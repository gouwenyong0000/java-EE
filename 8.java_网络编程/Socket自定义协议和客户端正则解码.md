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
import java.net.*;

import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * SocketClient 和 MessageReceiver 是一个完整的客户端通信解决方案，前者负责连接管理和消息发送，后者负责异步接收消息。
 * 设计特点：
 * 使用多线程技术实现异步消息接收。
 * 提供了重连机制和心跳机制，确保连接的稳定性。
 * 日志记录详细且灵活，便于监控和维护。
 */
public class SocketClient {

    private static final Logger log = LoggerFactory.getLogger(SocketClient.class);

    // Socket 客户端相关属性和变量
    private Socket socket;
    private OutputStream out;
    private InputStream in;
    // 接收线程
    private Thread receiveThread;
    // 接受线程运行状态
    private final AtomicBoolean running = new AtomicBoolean(false);

    // host 和 port
    private final String host;
    private final int port;

    // 用于存储接收到的数据
    private final ByteArrayOutputStream receivedData = new ByteArrayOutputStream();
    private int readTimeOut;

    /**
     * 构造函数，初始化客户端连接信息。
     *
     * @param host 服务器主机地址
     * @param port 服务器端口号
     */
    public SocketClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * 主方法，用于启动客户端并发送消息。
     *
     * @param args 命令行参数（未使用）
     */
    public static void main(String[] args) {
        SocketClient client = new SocketClient("localhost", 8080);
        if (client.connect()) {
            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.println("Enter message: ");
                String message = scanner.nextLine();
                client.readTimeOut = 300000;
                if (message.equals("bye")) {
                    break;
                }
                String response = client.sendMessageAndWaitForResponse(message.getBytes(), "gwy", 50000);
                System.out.println("Received response: " + response);
            }
            client.disconnect();
        } else {
            log.error("connect failed");
        }
    }

    /**
     * 连接到服务器。
     *
     * @return 如果连接成功返回true，否则返回false
     */
    public boolean connect() {
        try {
            //  socket
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000); // 5秒超时

            // 设置socket选项
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(readTimeOut); // 0表示无限等待，可以设置其他值以设置超时时间

            out = socket.getOutputStream();
            in = socket.getInputStream();

            startReceiveThread();// 启动接收线程

            log.info("Successfully connected to server at {}:{}", host, port); // 新增：记录成功连接的日志
            return true;
        } catch (ConnectException e) {
            log.warn("Connection refused: The server is not available or the port is incorrect. Host: {}, Port: {}", host, port); // 修改：使用 warn 级别并添加上下文信息
        } catch (SocketTimeoutException e) {
            log.error("Connection timed out: The server did not respond within the timeout period. Host: {}, Port: {}", host, port);
        } catch (UnknownHostException e) {
            log.error("Unknown host: Unable to resolve hostname '{}'. Error: {}", host, e.getMessage());
        } catch (SecurityException e) {
            log.error("Security error: Permission denied when connecting to host '{}'. Error: {}", host, e.getMessage());
        } catch (IOException e) {
            log.error("An unexpected I/O error occurred while connecting to server at {}:{}. Error: {}", host, port, e.getMessage()); // 修改：增强日志内容
        }
        return false;
    }

    /**
     * 启动接收线程，用于异步接收服务器消息。
     * 如果接收线程已经存在且正在运行，则不会重复启动。
     */
    private void startReceiveThread() {
        log.debug("Attempting to start receive thread.");
        running.set(true);
        MessageReceiver messageReceiver = new MessageReceiver(socket, in, running, receivedData, readTimeOut);
        receiveThread = new Thread(messageReceiver);
        receiveThread.start();

        log.info("Receive thread started successfully.");
    }


    /**
     * 发送消息到服务器。
     *
     * @param message 要发送的消息字节数组
     * @return 如果消息发送成功返回true，否则返回false
     */
    public boolean sendMessage(byte[] message) {
        // 连接状态检查 条件判断逻辑错误，因运算符优先级导致
        if ((socket == null || !isConnected()) && !reconnect()) {
            log.error("Failed to connect to the server, cannot send message. Host: {}, Port: {}", host, port);
            return false; // 连接失败，返回false
        }

        // 检查接收线程是否存活
        if (receiveThread == null || !receiveThread.isAlive()) {
            log.warn("Receive thread is not alive, attempting to restart...");
            startReceiveThread();
            if (receiveThread == null || !receiveThread.isAlive()) {
                log.error("Failed to restart receive thread, cannot send message.");
                return false; // 接收线程重启失败，返回false
            }
        }

        // 清空之前接收的数据
        synchronized (receivedData) {
            String dataToClear = receivedData.toString();
            log.debug("Clearing received data before sending new message. Host: {}, Port: {}, Data to clear: {}", host, port, dataToClear); // 增加清空的具体内容
            receivedData.reset();
        }

        try {
            // 发送消息
            out.write(message);
            out.flush();
            log.debug("Message sent successfully. Host: {}, Port: {}, Message: {}", host, port, new String(message)); // 记录发送成功的消息内容
            return true; // 消息发送成功，返回true
        } catch (IOException e) {
            log.error("Error occurred while sending message to server at {}:{}. Error: {}", host, port, e.getMessage());
            // 增加重试逻辑
            if (reconnect()) {
                try {
                    out.write(message);
                    out.flush();
                    log.debug("Message sent successfully after retry. Host: {}, Port: {}, Message: {}", host, port, new String(message)); // 记录重试后发送成功的消息内容
                    return true; // 重试后消息发送成功，返回true
                } catch (IOException retryException) {
                    log.error("Retry failed: The server did not accept the message after reconnection. Host: {}, Port: {}. Error: {}", host, port, retryException.getMessage());
                }
            }
            return false; // 消息发送失败，返回false
        }
    }

    /**
     * 发送消息并等待服务器响应。
     *
     * @param message       要发送的消息字节数组
     * @param regexPattern  响应匹配的正则表达式
     * @param timeoutMillis 超时时间（毫秒）
     * @return 匹配的响应字符串，超时或失败时返回null
     */
    public String sendMessageAndWaitForResponse(byte[] message, String regexPattern, long timeoutMillis) {
        // 调用 sendMessage 方法发送消息
        boolean sendMessageResult = sendMessage(message);
        if (!sendMessageResult) {
            log.error("Message sending failed, cannot wait for response.");
            return null; // 如果消息发送失败，直接返回null
        }

        // 设置合理的最大等待时间限制
        long maxWaitTime = Math.min(timeoutMillis, 300000); // 最大限制5分钟
        try {
            socket.setSoTimeout((int) maxWaitTime); // 设置为实际等待时间
        } catch (SocketException e) {
            log.error("Failed to set socket timeout: {}", e.getMessage());
            return null;
        }

        // 等待响应
        long startTime = System.currentTimeMillis();
        Pattern pattern = Pattern.compile(regexPattern);

        synchronized (receivedData) {
            while (System.currentTimeMillis() - startTime < maxWaitTime) {
                try {
                    String currentData = receivedData.toString("UTF-8");
                    if (pattern.matcher(currentData).find()) {
                        return currentData;
                    }

                    receivedData.wait(100); // 避免长时间阻塞
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Waiting interrupted while waiting for response", e);
                    return "等待中断";
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException("UTF-8 encoding not supported", e);
                }
            }
        }

        log.error("Timeout waiting for response matching the pattern: {}. Elapsed: {}ms", regexPattern, (System.currentTimeMillis() - startTime));
        return null;
    }

    /**
     * 尝试重新连接到服务器。
     *
     * @return 如果重新连接成功返回true，否则返回false
     */
    private boolean reconnect() {
        int attempts = 0;
        int totalTimes = 3;
        long initialDelay = 1000; // 初始延迟1秒
        while (attempts < totalTimes) {
            try {
                long delay = (long) Math.pow(2, attempts) * initialDelay; // 指数退避
                Thread.sleep(delay);
                disconnect();
                log.info("Attempting to reconnect {}/{}", attempts + 1, totalTimes);
                if (connect()) {
                    return true;
                }
                attempts++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted during reconnection attempt {}/{}", attempts + 1, totalTimes, e);
                return false;
            }
        }
        log.error("Failed to reconnect after {} attempts.", totalTimes);
        return false;
    }

    /**
     * 检查当前连接是否有效。
     *
     * @return 如果连接有效返回true，否则返回false
     */
    public boolean isConnected() {
        return socket != null
                && !socket.isClosed()
                && socket.isConnected()
                && !socket.isInputShutdown(); // 移除outputShutdown检查，允许单向通信
    }

    /**
     * 断开与服务器的连接。
     * 该方法会确保所有资源被正确关闭，并记录每一步的操作日志。
     */
    public void disconnect() {
        log.info("Attempting to disconnect from server at {}:{}", host, port);
        running.set(false);

        try {
            if (receiveThread != null) {
                log.debug("Interrupting receive thread...");
                receiveThread.interrupt();
                receiveThread.join(5000);
                if (receiveThread.isAlive()) {
                    log.warn("Receive thread did not terminate within the timeout period.");
                } else {
                    log.debug("Receive thread terminated successfully.");
                }
            }
        } catch (InterruptedException e) {
            log.error("Thread interruption occurred while closing resources: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }

        // 显式关闭所有资源，确保正确的关闭顺序
        try {
            if (out != null) {
                out.close();
                log.debug("OutputStream closed successfully");
            }
        } catch (IOException e) {
            log.error("Error closing OutputStream: {}", e.getMessage());
        }

        try {
            if (in != null) {
                in.close();
                log.debug("InputStream closed successfully");
            }
        } catch (IOException e) {
            log.error("Error closing InputStream: {}", e.getMessage());
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                log.debug("Socket closed successfully");
            }
        } catch (IOException e) {
            log.error("Error closing Socket: {}", e.getMessage());
        }

        log.info("Disconnected from server at {}:{}", host, port);
    }

    /**
     * 发送心跳消息以保持连接活跃。
     * 如果心跳发送失败，会尝试重新连接。
     */
    private void sendHeartbeat() {
        log.debug("Attempting to send heartbeat."); // 新增：记录心跳发送的尝试
        if (socket == null || !isConnected()) {
            log.warn("Socket is not connected, cannot send heartbeat.");
            reconnect();
            return;
        }
        try {
            out.write("HEARTBEAT".getBytes());
            out.flush();
            log.debug("Heartbeat sent successfully.");
        } catch (IOException e) {
            log.error("Heartbeat failed: {}", e.getMessage());
            reconnect();
        }
    }
}


```

## MessageReceiver.java

```java
package socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 消息接收器，用于异步接收服务器消息。
 */
class MessageReceiver implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(MessageReceiver.class);

    private Socket socket;
    private InputStream in;
    private AtomicBoolean running;
    private ByteArrayOutputStream receivedData;
    private long timeoutMillis;


    /**
     * 构造函数，初始化接收器。
     *
     * @param socket        套接字连接
     * @param in            输入流
     * @param running       运行状态标志
     * @param receivedData  接收到的数据缓冲区
     * @param timeoutMillis 等待数据的最大超时时间（毫秒）
     */
    public MessageReceiver(Socket socket, InputStream in, AtomicBoolean running, ByteArrayOutputStream receivedData, long timeoutMillis) {
        this.socket = socket;
        this.in = in;
        this.running = running;
        this.receivedData = receivedData;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * 接收线程的运行逻辑，持续读取消息并存储到缓冲区。
     */
    @Override
    public void run() {
        log.debug("MessageReceiver thread started.");
        socket.setPerformancePreferences(0, 2, 1); // 优化网络性能参数

        byte[] buffer = new byte[1024];
        try {
            // 初始化时验证连接有效性
            if (!validateSocketState()) {
                return;
            }
            socket.setSoTimeout((int) timeoutMillis);//设置超时时间，如果读取数据超时，则抛出

            while (running.get()) {

                // 实时校验socket状态
                if (!validateSocketState()) {
                    break;
                }

                int bytesRead = in.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {

                    // 同步写入数据
                    synchronized (receivedData) {
                        receivedData.write(buffer, 0, bytesRead);
                        receivedData.notifyAll();
                    }

                    // 打印 dump 十六进制 和 ascii
                    String hexDump = hexDump(buffer, bytesRead);
                    String asciiDump = asciiDump(buffer, bytesRead);
                    log.debug("Received {} bytes of data. Hex Dump: {}", bytesRead, hexDump);
                    log.debug("Received {} bytes of data. ASCII Dump: {}", bytesRead, asciiDump);

                } else if (bytesRead == -1) {
                    log.info("End of stream reached. Connection might be closed by server.");
                    break;
                }

                // 动态调整休眠时间
                Thread.sleep(Math.min(100, timeoutMillis / 10));
            }
        } catch (SocketTimeoutException e) {
            log.warn("Socket read timed out after {}ms: {}", timeoutMillis, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interruption occurred: {}", e.getMessage());
        } catch (IOException e) {
            log.error("Receive error: {}", e.getMessage());
        } finally {
            log.debug("MessageReceiver thread exiting.");
            // 确保资源释放
            running.set(false);
            closeQuietly(in);
            closeQuietly(socket);
        }
    }

    private boolean validateSocketState() throws IOException {
        if (socket == null || socket.isClosed() || !socket.isConnected() || socket.isInputShutdown()) {
            log.warn("Invalid socket state detected. Socket: {}, Connected: {}, InputShutdown: {}",
                    socket != null ? "Active" : "Null",
                    socket != null && socket.isConnected(),
                    socket != null && socket.isInputShutdown());
            return false;
        }

        if (in == null) {
            log.warn("InputStream is null, attempting to reinitialize...");
            in = socket.getInputStream();
            if (in == null) {
                log.error("Failed to reinitialize InputStream");
                return false;
            }
        }

        return true;
    }

    /**
     * 生成十六进制表示形式的字符串。
     *
     * @param buffer 字节数组
     * @param length 实际读取的字节数
     * @return 十六进制表示形式的字符串
     */
    private String hexDump(byte[] buffer, int length) {
        StringBuilder hexDump = new StringBuilder();
        for (int i = 0; i < length; i++) {
            hexDump.append(String.format("%02X ", buffer[i]));
        }
        return hexDump.toString().trim();
    }

    /**
     * 生成 ASCII 表示形式的字符串。
     *
     * @param buffer 字节数组
     * @param length 实际读取的字节数
     * @return ASCII 表示形式的字符串
     */
    private String asciiDump(byte[] buffer, int length) {
        StringBuilder asciiDump = new StringBuilder();
        for (int i = 0; i < length; i++) {
            asciiDump.append(Character.isISOControl(buffer[i]) ? "." : (char) buffer[i]);
        }
        return asciiDump.toString();
    }

    /**
     * 安静地关闭资源，忽略关闭过程中抛出的异常。
     *
     * @param closeable 可关闭的对象
     */
    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
                log.debug("Closed resource successfully: {}", closeable.getClass().getSimpleName());
            } catch (Exception e) {
                log.warn("Failed to close resource: {}", e.getMessage());
            }
        }
    }
}
```

