# instrument-client

这是一个基于 Java 17 的 TCP 仪表客户端示例项目，主要用于演示如何通过 Socket 与远程设备进行命令发送、响应接收、超时处理和协议解析。

## 项目定位

该工程适合以下场景：

- 与仪器或串口网关设备建立 TCP 连接
- 发送 SCPI / ASCII / 二进制协议命令
- 读取设备响应并做响应匹配
- 处理断线重连、超时、协议拆包与错误恢复
- 在业务层中以同步调用或异步监听方式消费数据

## 模块说明

- api
  - `ClientITF`：客户端核心接口
  - `DataListener`：异步事件监听器
  - `BlockingDataListener`：阻塞式监听器
  - `ResponseMatcher`：响应匹配器
- engine
  - `SocketClientITFImpl`：实现网络连接与请求处理的核心类
  - `SocketClientConfig`：连接参数与重连策略配置
  - `InstrumentClientException`：运行时异常封装
- protocol
  - `Protocol`：协议接口
  - `LineProtocol`：CRLF 文本协议实现
  - `LengthFieldProtocol`：长度字段 + 校验和协议实现
- model
  - `Command`：待发送命令
  - `Response`：协议解码后的响应对象
- Demo
  - `Demo`：示例程序
  - `TestServer`：本地测试服务端

## 设计特点

1. 线程隔离
   - 后台接收线程持续读取 socket，不阻塞业务线程。
2. 单请求同步
   - `sendAndMatch()` 使用 `Semaphore` 保证同一时刻只有一个请求处于待响应状态。
3. 响应匹配
   - 通过 `ResponseMatcher` 判断当前响应是否是这次请求返回。
4. 自动重连
   - 连接失败时会根据配置尝试重连，写失败也支持重试。
5. 双种监听方式
   - `DataListener` 适合异步回调
   - `BlockingDataListener` 适合队列消费
6. 协议抽象
   - `Protocol` 把文本协议和二进制协议统一抽象出来。

## 快速开始

### 1. 编译项目

```bash
cd instrument-client
mvn -q -DskipTests compile
```

### 2. 启动测试服务端

```bash
cd instrument-client
java -cp target/classes com.example.instrument.TestServer
```

启动后服务端会监听：

- host: `127.0.0.1`
- port: `5025`

支持的测试命令示例：

- `MEAS:VOLT?` -> `VOLT:1.234\r\n`
- `PING` -> `PONG\r\n`
- `STATUS?` -> `READY\r\n`
- 其他命令 -> `OK\r\n`

### 3. 运行示例客户端

新开一个终端：

```bash
cd instrument-client
java -cp target/classes com.example.instrument.Demo
```

示例会创建一个 `SocketClientITFImpl`，连接到本地服务端，并发送：

```java
client.sendAndRegex("MEAS:VOLT?", "VOLT:[0-9.]+\\r\\n", Duration.ofSeconds(3));
```

如果服务端正常响应，控制台中会打印匹配到的结果。

## 典型使用方式

```java
var client =
    new SocketClientITFImpl(
        SocketClientConfig.defaults("127.0.0.1", 5025),
        new LineProtocol());

client.init();
client.connect();

client.addListener(r -> System.out.println("RX: " + r.text()));

Response response =
    client.sendAndRegex("MEAS:VOLT?", "VOLT:[0-9.]+\\r\\n", Duration.ofSeconds(3));

System.out.println(response.text());
client.disconnect();
```

## 协议说明

### 1. LineProtocol

`LineProtocol` 适合文本协议，使用 CRLF 作为帧结束符。

例如：

```text
MEAS:VOLT?\r\nVOLT:1.234\r\n
```

它会在接收时按 `\r\n` 分帧，并保留未结束的半包，等待后续数据补齐。

### 2. LengthFieldProtocol

`LengthFieldProtocol` 适合二进制协议，帧结构示例：

```text
[0xAA][LEN_H][LEN_L][PAYLOAD...][CRC8]
```

- `0xAA`：起始标志
- `LEN`：有效载荷长度
- `CRC8`：校验和

它通过长度字段和校验和实现帧边界识别，适合更偏底层设备通信。

## 重要注意事项

### 1. 不要用 available() 判断包边界

`InputStream.available()` 只能表示“当前缓存中已有多少字节”，并不能保证一条完整协议帧已到达。完整帧边界应该由 `Protocol.decode()` 负责判断。

### 2. 发送线程不直接决定读取时机

本实现中，读取链路由后台 receiver thread 持续负责，`sendAndMatch()` 只负责等待匹配结果和串行化请求。这样可以避免因“读/写时机绑定”导致的死锁、丢数据和超时异常。

### 3. 重连和写重试的策略

默认情况下，写失败会尝试重连后再发送一次。生产环境中建议根据实际命令是否幂等来决定是否打开重试逻辑。

## 目录结构

```text
instrument-client/
├── pom.xml
├── README.md
├── src/
│   └── main/
│       └── java/
│           └── com/
│               └── example/
│                   ├── Demo.java
│                   ├── TestServer.java
│                   ├── api/
│                   ├── engine/
│                   ├── model/
│                   └── protocol/
└── target/
```

## 常见问题

### 连接被拒绝

- 确认 `TestServer` 已启动
- 确认端口 `5025` 没有被其他进程占用
- 检查 `SocketClientConfig.defaults()` 中的 host/port 是否匹配

### 响应超时

- 确认服务端返回格式和协议一致
- 检查命令字符串是否正确
- 确认 `ResponseMatcher` 与实际响应内容匹配

### 乱码或解析失败

- 检查协议的字符集是否正确
- 确认 CRLF 或长度字段格式与远端设备一致
- 观察日志中的原始响应内容，确认是否出现半包或粘包

## 结语

这个工程既适合作为学习 TCP 客户端实现的样例，也可以作为接入真实仪器设备时的基础框架。你可以直接基于此实现扩展更具体的设备驱动，例如电源、示波器、温控器等。
