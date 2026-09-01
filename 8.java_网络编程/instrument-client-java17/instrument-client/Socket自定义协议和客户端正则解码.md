

------

# 二、调试与排错（半包、粘包、协议偏移）

## ✔ 为什么会粘包 / 半包？（底层原理）

> **一句话总结：**
>  **TCP 不知道你的一条“消息”是多长，它只保证字节序按顺序到达**。



TCP **不关心消息边界**，只负责保证：

- 字节序列不丢失
- 字节序列按序到达

但不会保证：

- 一条 `send()` 对应一条 `recv()`

- 消息不会合并、拆分

  > ```pgsql
  > 正常包：      [header][body][crc]
  > 粘包：        [header][body][crc][header][body][crc]
  > 半包：        [header][body-part1
  >                body-part2][crc]
  > 魔数偏移：    [XX XX XX XX][header][body][crc]
  > ```

### 📌 粘包出现的原因

- 应用层发送太快 → Nagle 算法合并 small packet
- OS TCP 缓冲区满载调度 → 多个应用层包合并为一个 segment

### 📌 半包出现的原因

- 单个报文 > MSS → TCP 会自动拆分 segment
- 网络阻塞 → recv() 读取不足
- OS TCP buffer 中数据不足 → 稍后继续到达



------

## ✔ 如何解决？（使用 length 进行“自解释协议”）

为了正确解包，需要满足：

1. **魔数（2~4 bytes）** — 校验流是否对齐
2. **length 字段（固定长度）** — body 多长
3. **CRC 校验（可选）**
4. **循环读取 + ByteBuffer 状态机**

------

## ✔ ByteBuffer正确的处理流程（可直接复用）

```java
public void process(ByteBuffer buf) {
    buf.flip(); // 切换到读模式

    while (true) {
        // 1. header 长度不够 → break 等待下一次数据
        if (buf.remaining() < HEADER_SIZE) {
            break;
        }

        buf.mark(); // 记录 header 起始位置

        // 2. 读取魔数
        int magic = buf.getShort();
        if (magic != MAGIC_VALUE) {
            // 魔数错位 → 跳过 1 字节继续找头
            buf.reset();
            buf.get();  // 跳过一个字节，重新找魔数
            continue;
        }

        // 3. 读取 length
        int length = buf.getInt();

        // 4. body 不够 → reset 退回 header 起点
        if (buf.remaining() < length) {
            buf.reset();
            break;
        }

        // 5. 读取完整 body
        byte[] body = new byte[length];
        buf.get(body);

        // 6. 处理完整消息
        handle(body);
    }

    buf.compact(); // 移动未处理数据到 buffer 开头
}
```

### 📌 为什么 reset 非常关键？

如果读取 length 后 body 不够，就必须：

- `reset()` 回到 header 位置
- 保留数据等待下一次接收

否则流会错位，从而导致“魔数偏移”。

------

## ⭐ 魔数偏移的极其常见 Bug（必看）

示例现象：

```
00 CA FE 01 00 00 00 05 48 65 6C 6C 6F ...
↑ 正确位置      ↑ 但是程序从这里开始当作魔数
```

### 常见原因分析：

| 原因               | 解释                                    |
| ------------------ | --------------------------------------- |
| ❌ 半包时没有 reset | 读过头 → 偏移 → 魔数错位                |
| ❌ length 写错      | 读长度错误，导致后面数据全部错位        |
| ❌ CRC 字节序写反   | CRC 校验失败 → 继续当垃圾数据处理       |
| ❌ 未使用 compact   | 未处理的数据留在中间位置 → 下次读取错位 |

**魔数偏移 ≈ 100% 表示你的半包/粘包处理有问题。**

------

# 三、常见问题 FAQ（扩展版）

------

## ❓ 为什么不用分隔符？比如 `\n`？

**分隔符几乎必定出问题：**

- TCP 会把 `\n` 分散到不同 segment
- `send("abc\n")` → `recv()` 可能只拿到 `"a"`

因此，分隔符协议非常脆弱（除非使用 Netty LineBasedFrameDecoder）。

------

## ❓ length 字段应该放哪里？

推荐结构：

| 字段    | 长度 | 说明       |
| ------- | ---- | ---------- |
| magic   | 2/4  | 检测流对齐 |
| version | 1    | 可选       |
| type    | 1    | 可选       |
| length  | 4    | body 长度  |
| body    | N    | 二进制内容 |
| crc32   | 4    | 可选       |

注意：**length 不包含魔数、length 自身，只包含 body。**

------

## ❓ CRC32 能防攻击吗？

不能。
 CRC 设计用于：

- 检测随机比特错误
- 网络传输错误

不能：

- 防篡改
- 防攻击
- 保密性

需要安全性 → 使用：

- HMAC-SHA256
- AES-GCM
- RSA / ECDSA 签名

------

## ❓ ByteBuffer 为什么必须 flip / reset / compact？

| 方法        | 作用                                     |
| ----------- | ---------------------------------------- |
| `flip()`    | 将写模式 → 读模式；limit 设置为 position |
| `mark()`    | 记录当前位置（header 起点）              |
| `reset()`   | 回滚到 mark（半包处理关键）              |
| `compact()` | 将未读数据挪到开头（流式解析必用）       |

**典型错误：使用 `clear()` 覆盖未处理数据！**

------

# ⭐ 完整协议（魔数 + 长度 + CRC）示例结构

```
| magic(2B) | version(1B) | type(1B) |
| length(4B) | body(N) | crc32(4B) |
```

- `length=N`
- `crc32` 校验 **header 之后的 [length + body]**（自由定义）

------



# 附录-Socket客户端正则

## SocketClient.java

```java
package socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * SocketClient 优化版
 *
 * <p>优化点： 1. 移除了易导致死锁的 Semaphore 机制，实现读写分离。 2. 优化了同步等待响应的逻辑，减少 CPU 轮询。 3. 增强了异常处理和资源释放安全性。 4.
 * 增加了缓冲区最大限制，防止内存溢出。 5. 补充全链路日志体系，关键操作可追溯；优化线程管理、正则缓存、连接校验等核心逻辑
 */
public class SocketClient implements AutoCloseable {
  // ====================== 核心常量定义（消除魔法值）======================
  private static final Logger log = LoggerFactory.getLogger(SocketClient.class);
  private static final int DEFAULT_CONNECT_TIMEOUT = 5000;
  private static final int DEFAULT_RECONNECT_ATTEMPTS = 3;
  private static final long DEFAULT_RECONNECT_DELAY = 1000;
  private static final long DEFAULT_HEARTBEAT_INTERVAL = 30000;
  private static final byte[] HEARTBEAT_MESSAGE = "HEARTBEAT\n".getBytes(StandardCharsets.UTF_8);
  private static final int MAX_BUFFER_SIZE = 5 * 1024 * 1024;
  private static final int RECEIVE_BUFFER_SIZE = 4096; // 接收缓冲区大小常量
  private static final long MAX_RESPONSE_WAIT_TIME = 60000; // 响应最大等待时间
  private static final int MAX_HEARTBEAT_FAIL_COUNT = 3; // 心跳连续失败阈值
  private static final long THREAD_TERMINATION_TIMEOUT = 1000; // 线程终止等待时间
  private static final int MAX_PATTERN_CACHE_SIZE = 100; // 正则表达式缓存最大数量

  // ====================== 正则缓存（避免重复编译，提升性能）======================
  private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

  // ====================== 配置参数 ======================
  private final String host;
  private final int port;
  private final int connectTimeout;
  private final int reconnectAttempts;
  private final long reconnectInitialDelay;
  private final long heartbeatInterval;

  // ====================== 核心组件 ======================
  private volatile Socket socket;
  private volatile OutputStream outputStream;
  private Thread receiveThread; // 独立接收线程
  private final AtomicBoolean isRunning = new AtomicBoolean(false); // 运行状态
  private final AtomicBoolean isReconnecting = new AtomicBoolean(false); // 重连标记
  private final AtomicInteger heartbeatFailCount = new AtomicInteger(0); // 心跳失败计数器

  // ====================== 数据接收缓冲区（优化命名+复用）======================
  private final ByteArrayOutputStream bufferStream = new ByteArrayOutputStream(); // 接收缓冲区
  private final Object bufferLock = new Object(); // 读缓冲区同步锁（优化命名，语义更清晰）
  private final Object writeOperationLock = new Object(); // 写操作同步锁（优化命名）
  private final byte[] receiveBuffer = new byte[RECEIVE_BUFFER_SIZE]; // 复用接收缓冲区，减少GC

  // ====================== 线程池 ======================
  private ScheduledExecutorService heartbeatScheduler; // 心跳线程池
  private final ExecutorService reconnectExecutor; // 重连单线程池（避免创建大量线程）

  // ====================== 构造器 ======================
  public SocketClient(String host, int port) {
    this(host, port, DEFAULT_CONNECT_TIMEOUT, DEFAULT_RECONNECT_ATTEMPTS, DEFAULT_RECONNECT_DELAY, DEFAULT_HEARTBEAT_INTERVAL);
  }

  // 新增带完整参数的构造函数
  public SocketClient(String host, int port, int connectTimeout, int reconnectAttempts, 
                     long reconnectInitialDelay, long heartbeatInterval) {
    this.host = host;
    this.port = port;
    this.connectTimeout = connectTimeout;
    this.reconnectAttempts = reconnectAttempts;
    this.reconnectInitialDelay = reconnectInitialDelay;
    this.heartbeatInterval = heartbeatInterval;
    log.info(
        "SocketClient initialized with config - host: {}, port: {}, connectTimeout: {}ms, reconnectAttempts: {}, heartbeatInterval: {}ms",
        host,
        port,
        connectTimeout,
        reconnectAttempts,
        heartbeatInterval);
    reconnectExecutor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "Socket-Reconnect-" + host + ":" + port);
              t.setDaemon(true);
              return t;
            });
  }

  /** 建立连接 */
  public synchronized boolean connect() {
    log.debug("Attempting to connect to {}:{}", host, port);
    if (isConnected()) {
      log.warn("Already connected to {}:{}", host, port);
      return true;
    }

    try {
      closeSocket(); // 清理旧连接
      log.debug("Old socket closed, preparing to create new connection");

      socket = new Socket();
      socket.connect(new InetSocketAddress(host, port), connectTimeout);

      // 优化 Socket 性能参数
      socket.setTcpNoDelay(true); // 禁用 Nagle 算法，降低实时通信延迟
      socket.setKeepAlive(true); // 开启 TCP 保活
      socket.setSoTimeout(0); // 接收线程阻塞读，不设置超时

      outputStream = socket.getOutputStream();

      // 更新状态 + 启动接收线程 + 启动心跳
      isRunning.set(true);
      isReconnecting.set(false);
      heartbeatFailCount.set(0); // 重置心跳失败计数
      startReceiveThread();
      startHeartbeat();

      log.info("Successfully connected to {}:{}", host, port);
      return true;
    } catch (IOException e) {
      log.error("Failed to connect to {}:{}", host, port, e);
      return false;
    }
  }

  /** 检查连接并自动重连（抽离重复逻辑，提升可维护性） */
  private boolean checkAndReconnect() {
    log.debug("Checking connection status to {}:{}", host, port);
    if (isConnected()) {
      return true;
    }
    log.warn("Connection to {}:{} is lost, attempting to reconnect", host, port);
    return reconnect();
  }

  /**
   * 发送消息并等待匹配正则的响应 (同步阻塞模式)
   *
   * @param message 待发送的字节数组
   * @param regexPattern 响应匹配的正则表达式
   * @param timeoutMillis 超时时间（毫秒）
   * @return 匹配到的响应数据，超时/异常返回null
   * @throws SocketClientException 自定义异常封装底层错误
   */
  public String sendMessageAndWaitForResponse(
      byte[] message, String regexPattern, long timeoutMillis) {
    // 1. 入参校验（增强鲁棒性）
    if (message == null || message.length == 0) {
      log.error("Message is null or empty, skip send");
      return null;
    }
    if (regexPattern == null || regexPattern.isBlank()) {
      log.error("Regex pattern is null or blank, invalid parameter");
      return null;
    }

    // 2. 检查连接，断开则自动重连
    if (!checkAndReconnect()) {
      log.error("Failed to reconnect to {}:{}, cannot send message", host, port);
      return null;
    }

    // 3. 获取缓存的正则（避免重复编译，提升性能）
    Pattern pattern;
    try {
      // 限制缓存大小，防止内存泄漏
      if (PATTERN_CACHE.size() >= MAX_PATTERN_CACHE_SIZE) {
        PATTERN_CACHE.clear(); // 简单清理策略，实际应用中可能需要LRU算法
      }
      pattern = PATTERN_CACHE.computeIfAbsent(regexPattern, Pattern::compile);
    } catch (Exception e) {
      log.error("Invalid regex pattern: {}, error: {}", regexPattern, e.getMessage(), e);
      throw new SocketClientException("Invalid regex pattern: " + regexPattern, e);
    }

    // 4. 清理接收缓冲区（避免旧数据干扰匹配，替换System.err为日志，脱敏）
    synchronized (bufferLock) {
      try {
        String bufferData = bufferStream.toString(StandardCharsets.UTF_8);
        if (!bufferData.isBlank()) {
          log.debug(
              "Clearing old buffer data (length: {}), data: {}",
              bufferData.length(),
              maskSensitiveData(bufferData));
        }
        bufferStream.reset();
      } catch (Exception e) {
        log.error("Failed to clear buffer data", e);
        throw new SocketClientException("Failed to clear buffer data", e);
      }
    }

    // 5. 发送消息
    if (!sendInternal(message)) {
      log.error("Failed to send message to {}:{}", host, port);
      return null;
    }

    // 6. 阻塞等待响应（带超时）
    long startTime = System.currentTimeMillis();
    long maxWait = Math.min(timeoutMillis, MAX_RESPONSE_WAIT_TIME); // 硬性限制最大等待60秒
    log.debug("Waiting for response matching regex: {}, timeout: {}ms", regexPattern, maxWait);

    synchronized (bufferLock) {
      while (System.currentTimeMillis() - startTime < maxWait) {
        try {
          String currentData = bufferStream.toString(StandardCharsets.UTF_8); // 简化编码调用
          // 检查匹配
          if (pattern.matcher(currentData).find()) {
            log.debug(
                "Matched response for regex: {}, data length: {}",
                regexPattern,
                currentData.length());
            return currentData;
          }

          // 未找到则释放锁等待（接收线程有数据时会 notify）
          long remainingTime = maxWait - (System.currentTimeMillis() - startTime);
          bufferLock.wait(remainingTime);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn("Wait for response interrupted", e);
          return null;
        } catch (Exception e) {
          log.error("Error while waiting for response", e);
          throw new SocketClientException("Error while waiting for response", e);
        }
      }
    }

    log.warn(
        "Timeout waiting for response matching regex: {} (max wait: {}ms)", regexPattern, maxWait);
    return null;
  }

  /** 纯发送消息 (异步) */
  public boolean sendMessage(byte[] message) {
    // 入参校验
    if (message == null || message.length == 0) {
      log.error("Message is null or empty, skip send");
      return false;
    }
    // 检查连接并重连
    if (!checkAndReconnect()) {
      log.error("Failed to reconnect to {}:{}, cannot send message", host, port);
      return false;
    }
    return sendInternal(message);
  }

  /** 内部发送逻辑（抽离复用） */
  private boolean sendInternal(byte[] message) {
    try {
      if (outputStream != null) {
        synchronized (writeOperationLock) { // 防止多线程写入错乱
          outputStream.write(message);
          outputStream.flush();
        }
        log.debug("Sent {} bytes to {}:{}", message.length, host, port);
        return true;
      } else {
        log.error("OutputStream is null, cannot send message to {}:{}", host, port);
        return false;
      }
    } catch (IOException e) {
      log.error("Failed to send message to {}:{}", host, port, e);
      handleConnectionError();
      return false;
    }
  }

  /** 启动接收线程 */
  private void startReceiveThread() {
    if (receiveThread != null && receiveThread.isAlive()) {
      log.warn("Receive thread is already running, skip start");
      return;
    }
    receiveThread = new Thread(this::receiveLoop, "Socket-Receiver-" + host + ":" + port);
    receiveThread.setDaemon(true);
    log.debug("Starting receive thread: {}", receiveThread.getName());
    receiveThread.start();
  }

  /** 接收数据循环（优化缓冲区复用） */
  private void receiveLoop() {
    log.info("Receive thread started: {}", Thread.currentThread().getName());
    
    // 获取当前socket的引用以确保在整个方法执行过程中使用同一实例
    Socket currentSocket = this.socket;
    if (currentSocket == null) {
      log.error("Socket is null in receive loop, exiting");
      return;
    }
    
    InputStream in = null;
    try {
      in = currentSocket.getInputStream();
    } catch (IOException e) {
      log.error("Failed to get input stream from socket", e);
      handleConnectionError();
      return;
    }
    
    try {
      while (isRunning.get() && !Thread.currentThread().isInterrupted() && currentSocket == this.socket) {
        int len = -1;
        try {
          len = in.read(receiveBuffer); // 阻塞读（SoTimeout=0），复用缓冲区
        } catch (IOException e) {
          if (isRunning.get()) {
            log.error("Read error in receive thread", e);
            handleConnectionError(); // 读异常触发重连
          }
          break;
        }
        
        if (len == -1) { // 服务端关闭连接
          log.info("Server closed connection, receive thread will exit");
          break;
        }
        if (len > 0) {
          synchronized (bufferLock) { // 所有缓冲区操作必须在锁内（线程安全）
            // 保护性清理：如果缓冲区太大，强制重置，防止OOM
            if (bufferStream.size() > MAX_BUFFER_SIZE) {
              log.warn(
                  "Buffer overflow (size: {} > max: {}), resetting buffer",
                  bufferStream.size(),
                  MAX_BUFFER_SIZE);
              bufferStream.reset();
            }
            bufferStream.write(receiveBuffer, 0, len);
            log.debug("Received {} bytes, current buffer size: {}", len, bufferStream.size());

            // 唤醒正在等待响应的业务线程
            bufferLock.notifyAll();
          }
        }
      }
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (IOException e) {
          log.warn("Error closing input stream", e);
        }
      }
      log.info("Receive thread stopped: {}", Thread.currentThread().getName());
    }
  }

  /** 处理连接异常（重连线程池化） */
  private void handleConnectionError() {
    if (isReconnecting.compareAndSet(false, true)) {
      log.debug("Submit reconnect task to thread pool");
      reconnectExecutor.submit(
          () -> {
            try {
              reconnect();
            } catch (Exception e) {
              log.error("Reconnect task failed", e);
              isReconnecting.set(false);
            }
          });
    }
  }

  /** 重连逻辑 */
  private synchronized boolean reconnect() {
    log.info("Reconnecting to {}:{} (max attempts: {})", host, port, reconnectAttempts);
    closeSocket(); // 先彻底关闭旧资源

    for (int i = 0; i < reconnectAttempts; i++) {
      if (!isRunning.get()) {
        log.warn("Client is stopped, abort reconnect");
        break;
      }

      try {
        long delay = reconnectInitialDelay * (long) Math.pow(2, i); // 指数退避
        log.info("Waiting {}ms before reconnect attempt {}/{}", delay, i + 1, reconnectAttempts);
        Thread.sleep(delay);

        if (connect()) { // 重连成功
          log.info(
              "Reconnected to {}:{} successfully (attempt {}/{})",
              host,
              port,
              i + 1,
              reconnectAttempts);
          isReconnecting.set(false);
          return true;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Reconnect thread interrupted", e);
        break;
      }
    }
    log.error("Reconnection failed after {} attempts to {}:{}", reconnectAttempts, host, port);
    isReconnecting.set(false);
    return false;
  }

  /** 启动心跳（增加失败计数阈值） */
  private void startHeartbeat() {
    stopHeartbeat();
    heartbeatScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "Heartbeat-Worker-" + host + ":" + port);
              t.setDaemon(true);
              return t;
            });

    log.debug("Starting heartbeat scheduler (interval: {}ms)", heartbeatInterval);
    heartbeatScheduler.scheduleAtFixedRate(
        () -> {
          if (isConnected()) {
            try {
              // 添加额外的空值检查
              OutputStream currentOutputStream = this.outputStream;
              if (currentOutputStream == null) {
                log.warn("OutputStream is null during heartbeat, skipping heartbeat");
                return;
              }
              
              synchronized (writeOperationLock) {
                currentOutputStream.write(HEARTBEAT_MESSAGE);
                currentOutputStream.flush();
              }
              heartbeatFailCount.set(0); // 重置失败计数
              log.debug("Heartbeat sent to {}:{}", host, port);
            } catch (IOException e) {
              int failCount = heartbeatFailCount.incrementAndGet();
              log.warn(
                  "Heartbeat failed (count: {}/{}), error: {}",
                  failCount,
                  MAX_HEARTBEAT_FAIL_COUNT,
                  e.getMessage());
              // 达到失败阈值才触发重连（避免网络抖动误触发）
              if (failCount >= MAX_HEARTBEAT_FAIL_COUNT) {
                log.error(
                    "Heartbeat failed {} times, triggering reconnect", MAX_HEARTBEAT_FAIL_COUNT);
                handleConnectionError();
                heartbeatFailCount.set(0); // 重置计数
              }
            }
          } else {
            log.debug("Not connected, skip heartbeat");
          }
        },
        heartbeatInterval,
        heartbeatInterval,
        TimeUnit.MILLISECONDS);
  }

  /** 停止心跳（完善线程池关闭逻辑，等待终止） */
  private void stopHeartbeat() {
    if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
      log.debug("Shutting down heartbeat scheduler");
      heartbeatScheduler.shutdownNow();
      try {
        if (!heartbeatScheduler.awaitTermination(
            THREAD_TERMINATION_TIMEOUT, TimeUnit.MILLISECONDS)) {
          log.warn(
              "Heartbeat scheduler did not terminate gracefully within {}ms",
              THREAD_TERMINATION_TIMEOUT);
        } else {
          log.debug("Heartbeat scheduler terminated successfully");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Heartbeat scheduler termination interrupted", e);
      }
    }
  }

  /** 关闭Socket（补充日志，不忽略异常） */
  private void closeSocket() {
    log.debug("Closing socket to {}:{}", host, port);
    Socket socketToClose = this.socket;
    if (socketToClose != null) {
      try {
        if (!socketToClose.isClosed()) {
          socketToClose.close();
          log.debug("Socket to {}:{} closed successfully", host, port);
        } else {
          log.debug("Socket to {}:{} is already closed", host, port);
        }
      } catch (IOException e) {
        log.error("Failed to close socket to {}:{}", host, port, e); // 记录关闭失败日志
      } finally {
        // 在finally块外设置为null
      }
    }
    this.socket = null;
    this.outputStream = null;
  }

  /** 精准判断连接状态（优化逻辑） */
  public boolean isConnected() {
    boolean connected =
        socket != null
            && socket.isConnected()
            && !socket.isClosed()
            && !socket.isInputShutdown()
            && !socket.isOutputShutdown()
            && socket.isBound();
    log.debug("Connection status to {}:{} - {}", host, port, connected);
    return connected;
  }

  /** 脱敏敏感数据（日志安全） */
  private String maskSensitiveData(String data) {
    // 可根据业务扩展脱敏规则，比如隐藏令牌、密码等
    if (data.length() > 100) {
      return data.substring(0, 100) + "...[truncated]";
    }
    return data;
  }

  /** 关闭客户端（完善资源释放） */
  @Override
  public void close() {
    log.info("Closing SocketClient to {}:{}", host, port);
    isRunning.set(false);
    stopHeartbeat(); // 停止心跳线程池
    closeSocket(); // 关闭Socket

    // 中断接收线程
    if (receiveThread != null) {
      receiveThread.interrupt();
      try {
        if (receiveThread.isAlive()) {
          receiveThread.join(THREAD_TERMINATION_TIMEOUT);
          if (receiveThread.isAlive()) {
            log.warn("Receive thread did not terminate gracefully");
          } else {
            log.debug("Receive thread terminated successfully");
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Receive thread join interrupted", e);
      }
    }

    // 关闭重连线程池
    if (!reconnectExecutor.isShutdown()) {
      log.debug("Shutting down reconnect executor");
      reconnectExecutor.shutdownNow();
      try {
        if (!reconnectExecutor.awaitTermination(
            THREAD_TERMINATION_TIMEOUT, TimeUnit.MILLISECONDS)) {
          log.warn("Reconnect executor did not terminate gracefully");
        } else {
          log.debug("Reconnect executor terminated successfully");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Reconnect executor termination interrupted", e);
      }
    }
    log.info("SocketClient to {}:{} closed completely", host, port);
  }

  // ====================== 自定义异常（统一异常处理）======================
  public static class SocketClientException extends RuntimeException {
    public SocketClientException(String message) {
      super(message);
    }

    public SocketClientException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  // ====================== 测试代码（建议抽离到独立测试类：SocketClientTest.java）======================
  public static void main(String[] args) {
    log.info("Starting SocketClient test");
    // 使用 try-with-resources 自动关闭
    try (SocketClient client = new SocketClient("localhost", 8080)) {
      if (client.connect()) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Client started. Type 'bye' to exit.");

        while (true) {
          System.out.print("cmd> ");
          String cmd = scanner.nextLine();
          if ("bye".equalsIgnoreCase(cmd)) {
            log.info("User input 'bye', exit test");
            break;
          }

          // 发送并等待包含 "OK" 或 "ERROR" 的响应
          String resp =
              client.sendMessageAndWaitForResponse(
                  cmd.getBytes(StandardCharsets.UTF_8), "(OK|ERROR)", 5000);

          if (resp != null) {
            System.out.println("Response: " + resp);
          } else {
            System.out.println("No response or timeout.");
          }
        }
      } else {
        log.error("Failed to connect to localhost:8080, test exit");
        System.out.println("Failed to connect to server.");
      }
    } catch (Exception e) {
      log.error("SocketClient test failed", e);
      System.out.println("Client error: " + e.getMessage());
    }
  }
}
```

## SocketServer.java

```java
package socket;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SocketServer 是一个简易的Socket服务器，能够接受客户端连接并处理消息 功能： - 接收客户端发送的命令 - 对命令增加时间戳和随机数进行回复 - 支持多客户端连接 -
 * 处理心跳消息
 */
public class SocketServer {
    private static final Logger log = LoggerFactory.getLogger(SocketServer.class);

    // 配置参数
    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_THREAD_POOL_SIZE = 10;
    private static final int DEFAULT_READ_TIMEOUT = 300000; // 5分钟
    private static final String HEARTBEAT_MESSAGE = "HEARTBEAT";
    private static final int BUFFER_SIZE = 1024;

    // 服务器相关属性
    private final int port;
    private final int threadPoolSize;
    private final int readTimeout;

    private ServerSocket serverSocket;
    private ExecutorService clientThreadPool;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 构造函数，使用默认配置 */
    public SocketServer() {
        this(DEFAULT_PORT, DEFAULT_THREAD_POOL_SIZE, DEFAULT_READ_TIMEOUT);
    }

    /**
     * 构造函数，使用自定义配置
     *
     * @param port 服务器端口号
     * @param threadPoolSize 线程池大小
     * @param readTimeout 读取超时时间
     */
    public SocketServer(int port, int threadPoolSize, int readTimeout) {
        this.port = port;
        this.threadPoolSize = threadPoolSize;
        this.readTimeout = readTimeout;
    }

    /**
     * 启动服务器
     *
     * @return 如果启动成功返回true，否则返回false
     */
    public boolean start() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);

            clientThreadPool =
                    Executors.newFixedThreadPool(
                            threadPoolSize,
                            r ->
                                    new Thread(
                                            r,
                                            "SocketServer-ClientHandler-"
                                                    + ThreadLocalRandom.current().nextInt(1000)));

            running.set(true);

            // 启动接受客户端连接的线程
            Thread acceptThread = new Thread(this::acceptClients, "SocketServer-AcceptThread");
            acceptThread.start();

            log.info("Socket server started successfully on port {}", port);
            return true;
        } catch (IOException e) {
            log.error("Failed to start socket server on port {}", port, e);
            return false;
        }
    }

    /** 停止服务器 */
    public void stop() {
        log.info("Stopping socket server on port {}", port);

        running.set(false);

        try {
            // 关闭ServerSocket
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                log.debug("ServerSocket closed successfully");
            }
        } catch (IOException e) {
            log.error("Error closing ServerSocket", e);
        }

        // 关闭线程池
        if (clientThreadPool != null && !clientThreadPool.isShutdown()) {
            clientThreadPool.shutdown();
            try {
                if (!clientThreadPool.awaitTermination(5000, TimeUnit.MILLISECONDS)) {
                    clientThreadPool.shutdownNow();
                }
                log.debug("Client thread pool closed successfully");
            } catch (InterruptedException e) {
                log.error("Error closing client thread pool", e);
                clientThreadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("Socket server stopped on port {}", port);
    }

    /** 接受客户端连接 */
    private void acceptClients() {
        log.debug("Accept clients thread started");

        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                log.info("New client connected: {}", clientSocket.getRemoteSocketAddress());

                // 处理客户端连接
                clientThreadPool.execute(() -> handleClient(clientSocket));

            } catch (IOException e) {
                if (running.get()) {
                    log.error("Error accepting client connection", e);
                }
            }
        }

        log.debug("Accept clients thread stopped");
    }

    /**
     * 处理客户端连接
     *
     * @param clientSocket 客户端Socket
     */
    private void handleClient(Socket clientSocket) {
        String clientAddress = clientSocket.getRemoteSocketAddress().toString();
        log.debug("Handling client: {}", clientAddress);

        InputStream in = null;
        OutputStream out = null;

        try {
            // 设置Socket选项
            clientSocket.setTcpNoDelay(true);
            clientSocket.setKeepAlive(true);
            clientSocket.setSoTimeout(readTimeout);

            in = clientSocket.getInputStream();
            out = clientSocket.getOutputStream();

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            while (running.get() && !clientSocket.isClosed()) {
                try {
                    bytesRead = in.read(buffer);
                    if (bytesRead == -1) {
                        log.info("Client disconnected: {}", clientAddress);
                        break;
                    }

                    String message = new String(buffer, 0, bytesRead, "UTF-8");
                    log.debug("Received message from client {}: {}", clientAddress, message);

                    // 处理心跳消息
                    if (HEARTBEAT_MESSAGE.equals(message)) {
                        log.debug("Received heartbeat from client: {}", clientAddress);
                        continue; // 不回复心跳消息
                    }

                    // 处理客户端命令
                    String response = processCommand(message);

                    // 发送回复
                    out.write(response.getBytes("UTF-8"));
                    out.flush();
                    log.debug("Sent response to client {}: {}", clientAddress, response);

                } catch (SocketTimeoutException e) {
                    log.debug("Read timeout for client: {}", clientAddress);
                    // 超时不关闭连接，继续等待下一条消息
                } catch (IOException e) {
                    if (running.get()) {
                        log.error("I/O error with client {}: {}", clientAddress, e.getMessage());
                    }
                    break;
                }
            }

        } catch (IOException e) {
            log.error(
                    "Error setting up client connection for {}: {}", clientAddress, e.getMessage());
        } finally {
            // 关闭资源
            closeQuietly(in);
            closeQuietly(out);
            closeQuietly(clientSocket);
            log.debug("Client connection closed: {}", clientAddress);
        }
    }

    /**
     * 处理客户端命令
     *
     * @param command 客户端发送的命令
     * @return 增加时间戳和随机数后的回复
     */
    private String processCommand(String command) {
        // 获取当前时间戳
        long timestamp = System.currentTimeMillis();

        // 生成随机数（1-1000）
        int randomNum = ThreadLocalRandom.current().nextInt(1, 1001);

        // 构造回复
        return String.format(
                "服务器回复 >>> [Timestamp: %d][Random: %d][Command: %s]", timestamp, randomNum, command);
    }

    /**
     * 安全关闭Closeable资源
     *
     * @param closeable 要关闭的资源
     */
    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                log.debug("Error closing resource: {}", e.getMessage());
            }
        }
    }

    /**
     * 主方法，用于启动服务器
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SocketServer server =
                new SocketServer(DEFAULT_PORT, DEFAULT_THREAD_POOL_SIZE, DEFAULT_READ_TIMEOUT);

        if (server.start()) {
            log.info("Socket server is running. Press Ctrl+C to stop.");

            // 等待用户中断
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

            try {
                // 主线程睡眠，保持服务器运行
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            log.error("Failed to start socket server");
        }
    }
}

```





