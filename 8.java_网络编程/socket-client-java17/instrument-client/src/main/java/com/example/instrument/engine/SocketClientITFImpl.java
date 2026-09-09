package com.example.instrument.engine;

import com.example.instrument.api.BlockingDataListener;
import com.example.instrument.api.ClientITF;
import com.example.instrument.api.DataListener;
import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.model.Command;
import com.example.instrument.model.Response;
import com.example.instrument.protocol.Protocol;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *   <li>pendingFrames + Protocol 内部缓存：发送前清空，彻底解决粘包/拆包
 *   <li>Condition 唤醒：接收线程收到数据后 signalAll，发送线程立即检查 matcher
 *   <li>自动重连自愈：每次发送前检查 socket 状态，断链自动恢复
 *   <li>双模式监听：回调(addListener) + 阻塞队列(blockingListener)
 * </ul>
 */
public final class SocketClientITFImpl implements ClientITF {

  private static final Logger log = LoggerFactory.getLogger(SocketClientITFImpl.class);

  /**
   * 阻塞队列容量上限（256）。
   *
   * <p>使用 {@link ArrayBlockingQueue}（有界）而不是 {@code LinkedBlockingQueue}（无界），
   * 是因为如果业务层消费慢于服务端推送，无界队列会直接 OOM。有界队列会让 offer 返回 false，
   * 自动起到**背压**效果：消费者不处理 → 队列满 → 推送数据被丢弃 → 逼业务层快处理。
   */
  private static final int QUEUE_CAPACITY = 256;

  /**
   * 写许可排队等待超时（固定 30 秒）。
   *
   * <p>为什么独立于 responseTimeout？responseTimeout 是"命令发出后等响应的最长时间"，
   * 而写许可等待是"等前一个请求释放许可的时间"——前一个请求**一定会**在 responseTimeout 内结束
   * （success、超时、断连三种路径都 finally release），所以这个超时只需要比 responseTimeout 大一点即可。
   * 用独立常量避免两个语义完全不同的超时互相耦合。
   */
  private static final Duration WRITE_PERMIT_WAIT_TIMEOUT = Duration.ofSeconds(30);

  /**
   * receiveLoop 外层等 readPermit 的超时（固定 1 秒）。
   *
   * <p>为什么独立于 reconnectInterval？reconnectInterval 是"断连后等多久再重连"（可能配成 30 秒），
   * 而 readPermit 等待是"有没有新命令需要读"——如果没命令，receiveLoop 应该快速回 while(running) 检查退出标志，
   * 不能傻等 reconnectInterval。
   */
  private static final Duration RECEIVE_LOOP_PERMIT_WAIT_TIMEOUT = Duration.ofSeconds(1);

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
   * 用信号量而不是 synchronized 的原因：sendAndMatch 可能阻塞很久（等响应），
   * synchronized 会把别的发送线程也卡住且不可中断；信号量支持限时 tryAcquire + 可中断 acquire。
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
   * 为什么不直接让接收线程持续读？因为：如果服务端在空闲时会主动推送数据，
   * 接收线程持续读会把这些推送也塞进 pendingFrames → sendAndMatch 的 matcher
   * 会**匹配到推送数据**，导致"命令还没发出去，结果就匹配到了一个推送"。
   * 信号量保证了：**只有发完命令，才开始读**。
   */
  private final Semaphore readPermit = new Semaphore(0, true);

  // ===== 锁 + 条件变量（两把锁各管一摊，不要混用）=====
  /**
   * 管 **socket 连接状态**：创建/关闭 Socket、设置 input/output、创建接收线程。
   * init / ensureConnected / closeSocket 使用这把锁，和 bufferLock 不要交叉持有，避免死锁。
   */
  private final ReentrantLock socketLock = new ReentrantLock();

  /**
   * 管 **pendingFrames + dataArrived 条件变量**：dispatchFrames / signalDataArrived / clearBuffers。
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
   * 比 CountDownLatch 好在哪里：CountDownLatch 是一次性的，用完要重建；
   * Condition 可以重复 signalAll，配合"接收线程多次写 buffer、发送线程多次检查"这种多轮交互更自然。
   */
  private final Condition dataArrived = bufferLock.newCondition();

  // ===== 监听器 / 异步队列 =====
  /**
   * 回调监听器集合 —— CopyOnWriteArraySet 为什么适合：
   *
   * <ul>
   * <li>迭代时不会抛 ConcurrentModificationException（dispatchFrames 遍历时用户可能 addListener）
   *   <li>写操作很少（监听器通常 init 时注册一次），读操作很多（每收到一帧就遍历）
   *   <li>比 synchronized Set 锁粒度更细（写时整体替换数组，读时无锁）
   * </ul>
   */
  private final Set<DataListener> listeners = new CopyOnWriteArraySet<>();

  /**
   * pendingFrames 容量上限（1024）。
   *
   * <p>为什么需要上限：如果服务端持续主动推送数据，且业务层长时间不调用 sendAndMatch
   * （不会触发 clearBuffers），pendingFrames 会无限增长导致 OOM。
   * 超过上限时丢弃最旧的帧，保证内存安全。
   */
  private static final int PENDING_FRAMES_CAPACITY = 1024;

  /**
   * 已解码的完整响应帧暂存 —— 供 sendAndMatch 匹配使用。
   *
   * <p>为什么需要这个字段：{@code protocol.decode()} 在切出完整帧后会将其从内部 buffer 移除，
   * 导致 {@code protocol.probeResponse()} 只能看到残余半帧字节。完整帧必须暂存到这里，
   * 让 sendAndMatch 的匹配循环能检查到。
   *
   * <p>线程安全：所有读写都在 {@code bufferLock} 保护下进行。
   */
  private final List<Response> pendingFrames = new ArrayList<>();

  /**
   * 阻塞队列 —— 双模式监听的另一半：
   *
   * <ul>
   * <li>回调模式：addListener() 注册 → dispatchFrames 锁外回调 listener.onData()
   *   <li>阻塞模式：blockingListener() 返回一个对象 → 用户调 take()/poll() 从队列取
   * </ul>
   *
   * 两个模式**互不干扰**：一帧数据同时会进 listeners（回调）和 queue（队列）。
   */
  private final BlockingQueue<Response> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

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
      log.debug("Sleeping {} ms", d.toMillis());
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
    log.info("[{}] Initializing client, host={}, port={}", this, config.host(), config.port());
    running.set(true);
    socketLock.lock();
    try {
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
        log.debug("[{}] Receiver thread started", this);
      }
    } finally {
      socketLock.unlock();
    }
  }

  @Override
  public void connect() {
    log.info("[{}] Connecting to {}:{}", this, config.host(), config.port());
    requireInitialized();
    ensureConnected();
    log.info("[{}] Connected successfully", this);
  }

  /** 严格判断连接有效性。 条件：socket 存在 + isConnected + 未关闭 + 输入/输出流未 shutdown。 */
  @Override
  public boolean isConnected() {
    Socket s = socket;
    if (s == null) return false; // socket 已被 closeSocket 置 null，直接返回 false，避免 NPE
    return s.isConnected()
        && !s.isClosed()
        && !s.isInputShutdown()
        && !s.isOutputShutdown();
  }

  /** 断开连接 + 停止接收线程。调用后可重新 init() + connect() 恢复。 */
  @Override
  public void disconnect() {
    log.info("[{}] Disconnecting...", this);
    running.set(false);
    initialized.set(false);
    closeSocket();
    Thread t = receiverThread;
    if (t != null) {
      t.interrupt();
      try {
        t.join(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (t.isAlive()) {
        log.warn("[{}] Receiver thread did not terminate within 2s", this);
      }
    }
    bufferLock.lock();
    try {
      dataArrived.signalAll();
    } finally {
      bufferLock.unlock();
    }
    clearBuffers();
    log.info("[{}] Disconnected", this);
  }

  // ============ 发送核心 ============

  /**
   * 发送命令 + 等待满足 matcher 的响应。
   *
   * <h4>完整时序</h4>
   *
   * <pre>
   *  发送线程                                接收线程 (receiveLoop)
   *    │                                       │
   *    ├─ ① writePermit.acquire()              │
   *    ├─ ② clearBuffers() ← 解决粘包         │
   *    ├─ ③ ensureConnected()                  │
   *    ├─ ④ protocol.encode() + write + flush  │
   *    │    (写失败→重连→重试)                  │
   *    ├─ ⑤ readPermit.release()               ├─ readPermit.acquire()
   *    ├─ ⑥ loop:                               │   (等发送完才开始读)
   *    │   bufferLock → 查 pendingFrames        ├─ in.read → protocol.decode()
   *    │             → 查 probeResponse()       ├─ dispatchFrames()
   *    │   matcher.matches()?                   ├─ signalAll(dataArrived)
   *    │   yes → return                         │
   *    │   no  → awaitNanos() ◄─────────────────┤  (发送线程被唤醒后立即回检)
   *    └─ writePermit.release()                 │
   * </pre>
   */
  @Override
  public Response sendAndMatch(Command command, ResponseMatcher matcher, Duration timeout) {
    requireInitialized();
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(matcher, "matcher");

    Duration effectiveTimeout = timeout == null ? config.responseTimeout() : timeout;
    if (effectiveTimeout.isNegative() || effectiveTimeout.isZero())
      throw new IllegalArgumentException("timeout must be positive");

    log.debug("[{}] Acquire write permit for command: {}, timeout={}", this, command, effectiveTimeout);
    boolean acquired;
    try {
      acquired = writePermit.tryAcquire(WRITE_PERMIT_WAIT_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SocketClientException("interrupted before acquiring write permit", e);
    }
    if (!acquired)
      throw new SocketClientException(
          "another request is still pending (waited " + WRITE_PERMIT_WAIT_TIMEOUT + ")");

    try {
      // 清空缓存  确保链接  发送消息 释放读许可
      clearBuffers();
      ensureConnected();
      byte[] frame = protocol.encode(command);
      writeWithRetry(frame);
      log.debug("[{}] Command sent: {}, releasing read permit", this, command);
      readPermit.release();

      // 等待响应
      long deadlineNanos = System.nanoTime() + effectiveTimeout.toNanos();
      while (running.get()) {
        bufferLock.lock();
        try {
          int size = pendingFrames.size();
          log.debug("[{}] Checking pending frames, count={}", this, size);
          for (int i = 0; i < size; i++) {
            Response r = pendingFrames.get(i);
            log.debug("[{}] Checking frame[{}]: {}", this, i, r.text());
            if (matcher.matches(r)) {
              pendingFrames.remove(i);
              log.debug("[{}] Response matched: {}", this, r.text());
              return r;
            }
          }

          if (!isConnected())
            throw new SocketClientException("socket disconnected while waiting response");

          long remaining = deadlineNanos - System.nanoTime();
          if (remaining <= 0) {
            log.warn("[{}] Response timeout, command={}, timeout={}ms", this, command, effectiveTimeout.toMillis());
            throw new SocketClientException("response not matched within " + effectiveTimeout.toMillis() + " ms");
          }

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
    Pattern compiled = Pattern.compile(regex, Pattern.DOTALL);
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
   * 接收循环。读许可协调 → 连通检查 → 内层读 → signalAll。
   *
   * <p>读许可（初始 0）在外层循环 acquire：sendAndMatch 释放一个 = "可以开始读了"。
   * 一旦拿到 permit，内层循环持续读（socketSoTimeout 只做节流，不退出），直到 socket 断开或 EOF。
   *
   * <p>任何 IOException 都 closeSocket + signalAll + sleep(reconnectInterval) 重试，
   * 避免接收线程因网络抖动而死锁（接收线程不能等重连，发送线程在 awaitNanos）。
   */
  private void receiveLoop() {
    byte[] readBuffer = new byte[8192];
    log.debug("[{}] Receive loop started", this);
    while (running.get()) {
      try {
        boolean acquired;
        try {
          acquired =
              readPermit.tryAcquire(
                  RECEIVE_LOOP_PERMIT_WAIT_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
        if (!acquired) continue;

        log.debug("[{}] Read permit acquired, entering read loop", this);
        if (!isConnected()) {
          log.debug("[{}] Socket not connected, triggering ensureConnected", this);
          ensureConnected();
        }
        InputStream in = input;
        if (in == null) continue;

        while (running.get()) {
          int n;
          try {
            n = in.read(readBuffer);
          } catch (SocketTimeoutException ste) {
            continue;
          } catch (IOException ioe) {
            log.warn("[{}] IO error reading from socket: {}, closing socket", this, ioe.getMessage());
            closeSocket();
            break;
          }
          if (n < 0) {
            log.warn("[{}] End of stream received (remote closed)", this);
            throw new EOFException("remote closed input stream");
          }
          if (n == 0) continue;

          log.debug("[{}] Read {} bytes from socket", this, n);
          List<Response> frames = protocol.decode(readBuffer, 0, n);
          if (!frames.isEmpty()) {
            log.debug("[{}] Decoded {} frame(s)", this, frames.size());
            dispatchFrames(frames);
          } else {
            log.debug("[{}] No complete frame yet, signaling", this);
            signalDataArrived();
          }
        }
      } catch (IOException | SocketClientException e) {
        if (!running.get()) break;
        log.error("[{}] Connection error in receive loop: {}, will retry after {}",
            this, e.getMessage(), config.reconnectInterval());
        closeSocket();
        bufferLock.lock();
        try {
          dataArrived.signalAll();
        } finally {
          bufferLock.unlock();
        }
        sleepQuietly(config.reconnectInterval());
      } catch (RuntimeException e) {
        log.error("[{}] Unexpected error in receive loop: {}, will retry after {}",
            this, e.getMessage(), config.reconnectInterval());
        closeSocket();
        sleepQuietly(config.reconnectInterval());
      }
    }
    log.debug("[{}] Receive loop exited", this);
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
   * <li>**双重检查**：如果第一个判断时另一个线程已经在重连，socketLock 能保证只有一个线程走入真正的重连逻辑。
   *   <li>**{@code maxReconnectAttempts + 1}**：配置里的 maxReconnectAttempts=3 表示"额外尝试 3 次"，
   *       加 1 表示"首次尝试 + 额外尝试"总共 4 次。{@code Math.max(1, ...)} 保证至少试一次。
   *   <li>**设置 TCP_NODELAY**：禁用 Nagle 算法，仪器通信通常希望命令立即发出去，不要攒一批再发。
   *   <li>**设置 SO_KEEPALIVE**：让操作系统自动探测死连接（空闲 2 小时后发送探测包），避免 socket 在对端死了的情况下还表现为"已连接"。
   *   <li>**设置 SO_TIMEOUT**：非阻塞 read 的超时常量 —— 这个值也被 receiveLoop 用作"有数据就读一批，没数据就退出内层循环"的节流机制。
   *   <li>**锁外 sleep**：重连失败后先释放 socketLock 再 sleep，避免阻塞其他需要 socketLock 的线程（如 disconnect）。
   * </ul>
   */
  private void ensureConnected() {
    if (isConnected()) return;
    socketLock.lock();
    try {
      if (isConnected()) return;

      IOException last = null;
      int attempts = Math.max(1, config.maxReconnectAttempts() + 1);// 首次尝试 + 额外尝试

      for (int i = 0; i < attempts && running.get(); i++) {
        log.info("[{}] Connection attempt {}/{} to {}:{}", this, i + 1, attempts, config.host(), config.port());
        try {
          Socket newSocket = new Socket();
          newSocket.setTcpNoDelay(true); // 禁用 Nagle 算法，确保命令立即发送出去
          newSocket.setKeepAlive(true); // 让操作系统自动探测死连接，避免 socket 在对端死了的情况下还表现为"已连接"
          newSocket.setSoTimeout(
              (int) Math.max(1, config.socketReadTimeout().toMillis()));
          newSocket.connect(
              new InetSocketAddress(config.host(), config.port()),
              (int) Math.max(1, config.connectTimeout().toMillis()));
          socket = newSocket;
          input = newSocket.getInputStream();
          output = newSocket.getOutputStream();
          log.info("[{}] Connected successfully on attempt {}/{}", this, i + 1, attempts);
          return;
        } catch (IOException e) {
          last = e;
          log.warn("[{}] Connection attempt {}/{} failed: {}", this, i + 1, attempts, e.getMessage());
          closeSocket();
        }
        if (i < attempts - 1 && running.get()) {
          long baseMs = config.reconnectInterval().toMillis();
          long backoffMs = Math.min(baseMs * (1L << i), 30_000L);
          log.debug("[{}] Waiting {} ms before next retry (exponential backoff, attempt {})", this, backoffMs, i + 1);
          socketLock.unlock();
          try {
            sleepQuietly(Duration.ofMillis(backoffMs));
          } finally {
            socketLock.lock();
          }
          if (isConnected()) return;
        }
      }
      if (last != null) {
        log.error("[{}] All {} connection attempts failed to {}:{}", this, attempts, config.host(), config.port());
        throw new SocketClientException(
            "cannot connect to %s:%d".formatted(config.host(), config.port()), last);
      }
    } finally {
      socketLock.unlock();
    }
  }

  /**
   * 写出帧 —— 自动处理 OutputStream 单方面关闭的场景。
   *
   * <p>为什么需要"写失败→重连→再写一次"：TCP 连接是**双向半关闭**的。
   * 如果对端服务器只关了它的一半（关闭 input 但 output 还开着），
   * 客户端这边 socket.isConnected() 仍然返回 true。此时写数据会在 flush 时抛 IOException（Broken pipe），
   * 因为对端已经不会再回 ACK 了。所以：先 closeSocket 彻底清掉旧连接 → ensureConnected 建新连接 → 再写一次。
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
      log.debug("[{}] Data written successfully, {} bytes", this, frame.length);
    } catch (IOException first) {
      if (!config.retrySendOnWriteFailure()) {
        log.error("[{}] Write failed, retry disabled: {}", this, first.getMessage());
        closeSocket();
        throw new SocketClientException("send failed", first);
      }
      log.warn("[{}] Write failed, attempting reconnect and retry: {}", this, first.getMessage());
      closeSocket();
      ensureConnected();
      OutputStream out2 = output;
      if (out2 == null)
        throw new SocketClientException("output stream null after reconnect", first);
      try {
        out2.write(frame);
        out2.flush();
        log.info("[{}] Write succeeded after reconnect, {} bytes", this, frame.length);
      } catch (IOException second) {
        log.error("[{}] Write failed after reconnect: {}", this, second.getMessage());
        closeSocket();
        throw new SocketClientException("send failed after reconnect", second);
      }
    }
  }

  // ==================================================================
  //  缓冲区操作（已下沉到 Protocol 层）
  // ==================================================================

  /**
   * 分发协议解码出的完整帧 —— 暂存待匹配 + 入阻塞队列 + 回调监听器 + 唤醒等待线程。
   */
  private void dispatchFrames(List<Response> frames) {
    bufferLock.lock();
    try {
      for (Response response : frames) {
        if (pendingFrames.size() >= PENDING_FRAMES_CAPACITY) {
          pendingFrames.remove(0);
          log.warn("[{}] Pending frames capacity exceeded, dropped oldest frame", this);
        }
        pendingFrames.add(response);
        if (!queue.offer(response)) {
          log.warn("[{}] Blocking queue full, dropped a frame (consumers too slow)", this);
        }
        log.debug("[{}] Frame dispatched: {}", this, response.text());
      }
      dataArrived.signalAll();
    } finally {
      bufferLock.unlock();
    }
    for (Response response : frames) {
      for (DataListener listener : listeners) {
        try {
          listener.onData(response);
        } catch (RuntimeException e) {
          log.warn("[{}] Listener threw exception: {}", this, e.getMessage());
        }
      }
    }
  }

  /**
   * 仅唤醒等待线程 —— 当本次 read 没有产出完整帧（但有新字节累积到 protocol 内部）时调用。
   * 发送线程的 probeResponse 可能能匹配到"半截帧"的文本。
   */
  private void signalDataArrived() {
    bufferLock.lock();
    try {
      dataArrived.signalAll();
    } finally {
      bufferLock.unlock();
    }
  }

  /**
   * 清空协议内部的跨包缓存 + 待匹配帧 + 阻塞队列 + 残余读许可。
   * sendAndMatch 发送前 + disconnect 时调用。
   */
  private void clearBuffers() {
    bufferLock.lock();
    try {
      pendingFrames.clear();
    } finally {
      bufferLock.unlock();
    }
    queue.clear();
    protocol.clearCache();
    readPermit.drainPermits();
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
      log.debug("[{}] Socket closed", this);
    } finally {
      socketLock.unlock();
    }
  }

  @Override
  public String toString() {
    return "SocketClient[%s:%d]".formatted(config.host(), config.port());
  }
}