# Instrument Client

面向实验室仪器、测试设备及支持 TCP 自定义协议设备的 Java 17 通信客户端框架。提供 TCP 连接管理、协议编解码、请求/响应匹配、异步数据接收、断线自动重连、命令幂等性重试、拦截器链、运行时指标与诊断能力。

## 主要功能

| 能力 | 说明 |
|------|------|
| 连接管理 | TCP 连接建立、状态监听、优雅关闭及自动重连（指数退避 + 抖动） |
| 协议编解码 | 行协议（LF/CRLF）和长度字段二进制协议，可扩展自定义协议 |
| 请求模式 | 同步请求 `request()`、异步请求 `requestAsync()`、无应答发送 `send()` |
| 响应匹配 | 通过 `ResponseMatcher` 区分请求响应与非请求数据，支持 contains/equals/regex/predicate |
| 异步数据 | `DataListener` 事件监听、`BlockingDataListener` 阻塞式读取、溢出策略 |
| 拦截器链 | `ClientInterceptor` 拦截请求/响应生命周期，用于日志、监控、审计 |
| 幂等重试 | `CommandIdempotency` 标记命令幂等性，控制网络异常时的重试行为 |
| 指标与诊断 | `ClientMetrics` 运行时指标快照、`ClientDiagnostics` 诊断报告 |

## 环境要求

- JDK 17 或更高版本
- Maven 3.6 或更高版本

## 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                     业务调用方                               │
│         client.request() / client.send() / ...              │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                  api 层（面向用户）                           │
│  InstrumentClient  InstrumentClients  ResponseMatcher        │
│  DataListener  ConnectionListener  ClientInterceptor         │
│  BlockingDataListener                                       │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│               transport 层（核心实现）                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │InstrumentClientImpl│  │ RequestManager │  │ResponseDispatcher│  │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │   Receiver    │  │PendingRequest │  │  ReconnectPolicy │  │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
│                         │                                   │
│            ┌────────────┼────────────┐                      │
│            ▼            ▼            ▼                      │
│     ┌──────────┐  ┌──────────┐  ┌──────────┐               │
│     │TcpConnection│  │ProtocolEncoder│  │ProtocolDecoder│    │
│     └──────────┘  └──────────┘  └──────────┘               │
└─────────────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│              core 层（配置、模型、异常、指标、诊断）            │
│  ClientConfig  Command  Response  CommandIdempotency         │
│  ClientMetrics  ClientDiagnostics  InstrumentException       │
└─────────────────────────────────────────────────────────────┘
```

## 包结构

```text
com.example.instrument
├── api                         # 客户端接口与公共 API
│   ├── InstrumentClient        # 核心客户端接口（connect/request/send/close）
│   ├── InstrumentClients       # 静态工厂 + Builder
│   ├── ResponseMatcher         # 响应匹配器（函数式接口）
│   ├── DataListener            # 异步数据监听器
│   ├── BlockingDataListener    # 阻塞式数据监听器
│   ├── ConnectionListener      # 连接状态监听器
│   └── ClientInterceptor       # 请求/响应拦截器
├── protocol                    # 协议抽象与内置实现
│   ├── Protocol                # 协议接口（newEncoder/newDecoder）
│   ├── ProtocolEncoder         # 编码器接口
│   ├── ProtocolDecoder         # 解码器接口（处理半包/粘包）
│   ├── LineProtocol            # 行分隔符协议（LF/CRLF）
│   ├── LengthFieldProtocol     # 长度字段二进制协议（STX+长度+数据+校验和）
│   └── ProtocolException       # 协议异常
├── transport                   # 传输层核心实现
│   ├── InstrumentClientImpl    # 客户端核心实现（双锁、线程模型、重连调度）
│   ├── RequestManager          # 请求生命周期管理（AtomicReference + CAS）
│   ├── PendingRequest          # 待处理请求（CompletableFuture + Matcher）
│   ├── ResponseDispatcher      # 响应分发（匹配 → 异步队列 → 监听器）
│   ├── Receiver                # 接收线程（循环 read → decode → dispatch）
│   ├── ConnectionState         # 连接状态枚举（有限状态机）
│   ├── tcp/
│   │   ├── Connection          # 连接接口（connect/read/write/close）
│   │   └── TcpConnection       # Socket 实现（BIO、Socket 选项、状态机）
│   └── reconnect/
│       ├── ReconnectConfig     # 重连配置（指数退避 + 抖动）
│       └── ReconnectPolicy     # 重连策略（canAttempt/delay）
├── core                        # 公共基础
│   ├── config/
│   │   └── ClientConfig        # 客户端配置（Builder 模式）
│   ├── model/
│   │   ├── Command             # 命令（不可变、字节存储）
│   │   ├── Response            # 响应（不可变、帧/体双存储）
│   │   └── CommandIdempotency  # 幂等性枚举（IDEMPOTENT/NON_IDEMPOTENT/UNKNOWN）
│   ├── exception/
│   │   ├── InstrumentException # 基础异常
│   │   ├── ConnectionException # 连接异常
│   │   ├── RequestTimeoutException # 请求超时异常
│   │   ├── RequestCancelledException # 请求取消异常
│   │   └── ConfigurationException # 配置异常
│   ├── metrics/
│   │   └── ClientMetrics       # 指标收集器（LongAdder + Snapshot）
│   └── diagnostics/
│       └── ClientDiagnostics   # 诊断信息（状态历史、请求记录、错误统计）
└── example/
    └── ClientExample           # 使用示例
```

## 快速开始

### 最简示例

使用行协议连接本机 `5025` 端口的仪器（如 SCPI 兼容设备）：

```java
InstrumentClient client = InstrumentClients.tcp(
        new InetSocketAddress("127.0.0.1", 5025),
        LineProtocol.crlf(StandardCharsets.UTF_8),
        ClientConfig.defaults());

try {
    client.connect();
    var response = client.request(
            Command.text("*IDN?", StandardCharsets.UTF_8),
            ResponseMatcher.any(),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT);
    System.out.println(response.text(StandardCharsets.UTF_8));
} finally {
    client.close();
}
```

### Builder 方式创建

```java
InstrumentClient client = InstrumentClients.builder("192.168.1.100", 5025,
        LineProtocol.crlf(StandardCharsets.UTF_8))
    .connectTimeout(Duration.ofSeconds(10))
    .responseTimeout(Duration.ofSeconds(5))
    .build();
```

### 自定义配置

```java
ClientConfig config = ClientConfig.builder()
    .connectTimeout(Duration.ofSeconds(10))
    .responseTimeout(Duration.ofSeconds(30))
    .receiveBufferSize(16384)
    .sendBufferSize(8192)
    .tcpNoDelay(true)
    .keepAlive(true)
    .soLingerSeconds(-1)
    .overflowPolicy(ClientConfig.OverflowPolicy.DROP_OLDEST)
    .asyncQueueCapacity(512)
    .reconnect(ReconnectConfig.defaults())
    .build();
```

### 异步请求

```java
CompletableFuture<Response> future = client.requestAsync(
    Command.text("MEAS:VOLT?", StandardCharsets.UTF_8),
    ResponseMatcher.contains("VOLT"),
    Duration.ofSeconds(5),
    CommandIdempotency.IDEMPOTENT);

future.thenAccept(r -> System.out.println(r.text(StandardCharsets.UTF_8)))
      .exceptionally(e -> { System.err.println(e.getMessage()); return null; });
```

### 无应答发送

```java
client.send(Command.text("*RST", StandardCharsets.UTF_8));
```

### 连接状态监听

```java
client.addConnectionListener(new ConnectionListener() {
    @Override public void onConnected() {
        System.out.println("已连接");
    }
    @Override public void onDisconnected(Throwable cause) {
        System.out.println("已断开: " + (cause != null ? cause.getMessage() : "主动断开"));
    }
    @Override public void onReconnecting(int attempt, Duration delay, Throwable cause) {
        System.out.println("重连中... 第" + attempt + "次，" + delay + "后重试");
    }
    @Override public void onReconnectFailed(Throwable cause) {
        System.err.println("重连失败: " + cause.getMessage());
    }
});
```

### 异步数据接收

```java
// 方式一：事件监听
client.addDataListener(response ->
    System.out.println("异步数据: " + response.text(StandardCharsets.UTF_8)));

// 方式二：阻塞式读取
BlockingDataListener listener = client.blockingDataListener();
Response data = listener.poll(Duration.ofSeconds(10));
```

### 拦截器

```java
client.addInterceptor(new ClientInterceptor() {
    @Override public void beforeRequest(Command cmd, CommandIdempotency idempotency) {
        log.info(">>> {}", cmd.text());
    }
    @Override public void afterResponse(Command cmd, Response resp, Duration latency) {
        log.info("<<< {} ({}ms)", resp.text(), latency.toMillis());
    }
    @Override public void afterFailure(Command cmd, Throwable t) {
        log.error("!!! {} failed: {}", cmd.text(), t.getMessage());
    }
});
```

### 指标与诊断

```java
ClientMetrics.Snapshot snapshot = client.metrics();
System.out.printf("发送: %d帧/%dB, 接收: %d帧(匹配%d/异步%d), 丢弃: %d, 重连: %d, 超时: %d%n",
    snapshot.sentFrames(), snapshot.sentBytes(),
    snapshot.receivedFrames(), snapshot.matchedFrames(), snapshot.asyncFrames(),
    snapshot.droppedAsync(), snapshot.reconnects(), snapshot.timeouts());

DiagnosticReport report = client.diagnostics();
System.out.println(report);
```

## 协议支持

### 行协议（LineProtocol）

通过 `LineProtocol` 配置 LF 或 CRLF 行结束符及字符集，适用于 SCPI 等文本命令/响应设备。解码器内部使用 `ByteArrayOutputStream` 缓存不完整数据，正确处理半包和粘包。

```java
Protocol protocol = LineProtocol.crlf(StandardCharsets.UTF_8);           // CRLF 分隔
Protocol protocol2 = LineProtocol.lf(StandardCharsets.UTF_8);            // LF 分隔
Protocol protocol3 = LineProtocol.crlf(StandardCharsets.UTF_8)           // 自定义最大帧长
    .withMaxFrameLength(4096);
```

### 长度字段协议（LengthFieldProtocol）

帧格式：`[STX=0xAA][长度(2字节,大端)][数据][XOR校验和]`。解码器使用 `ByteBuffer` 管理读写状态，处理半包/粘包/非法帧。

```java
Protocol protocol = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
Protocol protocol2 = new LengthFieldProtocol(StandardCharsets.UTF_8, 1024); // 最大载荷 1024
```

如设备使用 CRC 等其他校验算法，应实现与设备协议一致的编解码逻辑。

### 自定义协议

实现 `Protocol` 接口，提供自定义的 `ProtocolEncoder` 和 `ProtocolDecoder`。解码器需要正确处理半包、粘包及非法帧，并设置最大帧长度限制。

## 配置说明

### ClientConfig 参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `connectTimeout` | 5s | TCP 连接建立超时 |
| `responseTimeout` | 10s | 默认请求响应超时 |
| `socketReadTimeoutMillis` | 0（无限） | Socket SO_TIMEOUT，仅影响 read() |
| `receiveBufferSize` | 8192 | Socket SO_RCVBUF 接收缓冲区 |
| `sendBufferSize` | 8192 | Socket SO_SNDBUF 发送缓冲区 |
| `soLingerSeconds` | -1 | SO_LINGER：-1=优雅关闭, 0=RST, >0=等待秒数 |
| `tcpNoDelay` | true | 禁用 Nagle 算法，减少小包延迟 |
| `keepAlive` | true | 启用 TCP keepalive 探测 |
| `asyncQueueCapacity` | 256 | 异步响应队列容量 |
| `overflowPolicy` | DROP_OLDEST | 队列溢出策略 |

### 溢出策略（OverflowPolicy）

| 策略 | 行为 |
|------|------|
| `DROP_OLDEST` | 丢弃队列中最老的响应，添加新响应 |
| `DROP_NEWEST` | 丢弃新响应，保留队列中已有响应 |
| `BLOCK` | 阻塞直到队列有空位 |
| `FAIL` | 直接抛出 IllegalStateException |

### 重连配置（ReconnectConfig）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `enabled` | true | 是否启用自动重连 |
| `maxAttempts` | 3 | 最大重试次数 |
| `initialDelay` | 1s | 初始延迟 |
| `maxDelay` | 30s | 最大延迟 |
| `jitterRatio` | 0.20 | 随机抖动比例（避免雷鸣群效应） |

延迟计算：`min(initialDelay × 2^(attempt-1), maxDelay) ± jitter`

## 核心设计

### 线程模型

```
业务线程 ────→ requestLock ────→ [注册 pending] ────→ [write 命令] ────→ [等待 future]
                                                                        ↑
Receiver 线程 ──→ [read 数据] ──→ [decode 帧列表] ──→ [dispatch 响应] ──┘
                                                                  │
                                              ┌──────────────────┘
                                              ▼
                                        [异步队列 → DataListener]

Reconnect 线程 ──→ [退避延迟] ──→ [connect] ──→ [startReceiver]

Request 线程池 ──→ [requestAsync 委托执行]
```

### 双锁策略

- **`requestLock`（公平锁）**：保护"检查 pending → 注册 → 写入命令"的原子性，同一连接同一时刻只允许一个请求占用响应通道
- **`lifecycleLock`（公平锁）**：保护连接生命周期（connect/disconnect/reconnect/close），防止多线程同时操作 Socket

### 连接状态机

```
NEW ──► CONNECTING ──成功──► CONNECTED
 │          │                    │
 │      失败/断开           异常/对端关闭
 │          ▼                    ▼
 │      DISCONNECTED ◄──────────┘
 │          │
 │       close()
 │          ▼
 └────► CLOSING ──► CLOSED（终态，不可逆）
```

### 请求生命周期

```
register(matcher) ──► [pending 等待] ──► dispatch(response) ──► future.complete(response)
                         │
                    ┌────┼────┐
                    ▼    ▼    ▼
                 超时  中断  连接断开
                    │    │    │
                    ▼    ▼    ▼
                 remove  remove  fail(cause)
                    │    │    │
                    ▼    ▼    ▼
              RequestTimeoutException / ConnectionException
```

### 响应分发逻辑

```
收到响应 → RequestManager.dispatch(response)
              │
              ├── matcher 匹配成功 → complete pending future → metrics.receivedMatched()
              │
              └── 无匹配 → 异步队列（overflowPolicy） → DataListener.onData() → metrics.receivedAsync()
```

## 请求与重试注意事项

- `connectTimeout` 用于连接建立；请求等待时间由请求调用的 `timeout` 参数控制。
- `IDEMPOTENT` 适合重复执行不会产生额外副作用的命令（如查询）；控制类命令应根据设备行为谨慎选择 `NON_IDEMPOTENT` 或 `UNKNOWN`。
- **超时不等于命令执行失败**：连接中断或响应超时，并不一定代表设备没有执行命令。对于启动、触发、复位、写入设定值等命令，不应未经判断就自动重发。
- `DataListener` 应尽量快速返回，耗时业务处理建议交给业务线程池，避免阻塞接收流程。
- 使用完客户端后调用 `close()` 释放连接和相关资源；关闭后不要继续使用该实例（终态保护）。
- 调试原始设备报文时注意日志可能包含设备数据或业务信息。

## 测试用例

测试代码位于 `src/test/java`，主要覆盖以下方面：

| 类别 | 测试类 | 重点用例 |
|------|--------|----------|
| 协议解析 | `LineProtocolTest` / `LengthFieldProtocolTest` | 半包、粘包、连续多帧、非法长度、缓冲区扩容 |
| 请求响应 | `ClientRequestTest` / `RequestManagerTest` / `ResponseDispatcherTest` | 正常响应、超时、迟到响应、未知响应、并发请求 |
| 连接生命周期 | `ClientLifecycleTest` / `ClientRetryTest` | 连接、关闭、重连策略、网络故障 |
| 并发与异步 | `ClientConcurrencyTest` / `ClientAsyncDataTest` | 并发请求、异步数据接收、发送与关闭竞争 |
| 网络故障 | `NetworkFailureIntegrationTest` | 断网恢复、设备重启、延迟响应 |
| 协议边界 | `ProtocolBoundaryIntegrationTest` | 半包/粘包边界、非法帧恢复 |
| 配置与模型 | `ClientConfigTest` / `ModelTest` / `CommandModelTest` | 配置参数、命令/响应模型 |
| 指标 | `ClientMetricsTest` | 指标计数与快照 |

运行全部测试：

```bash
mvn clean test
```

## 构建

```bash
mvn clean package
```

## 依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| SLF4J API | 2.0.17 | 日志门面 |
| Logback Classic | 1.5.18 | 日志实现 |
| JUnit Jupiter | 5.12.2 | 单元测试（test scope） |