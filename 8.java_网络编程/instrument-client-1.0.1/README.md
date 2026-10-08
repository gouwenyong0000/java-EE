先梳理项目结构与核心链路，再重点检查并发、资源、安全、网络异常/半包、超时重连、协议匹配和可维护性，
架构、并发、网络、资源、安全、协议、重试、可维护性、测试几个维度进行了汇总

# Instrument Client

一个面向**实验室仪器、测试设备、工控设备、SCPI/TCP 自定义协议**的 Java 17 通信客户端框架。

本框架按照长期维护目标重新划分职责，提供清晰的架构、可靠的网络通信能力和完善的测试覆盖。

## 项目功能

- **TCP 连接管理**：连接建立/断开、状态追踪、自动重连（带指数退避 + 抖动）
- **协议编解码**：支持行协议（CRLF/LF）和长度字段二进制协议，可扩展自定义协议
- **请求/响应模型**：同步请求（阻塞等待）、异步请求（CompletableFuture）、无应答发送（fire-and-forget）
- **响应匹配**：精确匹配、子串匹配、正则匹配、组合器（and/or/negate）
- **事件监听**：连接状态监听器、数据监听器、阻塞式数据监听器
- **拦截器链**：请求/响应生命周期钩子，支持日志、监控、故障注入
- **命令幂等性**：明确区分 IDEMPOTENT / NON_IDEMPOTENT / UNKNOWN，避免自动重发导致重复执行
- **异步队列**：未匹配响应独立队列，支持 DROP_OLDEST / BLOCK / DROP_NEWEST 溢出策略
- **指标收集**：请求计数、成功/失败/超时/重试统计、延迟指标
- **诊断框架**：连接状态快照、请求队列状态、指标汇总

## 目录结构

```
instrument-client-1.0.1/
├── src/
│   ├── main/java/com/example/instrument/
│   │   ├── api/                          # 公共 API 接口
│   │   │   ├── InstrumentClient          # 核心客户端接口
│   │   │   ├── ClientInterceptor         # 请求拦截器接口
│   │   │   ├── ConnectionListener        # 连接状态监听器
│   │   │   ├── DataListener              # 数据监听器
│   │   │   ├── BlockingDataListener      # 阻塞式数据监听器
│   │   │   └── ResponseMatcher           # 响应匹配器接口
│   │   │
│   │   ├── model/                        # 数据模型
│   │   │   ├── Command                   # 命令（发送负载）
│   │   │   ├── CommandIdempotency        # 命令幂等性枚举
│   │   │   ├── Request                   # 请求（内部封装）
│   │   │   └── Response                  # 响应（接收负载）
│   │   │
│   │   ├── protocol/                     # 协议编解码
│   │   │   ├── Protocol                  # 协议接口（Encoder + Decoder 工厂）
│   │   │   ├── ProtocolEncoder           # 编码器接口
│   │   │   ├── ProtocolDecoder           # 解码器接口
│   │   │   ├── LineProtocol              # 行协议实现（CRLF/LF 分隔）
│   │   │   └── LengthFieldProtocol       # 长度字段二进制协议
│   │   │
│   │   ├── config/                       # 配置类
│   │   │   ├── ClientConfig              # 客户端配置（Builder 模式）
│   │   │   └── ReconnectConfig           # 重连配置（退避 + 抖动）
│   │   │
│   │   ├── network/                      # 网络层
│   │   │   ├── Connection                # 连接接口
│   │   │   ├── TcpConnection             # TCP 连接实现
│   │   │   ├── ConnectionManager         # 多连接管理器
│   │   │   ├── ConnectionState           # 连接状态枚举
│   │   │   └── ReconnectPolicy           # 重连策略实现
│   │   │
│   │   ├── engine/                       # 核心引擎
│   │   │   ├── InstrumentClientImpl      # 客户端核心实现
│   │   │   ├── RequestManager            # 请求管理器（PendingRequest 生命周期）
│   │   │   ├── PendingRequest            # 待处理请求（CompletableFuture 包装）
│   │   │   ├── Receiver                  # 数据接收器（持续读取 TCP 流）
│   │   │   └── ResponseDispatcher        # 响应分发器（匹配请求 vs 异步数据）
│   │   │
│   │   ├── factory/                      # 工厂类
│   │   │   └── InstrumentClients         # 客户端构建工厂
│   │   │
│   │   ├── example/                      # 示例代码
│   │   │   └── ClientExample             # 最小使用示例
│   │   │
│   │   ├── exception/                    # 异常体系
│   │   │   ├── InstrumentException       # 基础异常
│   │   │   ├── ConnectionException       # 连接异常
│   │   │   ├── RequestTimeoutException   # 请求超时异常
│   │   │   ├── RequestCancelledException # 请求取消异常
│   │   │   ├── ProtocolException         # 协议解析异常
│   │   │   └── ConfigurationException    # 配置异常
│   │   │
│   │   ├── metrics/                      # 指标收集
│   │   │   └── ClientMetrics             # 客户端指标（线程安全计数器）
│   │   │
│   │   └── diagnostics/                  # 诊断工具
│   │       └── ClientDiagnostics         # 诊断报告生成器
│   │
│   └── test/java/com/example/instrument/
│       ├── api/                          # API 接口测试
│       ├── config/                       # 配置测试
│       ├── model/                        # 模型测试
│       ├── protocol/                     # 协议测试
│       ├── network/                      # 网络层测试
│       ├── engine/                       # 引擎测试
│       ├── metrics/                      # 指标测试
│       ├── testing/                      # 测试工具 + 专项测试
│       │   ├── InstrumentationServer     # 模拟仪器服务器
│       │   ├── MockConnection            # 模拟网络连接
│       │   ├── NetworkFluctuationTest    # 网络波动测试
│       │   ├── RetryMechanismTest        # 重试机制测试
│       │   ├── ProtocolBoundaryTest      # 协议边界测试（半包/粘包）
│       │   └── ConcurrencyStressTest     # 并发压力测试
│       ├── ComprehensiveInstrumentClientTest  # 端到端集成测试
│       ├── InstrumentClientIntegrationTest    # API 表面测试
│       └── RobustnessExtensibilityTest        # 健壮性 + 可扩展性测试
│
├── pom.xml
└── README.md
```

## 设计原则

### 1. 单一职责原则（SRP）

每个类只负责一个明确的职责：

- **TcpConnection**：只负责 TCP Socket 的 connect/read/write/close
- **Receiver**：只负责持续读取 TCP 字节流并交给 Decoder
- **Protocol/Decoder/Encoder**：只负责字节流 ↔ 帧的转换
- **RequestManager**：只维护 PendingRequest 的注册/完成/超时
- **ResponseDispatcher**：只负责区分"当前请求响应"和"异步数据"
- **ReconnectPolicy**：只负责连接恢复策略，不负责业务重试语义

### 2. 分层架构

```
                    +-----------------------------+
                    |       InstrumentClient      |
                    |  API / 生命周期 / 重试边界   |
                    +-------------+---------------+
                                  |
              +-------------------+-------------------+
              |                   |                   |
       +------v------+      +-----v------+      +------v------+
       | Request     |      |  Response  |      | Connection  |
       | Manager     |      | Dispatcher |      | Manager     |
       +------+------+      +-----+------+      +------+------+
              |                   |                     |
       CompletableFuture          |               TcpConnection
              |                   |                     |
              +-------------------+---------------------+
                                  |
                           +------v------+
                           |   Receiver  |
                           +------+------+
                                  |
                              TCP bytes
                                  |
                           +------v------+
                           |   Decoder   |
                           +-------------+
```

### 3. 不使用"清空旧响应"解决匹配问题

旧方案（已废弃）：
```
send -> clearBuffers -> read -> match
```

新方案：
```
register PendingRequest
        ↓
send command
        ↓
Receiver continuously reads
        ↓
Decoder emits Response
        ↓
RequestManager.tryComplete(response)
        ↓
matched -> CompletableFuture.complete()
not matched -> async queue / DataListener
```

这样不会因为 `clearBuffers()` 把合法异步消息误删。

### 4. 自动重发不是天然安全

例如发送 `START` 命令后网络异常，客户端无法知道仪器到底有没有收到。
如果直接 reconnect + resend，可能导致仪器重复执行：

```
START
START  ← 重复执行！
```

因此引入**命令幂等性分类**：

| 幂等性 | 行为 | 适用场景 |
|--------|------|----------|
| `IDEMPOTENT` | 允许按策略重试 | 查询类命令（`*IDN?`, `MEAS:VOLT?`） |
| `NON_IDEMPOTENT` | 连接异常后直接失败 | 执行类命令（`START`, `STOP`, `TRIGGER`） |
| `UNKNOWN` | 默认不重试 | 不确定副作用的命令 |

### 5. TCP 是字节流，不是消息

`read()` 一次可能得到：
- 半个包（半包）
- 一个完整包
- 多个连续包（粘包）

所以协议 Decoder 必须是**有状态的流解析器**，不能假设每次 read 返回完整帧。

### 6. 异常隔离

DataListener 中抛出的异常不会中断接收线程，只记录日志。确保用户代码的 bug 不会导致整个客户端崩溃。

## 常见网络编程范式

### 1. BIO（Blocking I/O）— 本项目采用

**模型**：每个连接独占一个线程，读写操作阻塞直到完成。

```
Thread-1 ──→ Socket.read() ──→ [阻塞等待数据] ──→ 处理响应
Thread-2 ──→ Socket.read() ──→ [阻塞等待数据] ──→ 处理响应
```

**特点**：
- 编程简单，逻辑直观
- 线程数 = 连接数，连接多时线程开销大
- 适合连接数少、长连接的场景

**本项目的应用**：
- `TcpConnection` 使用 `Socket.getInputStream()/getOutputStream()` 进行阻塞读写
- `Receiver` 在独立线程中循环调用 `connection.read(buffer)`，阻塞等待数据
- 适合仪器通信场景（通常只有 1~N 个连接，N < 100）

### 2. NIO（Non-blocking I/O / Selector 模型）

**模型**：使用 `Selector` 多路复用多个 Channel 的 I/O 事件，单线程或少量线程即可管理大量连接。

```
Thread-1 ──→ Selector.select() ──→ [有事件的 Channel] ──→ 处理读写
              ↑                        ↓
              └──────── 循环等待 ──────┘
```

**特点**：
- 单线程可管理成千上万连接
- 编程复杂，需要手动处理状态机
- 适合高并发、短连接的场景（如 Web 服务器）

**Java API**：`java.nio.channels.SocketChannel`、`Selector`、`SelectionKey`

### 3. AIO（Asynchronous I/O / 异步 I/O）

**模型**：发起 I/O 操作后立即返回，操作系统完成后通过回调通知应用。

```
Thread-1 ──→ channel.read(buffer, callback) ──→ 继续做其他事
                                                ↓
                              OS 完成读取后回调 callback.handleResult()
```

**特点**：
- 真正的异步，不阻塞任何线程
- Windows 下基于 IOCP 表现良好，Linux 下底层支持不如 epoll
- 编程模型复杂，回调嵌套深

**Java API**：`java.nio.channels.AsynchronousSocketChannel`

### 4. Reactor 模式

**模型**：事件驱动架构，将 I/O 多路复用和事件处理分离。

```
                    ┌─────────────┐
                    │   Reactor   │ ←─ 监听 I/O 事件
                    │  (Selector) │
                    └──────┬──────┘
                           │ 分发事件
              ┌────────────┼────────────┐
              ↓            ↓            ↓
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │ Handler  │ │ Handler  │ │ Handler  │
        │  (读)    │ │  (写)    │ │  (连接)  │
        └──────────┘ └──────────┘ └──────────┘
```

**变体**：
- **单 Reactor 单线程**：简单但有性能瓶颈
- **单 Reactor 多线程**：事件分发和业务处理分离
- **主从 Reactor**：Netty 采用的模式，主 Reactor 处理连接，从 Reactor 处理读写

**代表框架**：Netty、Mina、Redis（单线程 Reactor）

### 5. Proactor 模式

**模型**：异步操作完成后，结果已经就绪，直接交给处理器。

```
Thread-1 ──→ Proactor.initiateRead() ──→ OS 异步读取
                                              ↓
                              OS 完成后将数据放入 buffer
                                              ↓
                              Proactor 回调 Handler.handle(buffer)
```

**与 Reactor 的区别**：
- Reactor：通知"可以读了"，需要应用自己读
- Proactor：通知"已经读完了"，数据已就绪

**代表实现**：Windows IOCP、Boost.Asio

### 6. 半同步/半异步模式（Half-Sync/Half-Async）

**模型**：异步层处理 I/O 事件，同步层通过线程池处理业务逻辑。

```
异步层（I/O 线程）──→ 收到数据 ──→ 放入队列
                                      ↓
同步层（工作线程池）──→ 从队列取数据 ──→ 业务处理
```

**特点**：
- I/O 线程轻量，只做数据收发
- 业务逻辑在独立线程池执行，不阻塞 I/O
- ACE 框架的经典模式

### 本项目为何选择 BIO？

| 因素 | BIO | NIO | Netty |
|------|-----|-----|-------|
| 连接数 | 少（< 100） | 多（> 10000） | 多 |
| 编程复杂度 | 低 | 高 | 中 |
| 依赖 | 无 | JDK 内置 | 第三方库 |
| 调试难度 | 低 | 高 | 中 |
| 适用场景 | 仪器通信 | Web 服务器 | 通用高并发 |

仪器通信场景的典型特征：
1. **连接数少**：通常只连接 1~几台仪器
2. **请求-响应模式**：发一条命令，等一个响应
3. **长连接**：连接建立后长时间保持
4. **低并发**：不需要同时处理大量请求
5. **可维护性优先**：代码简单直观比极致性能更重要

因此 BIO 是最合适的选择。如果未来需要支持大规模连接，可以平滑迁移到 Netty。

## 使用原则

### 快速开始

```java
Protocol protocol = LineProtocol.crlf(StandardCharsets.UTF_8);

ClientConfig config = ClientConfig.builder()
        .connectTimeout(Duration.ofSeconds(5))
        .responseTimeout(Duration.ofSeconds(10))
        .reconnect(ReconnectConfig.defaults())
        .build();

InstrumentClient client = InstrumentClients.tcp(
        new InetSocketAddress("127.0.0.1", 5025),
        protocol,
        config
);

// 监听异步数据
client.addDataListener(response ->
        System.out.println("ASYNC: " + response.text())
);

client.connect();

// 同步请求
Response response = client.request(
        Command.text("*IDN?", StandardCharsets.UTF_8),
        ResponseMatcher.contains(""),
        Duration.ofSeconds(3),
        CommandIdempotency.IDEMPOTENT
);

System.out.println(response.text());

client.close();
```

### SCPI 仪器查询

```java
Response voltage = client.request(
        Command.text("MEAS:VOLT:DC?", StandardCharsets.UTF_8),
        ResponseMatcher.regex("[-+]?\\d+(\\.\\d+)?"),
        Duration.ofSeconds(2),
        CommandIdempotency.IDEMPOTENT
);
```

### 自定义二进制协议

默认长度字段协议格式：

```
+------+--------+----------------+----------+
| 0xAA | LEN(2) | PAYLOAD(LEN)   | XOR(1)   |
+------+--------+----------------+----------+
```

XOR 计算范围：`STX + LEN + PAYLOAD`。

如果你的真实协议使用 CRC-8/CRC-16，请实现对应的 `ProtocolEncoder/ProtocolDecoder`，不要把 XOR 称为 CRC。

### 多连接管理

```java
ConnectionManager manager = new ConnectionManager();

UUID id = manager.add(
        new InetSocketAddress("192.168.1.10", 5025),
        LineProtocol.crlf(StandardCharsets.UTF_8),
        config
);

manager.get(id).connect();
```

每个 client 都拥有自己的 decoder，因此不同连接之间不会共享协议解析状态。

### 异步请求

```java
CompletableFuture<Response> future = client.requestAsync(
        Command.text("*IDN?", StandardCharsets.UTF_8),
        ResponseMatcher.any(),
        Duration.ofSeconds(3),
        CommandIdempotency.IDEMPOTENT
);

future.thenAccept(response -> System.out.println(response.text()));
```

### 阻塞式数据监听器

```java
BlockingDataListener blocking = client.blockingDataListener();
client.addDataListener(blocking);

// 阻塞直到有数据到达
Response response = blocking.take();
// 或带超时
Response response = blocking.poll(Duration.ofSeconds(5));
```

### 拦截器

```java
client.addInterceptor(new ClientInterceptor() {
    @Override
    public void beforeRequest(Command command, CommandIdempotency idempotency) {
        System.out.println("Sending: " + command.text());
    }

    @Override
    public void afterResponse(Command command, Response response, Duration latency) {
        System.out.println("Received: " + response.text() + " in " + latency.toMillis() + "ms");
    }

    @Override
    public void afterFailure(Command command, CommandIdempotency idempotency, Throwable cause) {
        System.err.println("Failed: " + cause.getMessage());
    }
});
```

## 使用注意事项

### 1. 给每种仪器单独实现业务 Adapter

不要让业务代码直接拼协议。应该为每种仪器实现一个 Adapter 类，封装协议细节：

```java
public class PowerSupplyAdapter {
    private final InstrumentClient client;

    public PowerSupplyAdapter(InstrumentClient client) {
        this.client = client;
    }

    public double measureVoltage() throws Exception {
        Response response = client.request(
                Command.text("MEAS:VOLT:DC?", StandardCharsets.UTF_8),
                ResponseMatcher.regex("[-+]?\\d+(\\.\\d+)?"),
                Duration.ofSeconds(2),
                CommandIdempotency.IDEMPOTENT
        );
        return Double.parseDouble(response.text().trim());
    }

    public void setVoltage(double volts) throws Exception {
        client.request(
                Command.text("SOUR:VOLT " + volts, StandardCharsets.UTF_8),
                ResponseMatcher.equalsText("OK"),
                Duration.ofSeconds(2),
                CommandIdempotency.NON_IDEMPOTENT
        );
    }
}
```

### 2. 正确设置命令幂等性

| 命令类型 | 幂等性 | 示例 |
|----------|--------|------|
| 查询类 | `IDEMPOTENT` | `*IDN?`, `MEAS:VOLT?`, `SYST:ERR?` |
| 执行类 | `NON_IDEMPOTENT` | `*RST`, `INIT`, `TRIG`, `ABOR` |
| 不确定 | `UNKNOWN` | 不清楚是否有副作用的命令 |

### 3. 设置合理的最大帧长度

对二进制协议设置合理的 `maxFrameLength`，防止恶意或异常数据导致 OOM：

```java
LengthFieldProtocol.defaults(StandardCharsets.UTF_8, 65536); // 64KB
```

### 4. DataListener 中不要执行长时间阻塞操作

DataListener 在接收线程中调用，阻塞操作会导致后续数据无法接收：

```java
// 错误示例
client.addDataListener(response -> {
    Thread.sleep(10000); // 阻塞接收线程！
    processResponse(response);
});

// 正确示例
ExecutorService executor = Executors.newCachedThreadPool();
client.addDataListener(response -> {
    executor.submit(() -> processResponse(response)); // 异步处理
});
```

### 5. 日志级别建议

- 默认只记录长度和状态
- 调试原始报文时才打开 TRACE 级别
- 对日志中的设备数据做脱敏处理

### 6. 生产环境建议增加指标监控

建议集成 Micrometer/OpenTelemetry，但不要让指标逻辑侵入通信核心。

### 7. 为每种仪器增加协议级集成测试

使用 `InstrumentationServer` 模拟仪器行为，验证协议解析的正确性。

### 8. 连接超时和响应超时应该分开设置

- `connectTimeout`：TCP 连接建立超时
- `responseTimeout`：发送命令后等待响应超时

```java
ClientConfig.builder()
    .connectTimeout(Duration.ofSeconds(3))   // 连接超时
    .responseTimeout(Duration.ofSeconds(10)) // 响应超时
    .build();
```

### 9. close() 会中断所有 pending 请求

调用 `client.close()` 后，所有未完成的请求都会以 `RequestCancelledException` 失败。

### 10. 不要在 close() 后继续使用 client

`close()` 是终态操作，调用后无法重新连接。需要重新创建 client 实例。

## 测试覆盖

项目包含 202 个测试用例，覆盖以下场景：

| 测试类 | 测试数量 | 覆盖场景 |
|--------|----------|----------|
| `ComprehensiveInstrumentClientTest` | 28 | 端到端集成、粘包/半包/超时/重连 |
| `InstrumentClientIntegrationTest` | 15 | API 表面、监听器、异步请求 |
| `RobustnessExtensibilityTest` | 18 | 拦截器、MockConnection、诊断、指标 |
| `NetworkFluctuationTest` | 5 | 网络闪断、延迟抖动、超时边界 |
| `RetryMechanismTest` | 6 | 重试回调、重试上限、close 中断 |
| `ProtocolBoundaryTest` | 10 | 半包/粘包/空响应/噪声数据/错误帧 |
| `ConcurrencyStressTest` | 8 | 多线程并发、队列溢出、死锁检测 |
| 其他单元测试 | 112 | 配置、模型、协议、指标 |

运行测试：

```bash
mvn test
```

## 构建要求

- Java 17+
- Maven 3.6+
- JUnit 5

```bash
mvn clean compile
mvn test
mvn package
```

## License

MIT License