package com.example.instrument.engine;

import com.example.instrument.api.BlockingDataListener;
import com.example.instrument.api.DataListener;
import com.example.instrument.api.ClientITF;
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
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * TCP instrument client.
 *
 * <p>负责维护底层 socket 生命周期、请求/响应匹配，以及后台接收线程与重连逻辑。
 */
public final class SocketClientITFImpl implements ClientITF {
  /** 客户端配置项，包含 host、port、超时和重连策略等。 */
  private final SocketClientConfig config;

  private final Protocol protocol; // 指定协议栈，负责编码和解码。

  // 线程安全的标志位，用于控制客户端的初始化、运行状态和发送权限。
  private final AtomicBoolean initialized = new AtomicBoolean();
  private final AtomicBoolean running = new AtomicBoolean();
  private final Semaphore sendPermit = new Semaphore(1, true);
  private final ReentrantLock socketLock = new ReentrantLock();
  private final ReentrantLock writeLock = new ReentrantLock();
  private final Set<DataListener> listeners = new CopyOnWriteArraySet<>();
  private final BlockingQueue<Response> queue = new LinkedBlockingQueue<>();
  private final PendingRequestHolder pendingRequestHolder = new PendingRequestHolder();

  // 以下成员变量在多线程环境下被访问，需使用 volatile 保证可见性。
  private volatile Socket socket;
  private volatile InputStream input;
  private volatile OutputStream output;
  private volatile Thread receiverThread;

  /**
   * Constructor.
   *
   * @param config 配置
   * @param protocol 指定协议类型 --> 编解码器
   */
  public SocketClientITFImpl(SocketClientConfig config, Protocol protocol) {
    this.config = Objects.requireNonNull(config);
    this.protocol = Objects.requireNonNull(protocol);
  }

  @Override
  public void init() {
    // 仅在第一次初始化时启动后台接收线程；后续调用不重复创建线程。
    if (initialized.compareAndSet(false, true)) {
      running.set(true);
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
    requireInitialized();
    ensureConnected();
  }

  @Override
  public boolean isConnected() {
    Socket s = socket;
    return s != null
        && s.isConnected()
        && !s.isClosed()
        && !s.isInputShutdown()
        && !s.isOutputShutdown();
  }

  @Override
  public void disconnect() {
    running.set(false);
    closeSocket();
    Thread t = receiverThread;
    if (t != null) t.interrupt();
    failPending(new InstrumentClientException("client disconnected"));
  }

  @Override
  public Response sendAndMatch(Command command, ResponseMatcher matcher, Duration timeout) {
    // 发送前进行初始化校验和参数校验，避免在未 init 或非法超时下调用。
    requireInitialized();

    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(matcher, "matcher");
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero())
      throw new IllegalArgumentException("timeout <= 0");

    // 保证同一时刻只有一个请求在发送，避免多个线程互相污染 pendingRequest。
    boolean acquired;
    try {
      acquired = sendPermit.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InstrumentClientException("interrupted before send", e);
    }
    if (!acquired) throw new InstrumentClientException("another request is still waiting");

    // 当前请求注册到单槽位中，接收线程会用它来匹配对应响应。
    PendingRequest request = new PendingRequest(matcher);
    pendingRequestHolder.set(request);
    try {
      ensureConnected();
      byte[] frame = protocol.encode(command);
      writeWithReconnect(frame);
      // 等待响应线程使用 matcher 做匹配并通过 semaphore 释放当前请求。
      long waitMillis = timeout.toMillis();
      if (!request.semaphore.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
        pendingRequestHolder.clearIfSame(request);
        throw new InstrumentClientException("response timeout after " + timeout);
      }
      Throwable failure = request.failure;
      if (failure != null) throw new InstrumentClientException("request failed", failure);
      return request.response;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InstrumentClientException("interrupted while waiting response", e);
    } finally {
      pendingRequestHolder.clearIfSame(request);
      sendPermit.release();
    }
  }

  @Override
  public void addListener(DataListener listener) {
    listeners.add(Objects.requireNonNull(listener));
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

  private void receiveLoop() {
    // 读取缓冲区足够容纳常见响应帧，避免频繁扩容。
    byte[] readBuffer = new byte[8192];
    while (running.get()) {
      try {
        // 确保 socket 已建立；若断连则在这里重连并继续读取。
        ensureConnected();
        InputStream in = input;
        if (in == null) continue;
        int n;
        try {
          n = in.read(readBuffer);
        } catch (SocketTimeoutException e) {
          // socket 超时不是致命错误，继续保活并等待下一次读取。
          continue;
        }
        if (n < 0) throw new EOFException("remote closed input stream");
        if (n == 0) continue;
        List<Response> frames = protocol.decode(readBuffer, 0, n);
        for (Response response : frames) dispatch(response);
      } catch (IOException | InstrumentClientException e) {
        if (!running.get()) break;
        closeSocket();
        failPending(e);
        sleepQuietly(config.reconnectInterval());
      } catch (RuntimeException e) {
        // 无论解码器还是监听器抛异常，都不应直接杀死接收线程。
        closeSocket();
        failPending(e);
        sleepQuietly(config.reconnectInterval());
      }
    }
  }

  private void dispatch(Response response) {
    // 先尝试与当前挂起的请求匹配；一旦命中，则释放等待中的 sendAndMatch()。
    PendingRequest current = pendingRequestHolder.get();
    boolean matched = false;
    if (current != null) {
      try {
        matched = current.matcher.matches(response);
      } catch (RuntimeException ex) {
        current.fail(ex);
        return;
      }
      if (matched) current.complete(response);
    }

    // 无论是否命中请求，所有解码后的帧都会进入阻塞队列和监听器回调，便于异步消费。
    queue.offer(response);
    for (DataListener listener : listeners) {
      try {
        listener.onData(response);
      } catch (RuntimeException ignored) {
        /* user callback must not kill receiver */
      }
    }
  }

  /**
   * 保证当前客户端处于已连接状态。
   *
   * <p>该方法会先检查已有 socket 是否仍然有效；如果已失效，则在串行锁保护下执行重连逻辑。 重连过程会依次尝试连接到配置的 host/port，并在每次失败后清理旧
   * socket、等待重连间隔， 直到达到最大重试次数或连接成功为止。连接失败时抛出统一的 InstrumentClientException， 让调用方知道是连接层错误而不是业务逻辑错误。
   */
  private void ensureConnected() {
    // 若现有 socket 可用，则直接复用，避免重复建立连接。
    if (isConnected()) return;
    socketLock.lock();
    try {
      // 双重检查避免多个线程在同一时刻都进入重连逻辑，确保只保留一个有效 socket。
      if (isConnected()) return;
      IOException last = null;
      // maxReconnectAttempts 是“重试次数”，这里额外加 1 代表首次连接尝试。
      int attempts = Math.max(1, config.maxReconnectAttempts() + 1);
      for (int i = 0; i < attempts && running.get(); i++) {
        try {
          Socket newSocket = new Socket();
          newSocket.setTcpNoDelay(true);
          newSocket.setKeepAlive(true);
          newSocket.setSoTimeout((int) Math.max(1, config.socketReadTimeout().toMillis()));
          newSocket.connect(
              new InetSocketAddress(config.host(), config.port()),
              (int) Math.max(1, config.connectTimeout().toMillis()));
          InputStream newInput = newSocket.getInputStream();
          OutputStream newOutput = newSocket.getOutputStream();
          // 连接成功后更新全局 socket、input、output 引用，后续读写都走新连接。
          socket = newSocket;
          input = newInput;
          output = newOutput;
          return;
        } catch (IOException e) {
          last = e;
          // 每次失败都先清理旧状态，避免脏 socket 继续被复用。
          closeSocket();
          sleepQuietly(config.reconnectInterval());
        }
      }
      if (last != null)
        throw new InstrumentClientException(
            "cannot connect to %s:%d".formatted(config.host(), config.port()), last);
    } finally {
      socketLock.unlock();
    }
  }

  private void writeWithReconnect(byte[] frame) {
    // 发送时使用独立写锁，避免多个线程并发写 socket 导致报文混淆。
    writeLock.lock();
    try {
      ensureConnected();
      try {
        OutputStream out = output;
        if (out == null) throw new SocketException("output stream is null");
        out.write(frame);
        out.flush();
      } catch (IOException first) {
        // 发生写失败时，先清理当前 socket，再按策略重连后重试一次。
        closeSocket();
        if (!config.retrySendOnWriteFailure()) {
          throw new InstrumentClientException("send failed", first);
        }
        ensureConnected();
        try {
          output.write(frame);
          output.flush();
        } catch (IOException second) {
          closeSocket();
          throw new InstrumentClientException("send failed after reconnect", second);
        }
      }
    } finally {
      writeLock.unlock();
    }
  }

  private void failPending(Throwable cause) {
    // 当连接中断、超时或接收线程异常时，必须让挂起的请求失败并唤醒等待线程。
    PendingRequest current = pendingRequestHolder.get();
    if (current != null) current.fail(cause);
  }

  private void closeSocket() {
    // 关闭底层 socket 及其 IO 流，并清空引用，方便后续重新建立连接。
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

  private static void closeQuietly(AutoCloseable c) {
    if (c == null) return;
    try {
      c.close();
    } catch (Exception ignored) {
    }
  }

  private static void sleepQuietly(Duration d) {
    try {
      Thread.sleep(d.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 检查是否已经完成初始化，避免在未启动接收线程的状态下发送请求。 如果还未初始化则抛出 IllegalStateException异常。 */
  private void requireInitialized() {
    if (!initialized.get()) throw new IllegalStateException("call init() first");
  }

  /** 一次发送请求所对应的待完成上下文：matcher 用于匹配响应，semaphore 用于唤醒等待线程。 */
  private static final class PendingRequest {
    private final Semaphore semaphore = new Semaphore(0);
    private volatile ResponseMatcher matcher;
    private volatile Response response;
    private volatile Throwable failure;

    PendingRequest() {}

    PendingRequest(ResponseMatcher matcher) {
      this.matcher = matcher;
    }

    ResponseMatcher matcher() {
      return matcher;
    }

    void complete(Response response) {
      this.response = response;
      semaphore.release();
    }

    void fail(Throwable failure) {
      this.failure = failure;
      semaphore.release();
    }
  }

  /** 仅保存单个在途请求的槽位对象，使用 null 表示空闲状态。 */
  private static final class RequestSlot {
    private volatile PendingRequest current;
  }

  // 通过这个 holder 统一封装单槽位访问逻辑，保持外部字段最终不可变。
  private final class PendingRequestHolder {
    private final RequestSlot slot = new RequestSlot();

    void set(PendingRequest p) {
      slot.current = p;
    }

    PendingRequest get() {
      return slot.current;
    }

    void clearIfSame(PendingRequest p) {
      if (slot.current == p) slot.current = null;
    }
  }
}
