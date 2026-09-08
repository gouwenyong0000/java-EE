package com.example.instrument.engine;

import com.example.instrument.api.BlockingDataListener;
import com.example.instrument.api.ClientITF;
import com.example.instrument.api.DataListener;
import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.model.Command;
import com.example.instrument.model.Response;
import com.example.instrument.protocol.Protocol;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * 仪器 TCP 客户端核心引擎。
 *
 * <h3>架构分层</h3>
 *
 * <pre>
 *   接口层 ClientITF ─── 契约：init/connect/sendAndRegex/...
 *      │
 *   协议层 Protocol   ─── 编解码：LineProtocol / LengthFieldProtocol
 *      │
 *   通信层 Engine     ─── 本类：TCP 连接 + 线程 + 重连 + 缓冲区
 *      │
 *   业务适配层 Demo   ─── 为什么传、传完做什么
 * </pre>
 *
 * <h3>核心亮点</h3>
 *
 * <ul>
 *   <li>写许可(1) + 读许可(0) 信号量协调发送/接收节奏，保证实时性又实现同步阻塞
 *   <li>byteBuffer + textBuffer 双缓冲区 + 发送前清空，彻底解决粘包/拆包
 *   <li>Condition 唤醒：接收线程收到数据后 signalAll，发送线程立即检查正则
 *   <li>自动重连自愈：每次发送前检查 socket 状态，断链自动恢复
 *   <li>双模式监听：回调(addListener) + 阻塞队列(blockingListener)
 * </ul>
 */
public final class SocketClientITFImpl implements ClientITF {

  /**
   * 阻塞队列容量上限（256）。
   *
   * <p>使用 {@link ArrayBlockingQueue}（有界）而不是 {@code LinkedBlockingQueue}（无界），
   * 是因为如果业务层消费慢于服务端推送，无界队列会直接 OOM。有界队列会让 offer 返回 false， 自动起到**背压**效果：消费者不处理 → 队列满 → 推送数据被丢弃 →
   * 逼业务层快处理。
   */
  private static final int QUEUE_CAPACITY = 256;

  private final SocketClientConfig config;
  private final Protocol protocol;

  // ===== 运行状态 =====
  /** 是否已初始化（init() 被调用过且 receiverThread 已启动）。 */
  private final AtomicBoolean initialized = new AtomicBoolean();

  /** 总开关：为 false 时 receiveLoop 的 while 循环会在下一次检查时退出。 */
  private final AtomicBoolean running = new AtomicBoolean();

  // ===== 信号量协调（核心时序控制）=====
  /**
   * 写许可（初始 1，公平模式）：保证**同一时刻只有一个发送操作在跑**。
   *
   * <pre>
   *   sendAndMatch 前：writePermit.tryAcquire(timeout)  ← 消耗 1
   *   sendAndMatch finally 中：writePermit.release()    ← 归还 1
   * </pre>
   *
   * 用信号量而不是 synchronized 的原因：sendAndMatch 可能阻塞很久（等响应），synchronized 会把别的 发送线程也卡住且不可中断；信号量支持限时
   * tryAcquire + 可中断 acquire。
   */
  private final Semaphore writePermit = new Semaphore(1, true);

  /**
   * 读许可（初始 0，公平模式）：协调"什么时候开始读 socket"。
   *
   * <pre>
   *   sendAndMatch 写出成功后：readPermit.release()      ← 给 1 个"读令牌"
   *   receiveLoop 外层循环：  readPermit.tryAcquire(...) ← 等令牌
   * </pre>
   *
   * 为什么不直接让接收线程持续读？因为：如果服务端在空闲时会主动推送数据， 接收线程持续读会把这些推送也塞进 byteBuffer → sendAndMatch
   * 的正则匹配会**匹配到推送数据**， 导致"命令还没发出去，结果就匹配到了一个推送"。信号量保证了：**只有发完命令，才开始读**。
   */
  private final Semaphore readPermit = new Semaphore(0, true);

  // ===== 锁 + 条件变量（两把锁各管一摊，不要混用）=====
  /**
   * 管 **socket 连接状态**：创建/关闭 Socket、设置 input/output。 只有 ensureConnected / closeSocket 使用这把锁，和
   * bufferLock 不要交叉持有，避免死锁。
   */
  private final ReentrantLock socketLock = new ReentrantLock();

  /**
   * 管 **双缓冲区 + dataArrived 条件变量**：appendToBuffers / dispatchFromBuffers / clearBuffers / makeProbe。
   */
  private final ReentrantLock bufferLock = new ReentrantLock();

  /**
   * 高效唤醒机制：接收线程收到新数据后 signalAll；发送线程在响应匹配循环中 awaitNanos。
   *
   * <p>比裸轮询 {@code while (!matches) Thread.sleep(1ms)} 好在哪里：
   *
   * <ul>
   *   <li>实时性：signalAll 立即唤醒，不用等下一个 sleep 周期
   *   <li>省 CPU：线程在 awaitNanos 期间完全挂起，不占 CPU 时间片
   *   <li>灵活：可以指定等待多久（awaitNanos 返回剩余时间），超时处理更精确
   * </ul>
   *
   * 比 CountDownLatch 好在哪里：CountDownLatch 是一次性的，用完要重建；Condition 可以重复 signalAll， 配合"接收线程多次写
   * buffer、发送线程多次检查"这种多轮交互更自然。
   */
  private final Condition dataArrived = bufferLock.newCondition();

  // ===== 监听器 / 异步队列 =====
  /**
   * 回调监听器集合 —— CopyOnWriteArraySet 为什么适合：
   *
   * <ul>
   *   <li>迭代时不会抛 ConcurrentModificationException（dispatchFromBuffers 遍历时用户可能 addListener）
   *   <li>写操作很少（监听器通常 init 时注册一次），读操作很多（每收到一帧就遍历）
   *   <li>比 synchronized Set 锁粒度更细（写时整体替换数组，读时无锁）
   * </ul>
   */
  private final Set<DataListener> listeners = new CopyOnWriteArraySet<>();

  /**
   * 阻塞队列 —— 双模式监听的另一半：
   *
   * <ul>
   *   <li>回调模式：addListener() 注册 → dispatchFromBuffers 直接调用 listener.onData()
   *   <li>阻塞模式：blockingListener() 返回一个对象 → 用户调 take()/poll() 从队列取
   * </ul>
   *
   * 两个模式**互不干扰**：一帧数据同时会进 listeners（回调）和 queue（队列）。
   */
  private final BlockingQueue<Response> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

  // ===== 双缓冲区（解决粘包/拆包的关键）=====
  /**
   * 字节缓冲区 —— 累积所有收到的原始字节。
   *
   * <p>为什么要和 textBuffer 同时存在？因为：
   *
   * <ul>
   *   <li>Protocol.decode() 是面向**字节**的（需要切二进制帧、算 CRC8）
   *   <li>ResponseMatcher 正则匹配是面向**文本**的（resp.text().matches(regex)）
   * </ul>
   *
   * 发送前 clearBuffers() → 接收线程持续 append → 发送线程 makeProbe() 构造探针 → matcher 检查 textBuffer 对应的
   * Response。
   */
  private final ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();

  /**
   * 文本缓冲区 —— 和 byteBuffer 同步增长，协议解码后用于正则匹配。
   *
   * <p>每次 dispatchFromBuffers 会 reset byteBuffer + textBuffer： 这并不丢弃协议层内部的缓存（例如 LengthFieldProtocol
   * 自己的 buffer 还在累积半截帧）， 只是把 engine 层的"暂存"交给 protocol 解析。protocol 内部管理它自己的跨包缓存。
   */
  private final StringBuilder textBuffer = new StringBuilder();

  // ===== IO 资源（volatile 保证可见性）=====
  private volatile Socket socket;
  private volatile InputStream input;
  private volatile OutputStream output;
  private volatile Thread receiverThread;

  public SocketClientITFImpl(SocketClientConfig config, Protocol protocol) {
    this.config = Objects.requireNonNull(config, "config");
    this.protocol = Objects.requireNonNull(protocol, "protocol");
  }

  // ============ 工具方法 ============

  private static void closeQuietly(AutoCloseable c) {
    if (c == null) return;
    try {
      c.close();
    } catch (Exception ignored) {
      /* NOP */
    }
  }

  private static void sleepQuietly(Duration d) {
    try {
      Thread.sleep(d.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();// 保持中断状态，避免后续代码被中断
    }
  }

  private void requireInitialized() {
    if (!initialized.get()) throw new IllegalStateException("call init() first");
  }

  // ============ 生命周期 ============

  /** 初始化：启动后台常驻接收线程。 支持多次调用：disconnect 后可以再次 init() 重启新接收线程，实现客户端自愈。 */
  @Override
  public void init() {
    running.set(true);
    if (initialized.compareAndSet(false, true)
        || receiverThread == null
        || !receiverThread.isAlive()) {
      Thread t =
          new Thread(
              this::receiveLoop,
              "instrument-receiver-%s:%d".formatted(config.host(), config.port()));
      t.setDaemon(true);
      receiverThread = t;
      t.start();
    }
  }

  @Override
  public void connect() {
    requireInitialized(); // 确保 init 已调用
    ensureConnected(); // 确保已连接
  }

  /** 严格判断连接有效性。 条件：socket 存在 + isConnected + 未关闭 + 输入/输出流未 shutdown。 */
  @Override
  public boolean isConnected() {
    Socket s = socket;
    return s != null
        && s.isConnected()
        && !s.isClosed()
        && !s.isInputShutdown()
        && !s.isOutputShutdown();
  }

  /** 断开连接 + 停止接收线程。调用后可重新 init() + connect() 恢复。 */
  @Override
  public void disconnect() {
    running.set(false);
    closeSocket();
    Thread t = receiverThread;
    if (t != null) t.interrupt();
    // 唤醒所有等待 dataArrived 的发送线程，防止 awaitNanos 卡到超时
    bufferLock.lock();
    try {
      dataArrived.signalAll();
    } finally {
      bufferLock.unlock();
    }
    clearBuffers();
  }

  // ============ 发送核心 ============

  /**
   * 发送命令 + 等待满足 matcher 的响应。
   *
   * <h4>完整时序</h4>
   *
   * <pre>
   *  发送线程                              接收线程 (receiveLoop)
   *    │                                     │
   *    ├─ ① writePermit.acquire()            │
   *    ├─ ② clearBuffers() ← 解决粘包       │
   *    ├─ ③ ensureConnected()                │
   *    ├─ ④ protocol.encode() + write + flush│
   *    │    (写失败→重连→重试)                │
   *    ├─ ⑤ readPermit.release()             ├─ readPermit.acquire()
   *    ├─ ⑥ loop:                             │   (等发送完才开始读)
   *    │   bufferLock → makeProbe()           ├─ in.read → appendToBuffers()
   *    │   matcher.matches()?                 ├─ dispatchFromBuffers()
   *    │   yes → return                       ├─ signalAll(dataArrived)
   *    │   no  → awaitNanos() ◄───────────────┤  (发送线程被唤醒后立即回检)
   *    └─ writePermit.release()               │
   * </pre>
   */
  @Override
  public Response sendAndMatch(Command command, ResponseMatcher matcher, Duration timeout) {
    requireInitialized();
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(matcher, "matcher");

    // timeout 为 null 时使用 config.responseTimeout，否则使用调用方传入值。
    // 修复原项目 responseTimeout 配置"形同虚设"的问题。
    Duration effectiveTimeout = timeout == null ? config.responseTimeout() : timeout;
    if (effectiveTimeout.isNegative() || effectiveTimeout.isZero())
      throw new IllegalArgumentException("timeout must be positive");

    // ① 消耗写许可，保证同一时刻只有一个发送操作在跑
    boolean acquired;
    try {
      acquired = writePermit.tryAcquire(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SocketClientException("interrupted before acquiring write permit", e);
    }
    if (!acquired) throw new SocketClientException("another request is still pending");

    try {
      // ② 清空双缓冲区 —— 粘包的根治方案
      clearBuffers();

      // ③ 连通检查 + 自动重连
      ensureConnected();

      // ④ 组装命令 + 写出（失败自动重连并重试一次）
      byte[] frame = protocol.encode(command);
      writeWithRetry(frame);

      // ⑤ 释放读许可，通知接收线程"可以开始读这条命令的响应了"
      readPermit.release();

      // ⑥ 循环等待响应匹配
      long deadlineNanos = System.nanoTime() + effectiveTimeout.toNanos();
      while (running.get()) {
        // 快速检查：持锁看一眼当前缓冲区是否已满足条件
        bufferLock.lock();
        try {
          Response probe = makeProbe();
          if (probe != null && matcher.matches(probe)) return probe;
        } finally {
          bufferLock.unlock();
        }

        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0)
          throw new SocketClientException("response not matched within " + effectiveTimeout);

        // 使用 Condition 高效等待，避免裸轮询
        bufferLock.lock();
        try {
          dataArrived.awaitNanos(Math.max(10L, remaining));
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new SocketClientException("interrupted while waiting response", ie);
        } finally {
          bufferLock.unlock();
        }
      }
      throw new SocketClientException("client disconnected while waiting response");
    } finally {
      writePermit.release();
    }
  }

  @Override
  public Response sendAndRegex(String command, String regex, Duration timeout) {
    Objects.requireNonNull(regex, "regex");
    Pattern compiled = Pattern.compile(regex);
    return sendAndMatch(
        Command.of(command), resp -> compiled.matcher(resp.text()).matches(), timeout);
  }

  // ============ 监听器 / 异步队列 ============

  @Override
  public void addListener(DataListener listener) {
    listeners.add(Objects.requireNonNull(listener, "listener"));
  }

  @Override
  public void removeListener(DataListener listener) {
    listeners.remove(listener);
  }

  @Override
  public BlockingDataListener blockingListener() {
    return new BlockingDataListener() {
      @Override
      public Response take() throws InterruptedException {
        return queue.take();
      }

      @Override
      public Response poll(Duration timeout) throws InterruptedException, TimeoutException {
        Response r = queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (r == null) throw new TimeoutException("no data within " + timeout);
        return r;
      }
    };
  }

  // ==================================================================
  //  后台常驻接收线程
  // ==================================================================

  /**
   * 接收循环。读许可协调 → 连通检查 → 内层读 → 写缓冲区 → signalAll。
   *
   * <p>读许可（初始 0）在外层循环 acquire：sendAndMatch 释放一个 = "轮到读这条命令的响应了"。 内层循环用 socketSoTimeout 兜底，每次读完
   * available() 的全部数据就跳出，等下一个读许可。
   *
   * <p>任何 IOException 都 closeSocket + signalAll + sleep(reconnectInterval) 重试，
   * 避免接收线程因网络抖动而死锁（接收线程不能等重连，发送线程在 awaitNanos）。
   */
  private void receiveLoop() {
    byte[] readBuffer = new byte[8192];
    while (running.get()) {
      try {
        // ① 读许可协调：发送完命令才开始读，避免把主动推送和命令响应粘在一起
        boolean acquired;
        try {
          acquired =
              readPermit.tryAcquire(config.reconnectInterval().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
        if (!acquired) continue;

        // ② 连通二次检查
        if (!isConnected()) ensureConnected();
        InputStream in = input;
        if (in == null) continue;

        // ③ 内层：持续读取直到 socketSoTimeout 到期（或 EOF）
        //
        // socketSoTimeout 的妙用：把原本阻塞的 in.read() 变成"阻塞读 + 超时打断"。
        // 有数据到达 → read 立即返回（n>0）→ append + dispatch → 继续读下一包
        // 没数据了  → 超时抛 SocketTimeoutException → break 出内层 → 回外层等下一个 readPermit
        // 对端关闭  → read 返回 -1  → 抛 EOFException → 进入外层 catch → closeSocket + 重试
        //
        // 这样设计比"外层一直 while(true) in.read()"好在哪里：
        // 每次读完一批数据后会等 readPermit（发送完新命令才会 release），
        // 避免在服务端空闲推送大量数据时 receiverThread 把 byteBuffer 塞爆
        while (running.get()) {
          int n;
          try {
            n = in.read(readBuffer);
          } catch (SocketTimeoutException ste) {
            // socketSoTimeout 到期 = "没数据了"，不是致命错误
            break;
          } catch (IOException ioe) {
            // 真实的 IO 错误（对端断开、网络抖动等）
            closeSocket();
            break;
          }
          if (n < 0) throw new EOFException("remote closed input stream");
          if (n == 0) continue; // readBuffer 是 8192 字节，正常不会返回 0，仅作防御

          appendToBuffers(readBuffer, 0, n);
          dispatchFromBuffers();
        }
      } catch (IOException | SocketClientException e) {
        if (!running.get()) break;
        closeSocket();
        // 关键！即使自己出错也要 signalAll：
        // 如果此时有 sendAndMatch 正在 awaitNanos，它可能一直等到超时（responseTimeout）才退出。
        // signalAll 让它立即被唤醒，检查 socket 状态，快速失败。
        bufferLock.lock();
        try {
          dataArrived.signalAll();
        } finally {
          bufferLock.unlock();
        }
        sleepQuietly(config.reconnectInterval());
      } catch (RuntimeException ignored) {
        // 用户监听器抛异常 / Protocol.decode 有 bug —— 不能让这个异常杀死接收线程。
        // 线程死了 = 没人读 socket = sendAndMatch 永远等不到响应。
        closeSocket();
        sleepQuietly(config.reconnectInterval());
      }
    }
  }

  // ==================================================================
  //  连通 & 写出
  // ==================================================================

  /**
   * 保证已连接 —— 网络抖动自愈的核心。
   *
   * <p>设计要点：
   *
   * <ul>
   *   <li>**双重检查**：如果第一个判断时另一个线程已经在重连，socketLock 能保证只有一个线程走入真正的重连逻辑。
   *   <li>**{@code maxReconnectAttempts + 1}**：配置里的 maxReconnectAttempts=3 表示"额外尝试 3 次"， 加 1
   *       表示"首次尝试 + 额外尝试"总共 4 次。{@code Math.max(1, ...)} 保证至少试一次。
   *   <li>**设置 TCP_NODELAY**：禁用 Nagle 算法，仪器通信通常希望命令立即发出去，不要攒一批再发。
   *   <li>**设置 SO_KEEPALIVE**：让操作系统自动探测死连接（空闲 2 小时后发送探测包），避免 socket 在对端死了的情况下还表现为"已连接"。
   *   <li>**设置 SO_TIMEOUT**：非阻塞 read 的超时常量 —— 这个值也被 receiveLoop 用作"有数据就读一批，没数据就退出内层循环"的节流机制。
   * </ul>
   */
  private void ensureConnected() {
    if (isConnected()) return;
    socketLock.lock();
    try {
      // 双重检查：防止多个线程同时走入重连逻辑
      if (isConnected()) return;

      IOException last = null;
      // maxReconnectAttempts 配置的是"额外重试次数"，所以 +1 代表首次也算一次尝试
      int attempts = Math.max(1, config.maxReconnectAttempts() + 1);

      for (int i = 0; i < attempts && running.get(); i++) {
        try {
          Socket newSocket = new Socket();
          newSocket.setTcpNoDelay(true); // 禁用 Nagle，小封包立即发
          newSocket.setKeepAlive(true); // 空闲时自动探测死连接
          newSocket.setSoTimeout( // 非阻塞 read 超时（毫秒）
              (int) Math.max(1, config.socketReadTimeout().toMillis()));
          newSocket.connect(
              new InetSocketAddress(config.host(), config.port()),
              (int) Math.max(1, config.connectTimeout().toMillis()));
          socket = newSocket;
          input = newSocket.getInputStream();
          output = newSocket.getOutputStream();
          return; // 连接成功 快速返回 退出循环
        } catch (IOException e) {
          last = e;
          closeSocket();
          sleepQuietly(config.reconnectInterval());
        }
      }
      if (last != null) // 最后一次重试失败
        throw new SocketClientException(
            "cannot connect to %s:%d".formatted(config.host(), config.port()), last);
    } finally {
      socketLock.unlock();
    }
  }

  /**
   * 写出帧 —— 自动处理 OutputStream 单方面关闭的场景。
   *
   * <p>为什么需要"写失败→重连→再写一次"： TCP 连接是**双向半关闭**的。如果对端服务器只关了它的一半（关闭 input 但 output 还开着）， 客户端这边
   * socket.isConnected() 仍然返回 true。此时写数据会在 flush 时抛 IOException（Broken pipe）， 因为对端已经不会再回 ACK 了。所以：先
   * closeSocket 彻底清掉旧连接 → ensureConnected 建新连接 → 再写一次。
   *
   * <p>为什么只重试一次？因为 {@link SocketClientConfig#retrySendOnWriteFailure} 控制这个行为。
   * 如果业务层认为"一次失败说明协议错了，不该再试"，可以把它设为 false。
   */
  private void writeWithRetry(byte[] frame) {
    ensureConnected();
    OutputStream out = output;
    if (out == null) throw new SocketClientException("output stream is null");

    try {
      out.write(frame);
      out.flush();
    } catch (IOException first) {
      if (!config.retrySendOnWriteFailure()) {
        closeSocket();
        throw new SocketClientException("send failed", first);
      }
      closeSocket();
      ensureConnected();
      OutputStream out2 = output;
      if (out2 == null)
        throw new SocketClientException("output stream null after reconnect", first);
      try {
        out2.write(frame);
        out2.flush();
      } catch (IOException second) {
        closeSocket();
        throw new SocketClientException("send failed after reconnect", second);
      }
    }
  }

  // ==================================================================
  //  双缓冲区（粘包/拆包根治方案）
  // ==================================================================

  /**
   * 追加原始字节 + 解码文本到双缓冲区（必须持 bufferLock 调用）。
   *
   * <p>用 {@code ByteBuffer.wrap + charset.decode} 而不是 {@code new String(data, charset)} 的原因：
   * 前者可以精确控制 offset/length 子数组，后者每次都 new String 无法只解码 [offset, offset+length) 区间。
   */
  private void appendToBuffers(byte[] data, int offset, int length) {
    bufferLock.lock();
    try {
      byteBuffer.write(data, offset, length);
      textBuffer.append(
          protocol.charset().decode(ByteBuffer.wrap(data, offset, length)).toString());
    } finally {
      bufferLock.unlock();
    }
  }

  /**
   * 从 byteBuffer 取出所有原始字节 → 交给 protocol.decode 切帧 → dispatch 回调 + 队列 → reset buffer → signalAll。
   *
   * <p>为什么 byteBuffer.reset() 后不会丢半截帧数据？ —— protocol.decode 内部维护了自己的跨包缓存（例如
   * LengthFieldProtocol.buffer），它会把收到的所有字节 先追加到自己的 buffer 里，解析完完整帧后把残余写回。engine 层的 byteBuffer 本质是"给
   * protocol 的输入暂存"， 并不是跨包缓存的最终决策者。
   *
   * <p>必须在持有 bufferLock 的前提下调用，因为它会修改 byteBuffer / textBuffer。
   */
  private void dispatchFromBuffers() {
    bufferLock.lock();
    try {
      byte[] raw = byteBuffer.toByteArray();
      List<Response> frames = protocol.decode(raw, 0, raw.length);
      byteBuffer.reset();
      textBuffer.setLength(0);

      for (Response response : frames) {
        // ① 先入阻塞队列（阻塞模式）
        queue.offer(response);
        // ② 再遍历回调监听器（回调模式）
        for (DataListener listener : listeners) {
          try {
            listener.onData(response);
          } catch (RuntimeException ignored) {
            // 用户回调异常保护：监听器抛异常不能杀死接收线程
          }
        }
      }

      // ③ 关键！唤醒 sendAndMatch 中 awaitNanos 的线程立即检查正则匹配
      dataArrived.signalAll();
    } finally {
      bufferLock.unlock();
    }
  }

  /** 清空双缓冲区。sendAndMatch 发送前调用，保证只匹配当前命令的响应。 */
  private void clearBuffers() {
    bufferLock.lock();
    try {
      byteBuffer.reset();
      textBuffer.setLength(0);
    } finally {
      bufferLock.unlock();
    }
  }

  /** 用当前双缓冲区内容快速构造一个探针 Response 供 matcher 检查（持 bufferLock 调用）。 */
  private Response makeProbe() {
    byte[] raw = byteBuffer.toByteArray();
    if (raw.length == 0) return null;
    return new Response(raw, protocol.charset());
  }

  // ==================================================================
  //  IO 资源管理
  // ==================================================================

  private void closeSocket() {
    socketLock.lock();
    try {
      closeQuietly(input);
      closeQuietly(output);
      closeQuietly(socket);
      input = null;
      output = null;
      socket = null;
    } finally {
      socketLock.unlock();
    }
  }
}
