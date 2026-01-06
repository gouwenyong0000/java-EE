# 一、自定义服务端解码案例

------

## 一、协议设计（含 CRC32）

📌 1. 自定义协议格式（含 CRC32）

```
| magic(2) | version(1) | length(4) | body(N) | crc32(4) |
```

| 字段    | 长度 | 说明                 |
| ------- | ---- | -------------------- |
| magic   | 2B   | 协议标识（0xCAFE）   |
| version | 1B   | 协议版本，控制兼容性 |
| length  | 4B   | body 实际长度        |
| body    | N    | 数据内容             |
| crc32   | 4B   | 对 body 计算的 CRC32 |

------

📌 2. CRC32 计算示例（Java）

```java
CRC32 crc32 = new CRC32();
crc32.update(bodyBytes);
long crcValue = crc32.getValue();
```

------

📌 3. length 字段安全校验（重点补充）

防止恶意请求导致 OOM：

```java
public static final int MAX_BODY_SIZE = 1024 * 1024; // 1MB

if (bodyLength < 0 || bodyLength > MAX_BODY_SIZE) {
    System.out.println("非法长度字段，关闭连接");
    socket.close();
    return;
}
```

------

📌 4. 协议扩展字段（新增）

未来可扩展：

```java
| magic | version | msgType(1) | seq(4) | timestamp(8) | length | body | crc |
```

支持：

- 请求/响应类型（msgType）
- 请求序列号（seq）
- 防重放攻击（timestamp）



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





------

## ✅ 二、服务端（Server.java）— 带 CRC 校验版

Server.java（含 CRC 校验 + 半包处理 + 安全校验）

```java
package com.g.co;


import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

public class Server {

  private static final int MAX_BODY_SIZE = 1024 * 1024;

  public static void main(String[] args) throws Exception {
    ServerSocket serverSocket = new ServerSocket(9000);
    System.out.println("Server listening on port 9000");

    Socket socket = serverSocket.accept();
    System.out.println("Client connected!");

    InputStream in = socket.getInputStream();

    ByteBuffer buffer = ByteBuffer.allocate(4096);
    byte[] readBuf = new byte[512];
    int len;

    while ((len = in.read(readBuf)) != -1) {
      buffer.put(readBuf, 0, len);
      buffer.flip();

      while (true) {
        if (buffer.remaining() < 7) break;

        buffer.mark();

        short magic = buffer.getShort();
        if (magic != (short) 0xCAFE) {
          System.out.println("❌ 魔数不正确，关闭连接");
          socket.close();
          return;
        }

        byte version = buffer.get();
        int bodyLength = buffer.getInt();

        if (bodyLength < 0 || bodyLength > MAX_BODY_SIZE) {
          System.out.println("❌ length 非法：" + bodyLength);
          return;
        }

        if (buffer.remaining() < bodyLength + 4) {
          buffer.reset();
          break;
        }

        byte[] bodyBytes = new byte[bodyLength];
        buffer.get(bodyBytes);

        long recvCRC = buffer.getInt() & 0xFFFFFFFFL;

        CRC32 crc32 = new CRC32();
        crc32.update(bodyBytes);
        long calcCRC = crc32.getValue();

        if (recvCRC != calcCRC) {
          System.out.println("❌ CRC 校验失败");
        } else {
          System.out.println("✅ 收到合法消息 body=" + new String(bodyBytes));
        }
        System.out.println("--------------------------------");
      }
      buffer.compact();
    }
  }
}

```

------

## ✅ 三、客户端（Client.java）

```java
package com.g.co;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

public class Client {

  public static void main(String[] args) throws Exception {
    Socket socket = new Socket("127.0.0.1", 9000);
    OutputStream out = socket.getOutputStream();

    sendMsg(out, "Hello");
    sendMsg(out, "This is a custom protocol");
    sendMsg(out, "Bye Bye");

    socket.close();
    System.out.println("Client sent messages.");
  }

  private static void sendMsg(OutputStream out, String body) throws Exception {

    byte[] bytes = buildPacket(body.getBytes());
    out.write(bytes);
  }

  private static byte[] buildPacket(byte[] body) {
    CRC32 crc32 = new CRC32();
    crc32.update(body);
    int crc = (int) crc32.getValue();

    ByteBuffer buffer = ByteBuffer.allocate(7 + body.length + 4);
    buffer.putShort((short) 0xCAFE); //
    buffer.put((byte) 1); // version
    buffer.putInt(body.length);
    buffer.put(body);
    buffer.putInt(crc);

    return buffer.array();
  }
}

```

------

## ✅ 四、运行效果示例

服务端输出示例：

```
Server listening on port 9000
Client connected!
✅ 收到合法消息 body=Hello
--------------------------------
✅ 收到合法消息 body=This is a custom protocol
--------------------------------
✅ 收到合法消息 body=Bye Bye
```

如果你故意修改 body 或 crc，服务端会显示：

```
❌ CRC 校验失败：recv=123456 calc=789012
```

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
import java.util.regex.Pattern;

/**
 * SocketClient 优化版
 *
 * <p>优化点： 1. 移除了易导致死锁的 Semaphore 机制，实现读写分离。 2. 优化了同步等待响应的逻辑，减少 CPU 轮询。 3. 增强了异常处理和资源释放安全性。 4.
 * 增加了缓冲区最大限制，防止内存溢出。
 */
public class SocketClient implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(SocketClient.class);

  // --- 配置参数 ---
  private static final int DEFAULT_CONNECT_TIMEOUT = 5000;
  private static final int DEFAULT_RECONNECT_ATTEMPTS = 3;
  private static final long DEFAULT_RECONNECT_DELAY = 1000;
  private static final long DEFAULT_HEARTBEAT_INTERVAL = 30000;
  private static final byte[] HEARTBEAT_MESSAGE = "HEARTBEAT\n".getBytes(StandardCharsets.UTF_8);
  private static final int MAX_BUFFER_SIZE = 5 * 1024 * 1024;

  private final String host;
  private final int port;
  private final int connectTimeout;
  private final int reconnectAttempts;
  private final long reconnectInitialDelay;
  private final long heartbeatInterval;

  // --- 核心组件 ---
  private volatile Socket socket;
  private volatile OutputStream outputStream;
  private Thread receiveThread;
  private final AtomicBoolean isRunning = new AtomicBoolean(false);
  private final AtomicBoolean isReconnecting = new AtomicBoolean(false);

  // --- 数据接收缓冲区 ---
  // 使用 StringBuilder 替代 ByteArrayOutputStream，方便正则匹配，但需注意并发
  private final ByteArrayOutputStream bufferStream = new ByteArrayOutputStream();
  private final Object readLock = new Object(); // 用于同步读
  private final Object writeLock = new Object(); // 用于同步写

  // --- 线程池 (用于心跳) ---
  private ScheduledExecutorService heartbeatScheduler;

  public SocketClient(String host, int port) {
    this.host = host;
    this.port = port;
    this.connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    this.reconnectAttempts = DEFAULT_RECONNECT_ATTEMPTS;
    this.reconnectInitialDelay = DEFAULT_RECONNECT_DELAY;
    this.heartbeatInterval = DEFAULT_HEARTBEAT_INTERVAL;
  }

  /** 建立连接 */
  public synchronized boolean connect() {
    if (isConnected()) {
      return true;
    }

    try {
      closeSocket(); // 确保旧连接清理

      socket = new Socket();
      socket.connect(new InetSocketAddress(host, port), connectTimeout);

      // 优化 Socket 参数
      socket.setTcpNoDelay(true); // 禁用 Nagle 算法，提高实时性
      socket.setKeepAlive(true);
      socket.setSoTimeout(0); // 读线程使用阻塞模式，不设置超时，避免 Read 抛错

      outputStream = socket.getOutputStream();

      // 状态标记
      isRunning.set(true);
      isReconnecting.set(false);

      // 启动接收线程
      startReceiveThread();

      // 启动心跳
      startHeartbeat();

      log.info("Successfully connected to {}:{}", host, port);
      return true;
    } catch (IOException e) {
      log.error("Failed to connect to {}:{}", host, port, e);
      return false;
    }
  }

  /** 发送消息并等待匹配正则的响应 (同步阻塞模式) */
  public String sendMessageAndWaitForResponse(
      byte[] message, String regexPattern, long timeoutMillis) {
    if (!isConnected()) {
      if (!reconnect()) return null;
    }

    Pattern pattern;
    try {
      pattern = Pattern.compile(regexPattern);
    } catch (Exception e) {
      log.error("Invalid regex: {}", regexPattern);
      return null;
    }

    // 1. 清理缓冲区 (只清理旧数据，准备接收新响应)
    synchronized (readLock) {
        try {
          String string = bufferStream.toString(StandardCharsets.UTF_8.name());
        System.err.println("丢弃的字节 = " + string);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        bufferStream.reset();
    }

    // 2. 发送消息
    if (!sendInternal(message)) {
      return null;
    }

    // 3. 等待响应
    long startTime = System.currentTimeMillis();
    long maxWait = Math.min(timeoutMillis, 60000); // 硬性限制最大等待60秒

    synchronized (readLock) {
      while (System.currentTimeMillis() - startTime < maxWait) {
        try {
          String currentData = bufferStream.toString(StandardCharsets.UTF_8.name());
          // 检查匹配
          if (pattern.matcher(currentData).find()) {
            return currentData; // 找到匹配，返回
          }

          // 没找到，释放锁并等待接收线程 notify
          readLock.wait(maxWait - (System.currentTimeMillis() - startTime));

        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return null;
        } catch (UnsupportedEncodingException e) {
          log.error("Encoding error", e);
          return null;
        }
      }
    }

    log.warn("Timeout waiting for response pattern: {}", regexPattern);
    return null;
  }

  /** 纯发送消息 (异步) */
  public boolean sendMessage(byte[] message) {
    if (!isConnected() && !reconnect()) {
      return false;
    }
    return sendInternal(message);
  }

  private boolean sendInternal(byte[] message) {
    try {
      if (outputStream != null) {
        synchronized (writeLock) { // 防止多线程写入错乱
          outputStream.write(message);
          outputStream.flush();
        }
        log.debug("Sent {} bytes", message.length);
        return true;
      }
    } catch (IOException e) {
      log.error("Send error", e);
      handleConnectionError();
    }
    return false;
  }

  private void startReceiveThread() {
    if (receiveThread != null && receiveThread.isAlive()) {
      return;
    }
    receiveThread = new Thread(this::receiveLoop, "Socket-Receiver-" + host);
    receiveThread.setDaemon(true);
    receiveThread.start();
  }

  private void receiveLoop() {
    byte[] buffer = new byte[4096];
    try (InputStream in = socket.getInputStream()) {

      while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
        int len = in.read(buffer);
        if (len == -1) {
          log.info("Server closed connection");
          break;
        }
        if (len > 0) {
          synchronized (readLock) {
            // 保护性清理：如果缓冲区太大，强制重置，防止OOM
            if (bufferStream.size() > MAX_BUFFER_SIZE) {
              log.warn("Buffer overflow, resetting...");
              bufferStream.reset();
            }

            bufferStream.write(buffer, 0, len);

            // 唤醒正在等待响应的业务线程
            readLock.notifyAll();
          }

          if (log.isDebugEnabled()) {
            log.debug("Received bytes: {}", len);
          }
        }
      }
    } catch (IOException e) {
      if (isRunning.get()) {
        log.error("Read error: {}", e.getMessage());
        handleConnectionError();
      }
    } finally {
      log.info("Receive thread stopped");
    }
  }

  private void handleConnectionError() {
    if (isReconnecting.compareAndSet(false, true)) {
      new Thread(this::reconnect, "Socket-Reconnect-Thread").start();
    }
  }

  private synchronized boolean reconnect() {
    log.info("Attempting to reconnect...");
    closeSocket(); // 先彻底关闭旧资源

    for (int i = 0; i < reconnectAttempts; i++) {
      if (!isRunning.get()) break; // 如果用户主动关闭，停止重连

      try {
        long delay = reconnectInitialDelay * (long) Math.pow(2, i); // 指数退避
        log.info("Waiting {}ms before reconnect attempt {}/{}", delay, i + 1, reconnectAttempts);
        Thread.sleep(delay);

        if (connect()) {
          isReconnecting.set(false);
          return true;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    log.error("Reconnection failed after {} attempts", reconnectAttempts);
    isReconnecting.set(false);
    return false;
  }

  private void startHeartbeat() {
    stopHeartbeat();
    heartbeatScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "Heartbeat-Worker");
              t.setDaemon(true);
              return t;
            });

    heartbeatScheduler.scheduleAtFixedRate(
        () -> {
          if (isConnected()) {
            try {
              synchronized (writeLock) {
                outputStream.write(HEARTBEAT_MESSAGE);
                outputStream.flush();
              }
              log.debug("Heartbeat sent");
            } catch (IOException e) {
              log.warn("Heartbeat failed, triggering reconnect...");
              handleConnectionError();
            }
          }
        },
        heartbeatInterval,
        heartbeatInterval,
        TimeUnit.MILLISECONDS);
  }

  private void stopHeartbeat() {
    if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
      heartbeatScheduler.shutdownNow();
    }
  }

  private void closeSocket() {
    try {
      if (socket != null) socket.close();
    } catch (IOException ignored) {
    }
  }

  public boolean isConnected() {
    return socket != null
        && socket.isConnected()
        && !socket.isClosed()
        && !socket.isInputShutdown()
        && !socket.isOutputShutdown();
  }

  @Override
  public void close() {
    log.info("Closing client...");
    isRunning.set(false);
    stopHeartbeat();
    closeSocket();
    if (receiveThread != null) {
      receiveThread.interrupt();
    }
  }

  // --- Main Test ---
  public static void main(String[] args) {
    // 使用 try-with-resources 自动关闭
    try (SocketClient client = new SocketClient("localhost", 8080)) {
      if (client.connect()) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Client started. Type 'bye' to exit.");

        while (true) {
          System.out.print("cmd> ");
          String cmd = scanner.nextLine();
          if ("bye".equalsIgnoreCase(cmd)) break;

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
      }
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





