# Instrument Client

一个面向**实验室仪器、测试设备、工控设备、SCPI/TCP 自定义协议**的 Java 17 通信客户端框架。

本版本不是在旧 `SocketClientITFImpl` 上继续堆功能，而是按照长期维护的目标重新划分职责：

- TCP 连接层：只负责连接、读写、关闭、状态。
- 接收层：持续读取 TCP 字节流，不再使用 `readPermit` 控制"什么时候读"。
- 协议层：只负责字节流 → 帧、命令 → 帧。
- 请求管理层：维护当前请求及响应匹配关系。
- 分发层：区分"当前请求响应"和"异步/未匹配数据"。
- 重连层：只负责连接恢复策略，不负责业务重试语义。
- 幂等性：明确区分 IDEMPOTENT / NON_IDEMPOTENT / UNKNOWN，避免因为自动重发导致仪器重复执行命令。

## Changelog

### 1.0.0

- Rebuilt TCP instrument client around explicit layers.
- Removed `readPermit`-based receiver gating.
- Removed `clearBuffers()` request association mechanism.
- Replaced `Condition` response waiting with `CompletableFuture`.
- Added explicit connection states.
- Added reconnect backoff with jitter.
- Added command idempotency classification.
- Added stateful line and length-field decoders.
- Added async response queue overflow policies.
- Added metrics snapshot.
- Added unit tests for fragmentation, sticky packets, checksum resync and request matching.

## 1. 核心设计

```text
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

Command -> Encoder -> TcpConnection -> Instrument
Response <- Decoder <- Receiver <- TcpConnection <- Instrument
```

## 2. 与旧版本相比的关键变化

| 旧问题 | 新设计 |
|---|---|
| `readPermit` 获取后进入持续读取，语义不清 | Receiver 连接后持续读取 |
| `clearBuffers()` 清理时可能与 decoder 并发 | 不再用清空缓存建立请求关联 |
| 每次发送前清理异步数据 | 异步数据独立进入 async queue/listener |
| `pendingFrames` + `Condition` | `PendingRequest` + `CompletableFuture` |
| 响应与异步推送混在一起 | Dispatcher 先匹配当前请求，否则作为 async data |
| 写失败后无条件重发 | 根据命令幂等性决定是否允许重试 |
| Socket 状态靠多个 socket flag 推断 | 显式 `ConnectionState` |
| 一个类承担所有职责 | Connection / Receiver / RequestManager / Dispatcher 分离 |
| reconnect 与 retry 混合 | reconnect 恢复连接，retry 由请求幂等性控制 |

## 3. 最重要的可靠性原则

### 3.1 TCP 是字节流，不是消息

`read()` 一次可能得到：

```text
半个包
一个完整包
多个连续包
```

所以协议 decoder 必须是**有状态的流解析器**。

### 3.2 不使用"清空旧响应"解决匹配问题

旧方案：

```text
send -> clearBuffers -> read -> match
```

新方案：

```text
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
matched -> CompletableFuture
not matched -> async queue/listener
```

这样不会因为 `clearBuffers()` 把合法异步消息误删。

### 3.3 自动重发不是天然安全

例如：

```text
START
```

客户端写入后网络异常，客户端无法知道仪器到底有没有收到。
如果直接 reconnect + resend：

```text
START
START
```

可能造成重复动作。

因此：

- `IDEMPOTENT`：允许按策略重试。
- `NON_IDEMPOTENT`：连接异常后直接失败。
- `UNKNOWN`：默认不重试。

对于查询类 SCPI 命令可以标记为 `IDEMPOTENT`；执行/启动/停止/触发等命令默认不要自动重发。

## 4. 快速开始

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

client.addDataListener(response ->
        System.out.println("ASYNC: " + response.text())
);

client.connect();

Response response = client.request(
        Command.text("*IDN?", StandardCharsets.UTF_8),
        ResponseMatcher.contains(""),
        Duration.ofSeconds(3),
        CommandIdempotency.IDEMPOTENT
);

System.out.println(response.text());

client.close();
```

## 5. SCPI 示例

```java
Response voltage = client.request(
        Command.text("MEAS:VOLT?", StandardCharsets.UTF_8),
        ResponseMatcher.regex("[-+]?\\d+(\\.\\d+)?"),
        Duration.ofSeconds(2),
        CommandIdempotency.IDEMPOTENT
);
```

## 6. 自定义二进制协议

默认实现：

```text
+------+--------+----------------+----------+
| 0xAA | LEN(2) | PAYLOAD(LEN)   | XOR(1)   |
+------+--------+----------------+----------+
```

XOR 计算范围：`STX + LEN + PAYLOAD`。

注意：如果你的真实协议使用 CRC-8/CRC-16，请实现对应的 `ProtocolEncoder/ProtocolDecoder`，不要把 XOR 称为 CRC。

## 7. 多连接

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

## 8. 生产环境建议

1. 给每种仪器单独实现业务 Adapter，不要让业务代码直接拼协议。
2. 查询命令可以明确标记 `IDEMPOTENT`。
3. `START/STOP/TRIGGER/RESET` 等命令默认 `NON_IDEMPOTENT`。
4. 对二进制协议设置合理的最大帧长度。
5. 不要在 DataListener 中执行长时间阻塞操作。
6. 对日志中的设备数据做脱敏；默认只记录长度和状态，调试原始报文时再打开 TRACE。
7. 为每一种仪器增加协议级集成测试。
8. 生产环境建议增加 Micrometer/OpenTelemetry 指标，但不要让指标逻辑侵入通信核心。

## 9. 测试覆盖方向

建议至少覆盖：

- 正常 request/response
- 多线程单连接请求串行化
- 异步推送与请求响应交错
- TCP 半包
- TCP 粘包
- 一次收到多个 frame
- response timeout
- EOF / connection reset
- reconnect
- idempotent retry
- non-idempotent no retry
- listener 异常隔离
- async queue overflow
- 非法长度
- checksum 错误
- 超大 frame

## 10. 项目结构

```text
src/main/java/com/example/instrument
├── api
├── config
├── connection
├── core
├── exception
├── metrics
├── model
└── protocol

src/test/java/com/example/instrument
├── core
└── protocol

docs
├── DESIGN.md
└── PROTOCOL_EXTENSION.md
```

---

## 11. 设计说明

### 11.1 目标

这个项目的目标不是做一个"能连接 TCP 的 Socket 工具类"，而是提供一个可以长期扩展的仪器通信基础设施。

目标特征：

- Java 17+
- TCP stream safe
- 同步 request/response
- 异步 unsolicited message
- 单连接 single-flight，适配绝大多数 SCPI 仪器
- 多连接
- 自动 reconnect
- 明确 retry 语义
- 文本协议 + 二进制协议
- 协议 decoder 状态独立
- 连接状态显式化
- 核心代码低耦合

### 11.2 核心功能

#### 11.2.1 连接管理

- **同步/异步请求**：支持 `request()` 同步阻塞调用和 `requestAsync()` 异步非阻塞调用
- **无应答发送**：`send()` 方法支持只发送命令不等待响应
- **自动重连**：可配置的指数退避重连策略，支持 jitter 避免 thundering herd
- **幂等重试**：IDEMPOTENT 命令在连接断开时自动重连并重试，UNKNOWN/NON_IDEMPOTENT 命令直接失败
- **连接状态监听**：通过 `ConnectionListener` 监听连接建立、断开、重连、重连失败等事件
- **优雅关闭**：`close()` 方法协调各线程安全退出，取消所有 pending 请求

#### 11.2.2 协议支持

- **可扩展协议接口**：`Protocol` 接口定义编码/解码抽象
- **内置 LineProtocol**：基于分隔符的文本协议（如 SCPI 换行符分隔）
- **内置 LengthFieldProtocol**：基于长度字段的二进制协议（如 0xAA + 长度 + 数据 + 校验和）
- **协议独立于连接**：协议编解码器与 TCP 连接解耦，可自由替换

#### 11.2.3 响应分发

- **智能匹配**：通过 `ResponseMatcher` 接口支持任意匹配逻辑（包含、精确匹配、正则、自定义 predicate）
- **异步通道**：未匹配到 pending 请求的响应不会丢失，而是进入异步队列并触发 `DataListener`
- **可配置溢出策略**：DROP_OLDEST、DROP_NEWEST、BLOCK、FAIL 四种策略应对队列满的情况
- **多监听器支持**：支持添加多个 `DataListener` 和 `ConnectionListener`，所有监听器都会收到相同事件

#### 11.2.4 指标收集

- **发送统计**：发送帧数、发送字节数
- **接收统计**：接收帧数、匹配成功帧数、异步帧数
- **错误统计**：丢弃的异步帧数、监听器错误数
- **连接统计**：重连次数、超时次数
- **线程安全**：使用 `LongAdder` 实现高并发下的低开销统计

### 11.3 分层架构

```
┌─────────────────────────────────────────────────┐
│              Factory Layer                       │
│         InstrumentClients                        │
├─────────────────────────────────────────────────┤
│              API Layer                           │
│    InstrumentClient  Interface                   │
│    ResponseMatcher / DataListener /              │
│    ConnectionListener / BlockingDataListener     │
├─────────────────────────────────────────────────┤
│             Core Layer                           │
│  InstrumentClientImpl / RequestManager /         │
│  ResponseDispatcher / Receiver / PendingRequest  │
├─────────────────────────────────────────────────┤
│          Connection Layer                        │
│     Connection / TcpConnection /                 │
│     ConnectionState / ReconnectPolicy            │
├─────────────────────────────────────────────────┤
│           Protocol Layer                         │
│  Protocol / ProtocolEncoder / ProtocolDecoder /  │
│  LineProtocol / LengthFieldProtocol              │
├─────────────────────────────────────────────────┤
│            Config Layer                          │
│    ClientConfig / ReconnectConfig                │
├─────────────────────────────────────────────────┤
│           Exception Layer                        │
│  InstrumentException / ConnectionException /     │
│  RequestTimeoutException / RequestCancelled      │
│  Exception / ProtocolException /                 │
│  ConfigurationException                          │
├─────────────────────────────────────────────────┤
│            Metrics Layer                         │
│           ClientMetrics                          │
└─────────────────────────────────────────────────┘
```

### 11.4 为什么一个连接默认只允许一个 pending request

很多仪器协议没有 request-id：

```text
client -> MEAS:VOLT?
instrument -> 1.234
```

如果同时：

```text
client -> A?
client -> B?
```

返回：

```text
response A
response B
```

客户端没有可靠方法知道哪个 response 属于哪个 request。

因此框架默认 single-flight：

```text
Request A
   |
   +---- waiting ----+
                    Response A
                         |
                    Request B
```

如果未来协议具有明确 correlation-id，可以新增 `CorrelationStrategy`，再升级到多 pending request。

### 11.5 Receiver 为什么持续运行

TCP 接收本质上是连接级事件流，而不是 request 级操作。

正确模型：

```text
Connection established
       |
    Receiver
       |
   read bytes
       |
    decoder
       |
  response frames
       |
 dispatcher
```

Receiver 不应该知道：

- 当前是不是某个命令
- regex 是什么
- 业务是什么
- 是否应该 clear buffer

这些属于更高层。

### 11.6 RequestManager

RequestManager 只有一个核心职责：

```text
当前 pending request
```

它不负责 socket，不负责 reconnect，不负责 protocol decode。

这使测试非常简单：给一个 Response，看它是否完成 Future。

### 11.7 ResponseDispatcher

分发规则：

```text
Response
   |
   +--> PendingRequest matcher == true --> complete future
   |
   +--> otherwise ----------------------> async channel
```

这里有一个重要取舍：

"当前 request 没匹配上的 response"不会被静默丢弃，而会进入 async 通道。

这样即使仪器发送 unsolicited event，也不会因为当前存在同步请求而丢失。

### 11.8 Reconnect 与 Retry

二者必须分离。

Reconnect：

```text
DISCONNECTED
    |
    v
RECONNECTING
    |
    +--> CONNECTED
    |
    +--> failed -> backoff
```

Retry：

```text
request failure
     |
     +-- timeout ------------> normally no retry
     |
     +-- connection error
              |
              +-- IDEMPOTENT -> may retry
              +-- UNKNOWN ----> fail
              +-- NON_IDEMPOTENT -> fail
```

### 11.9 Backoff

使用指数退避：

```text
delay = min(initial * 2^(attempt-1), max)
```

并加入 jitter，避免多个客户端同时断线后形成 reconnect thundering herd。

### 11.10 Decoder

Decoder 是 stateful object：

```java
List<Response> decode(byte[] bytes, int offset, int length);
void reset();
```

每个 InstrumentClient 创建自己的 decoder：

```text
Client A -> Decoder A
Client B -> Decoder B
```

绝对不要在多个 TCP connection 间共享有状态 decoder。

### 11.11 生命周期

```text
NEW
 |
 | connect()
 v
CONNECTING
 |
 +--> CONNECTED
 |
 +--> DISCONNECTED

CONNECTED -- network failure --> DISCONNECTED
DISCONNECTED -- reconnect --> RECONNECTING --> CONNECTED

any state -- close() --> CLOSING --> CLOSED
```

`close()` 设置 manualClose 标志，阻止后台 reconnect。

### 11.12 为什么不再使用 Condition

旧实现：

```text
Condition.await()
Condition.signalAll()
```

这要求自己正确处理：

- 锁
- signal 时机
- spurious wakeup
- timeout
- request 生命周期
- connection failure

`CompletableFuture` 更适合"一次请求对应一次结果"的语义。

### 11.13 未来扩展

可以在不改变核心 Connection/Receiver 的情况下增加：

```text
SCPI Adapter
Modbus TCP Adapter
Vendor Binary Adapter
ASCII Adapter
JSON-over-TCP Adapter
```

也可以增加：

- correlation-id
- command pipeline
- priority queue
- rate limiter
- metrics SPI
- tracing SPI
- TLS connection
- serial connection
- UDP connection
- heartbeat
- device capability discovery

## 12. 并发模型

### 12.1 线程模型

每个 client：

```text
Request Executor : 单线程，保证 request 顺序
Receiver Thread  : 一个，专门读 TCP
Reconnect Scheduler: 一个，负责延迟 reconnect
```

DataListener 默认在 receiver thread 上调用，因此 listener 必须快速返回。

如果业务需要耗时处理，应在 listener 中提交自己的业务线程池。

### 12.2 锁策略

采用**双锁设计**，分离不同粒度的同步需求：

| 锁 | 类型 | 保护范围 | 公平性 |
|---|---|---|---|
| `requestLock` | `ReentrantLock(true)` | 请求注册、写入、等待的原子性 | 公平锁 |
| `lifecycleLock` | `ReentrantLock(true)` | 连接建立、断开、关闭的生命周期 | 公平锁 |

**双锁分离的好处**：

- 避免请求操作和连接操作互相阻塞
- 公平锁保证线程饥饿问题
- 锁粒度最小化，提高并发性能

### 12.3 无锁并发

| 组件 | 技术 | 用途 |
|---|---|---|
| `RequestManager` | `AtomicReference` + CAS | 单个 pending request 的线程安全管理 |
| `ClientMetrics` | `LongAdder` | 高并发下的低开销指标统计 |
| `connectionListeners` | `CopyOnWriteArrayList` | 线程安全的监听器列表，读多写少场景 |
| `reconnectScheduled` | `AtomicBoolean` | 防止重复调度重连任务 |
| `closed` / `reconnectSuppressed` | `volatile` | 跨线程可见的状态标志 |

### 12.4 CompletableFuture 异步模型

```text
主线程                          Receiver 线程
  |                                |
  |  register(pending)             |
  |  write(command)                |
  |  future.get(timeout) ──阻塞──>  |
  |                                |  read(bytes)
  |                                |  decoder.decode()
  |                                |  dispatcher.dispatch(response)
  |                                |  pending.tryComplete(response)
  |  <──── Future 完成 ────────────|
  |  返回 response                 |
```

**优势**：

- 自动处理 spurious wakeup
- 内置超时支持
- 异常传播清晰
- 不需要手动管理 wait/notify

### 12.5 CAS 操作保证线程安全

`RequestManager` 使用 CAS 操作管理 pending request：

```java
// 注册：CAS null -> request
pending.compareAndSet(null, request)

// 分发：CAS request -> null（防止超时线程误删新请求）
pending.compareAndSet(request, null)

// 移除：CAS request -> null
pending.compareAndSet(request, null)
```

**关键设计点**：

- CAS 防止超时线程和分发线程竞争
- `getAndSet(null)` 保证 fail() 操作的原子性
- 避免使用 synchronized 减少锁竞争

## 13. 设计模式

### 13.1 工厂模式（Factory Pattern）

**InstrumentClients** 作为静态工厂类：

```java
// 静态工厂方法，隐藏实现细节
InstrumentClient client = InstrumentClients.tcp(host, port, protocol, config);
```

**优势**：

- 统一创建入口，便于扩展新的连接类型（如 TLS、Serial）
- 参数校验集中在工厂方法中
- 返回接口类型，降低耦合

### 13.2 建造者模式（Builder Pattern）

**ClientConfig.Builder** 使用链式调用构建复杂配置：

```java
ClientConfig config = ClientConfig.builder()
    .connectTimeout(Duration.ofSeconds(10))
    .responseTimeout(Duration.ofSeconds(30))
    .receiveBufferSize(16384)
    .overflowPolicy(OverflowPolicy.BLOCK)
    .reconnect(ReconnectConfig.defaults())
    .build();
```

**优势**：

- 避免 telescoping constructor 问题
- 配置项有默认值，只需覆盖需要的项
- 构建时统一校验参数合法性

### 13.3 策略模式（Strategy Pattern）

**Protocol 接口**定义编解码策略：

```java
// 可自由切换协议实现
Protocol lineProtocol = new LineProtocol();
Protocol binaryProtocol = new LengthFieldProtocol();

InstrumentClient client = InstrumentClients.tcp(host, port, protocol, config);
```

**OverflowPolicy 枚举**定义队列溢出策略：

```java
public enum OverflowPolicy { 
    DROP_OLDEST,   // 丢弃最老的
    DROP_NEWEST,   // 丢弃最新的
    BLOCK,         // 阻塞等待
    FAIL           // 抛出异常
}
```

**优势**：

- 开闭原则：新增协议无需修改核心代码
- 运行时可替换策略
- 每种策略独立测试

### 13.4 观察者模式（Observer Pattern）

**DataListener / ConnectionListener** 实现事件通知：

```java
// 添加多个监听器
client.addDataListener(response -> System.out.println("Async: " + response));
client.addConnectionListener(new ConnectionListener() {
    void onConnected() { ... }
    void onDisconnected(Throwable cause) { ... }
});
```

**实现细节**：

- 使用 `CopyOnWriteArrayList` 存储监听器
- 监听器调用被 try-catch 包裹，单个监听器异常不影响其他监听器
- 支持 `BlockingDataListener` 提供阻塞式消费接口

### 13.5 责任链模式（Chain of Responsibility）

**响应分发链路**：

```text
Receiver.read()
    |
    v
ProtocolDecoder.decode()
    |
    v
ResponseDispatcher.dispatch()
    |
    +--> RequestManager.dispatch() --> PendingRequest.tryComplete()
    |
    +--> asyncQueue.offer()
    |
    +--> DataListener.onData()
```

每个环节只关心自己的职责：

- Receiver 只负责读字节
- Decoder 只负责解码
- Dispatcher 只负责分发
- RequestManager 只管理 pending request

### 13.6 模板方法模式（Template Method）

**ReconnectPolicy** 封装重连判断逻辑：

```java
// 判断是否启用重连
boolean enabled() = config.enabled() && config.maxAttempts() > 0;

// 判断是否可以进行此次尝试
boolean canAttempt(int attempt) = enabled() && attempt <= config.maxAttempts();

// 计算延迟时间
Duration delay(int attempt) = config.delayForAttempt(attempt);
```

**优势**：

- 重连逻辑集中在一个类中
- 配置与策略分离
- 易于测试和替换

### 13.7 函数式接口（Functional Interface）

**ResponseMatcher** 采用函数式设计：

```java
// Lambda 表达式
ResponseMatcher matcher = r -> r.text().contains("OK");

// 方法引用
ResponseMatcher matcher = ResponseMatcher.contains("OK");

// 内置工厂方法
ResponseMatcher.any()
ResponseMatcher.equalsText("ERROR")
ResponseMatcher.regex("^DATA:.*$")
ResponseMatcher.predicate(r -> r.text().startsWith("INFO"))
```

**优势**：

- 减少样板代码
- 匹配逻辑可以组合
- 支持自定义复杂匹配

## 14. 设计亮点

### 14.1 并发安全

#### 14.1.1 Single-Flight 保证

通过 `requestLock` 保证"检查、注册、写入"三步原子性：

```java
requestLock.lock();
try {
    if (requestManager.hasPending()) {
        throw new IllegalStateException("another request is pending");
    }
    if (!isConnected()) {
        connect();
    }
    pending = requestManager.register(request.matcher());
    connection.write(encoder.encode(request.command()));
    return pending.future().get(timeout);
} finally {
    requestLock.unlock();
}
```

**防止的问题**：

- 并发请求导致响应通道被占用
- write 返回前快速响应被误判为异步数据
- 连接状态检查与写入之间的 TOCTOU 竞争

#### 14.1.2 无锁统计

`ClientMetrics` 使用 `LongAdder` 替代 `AtomicLong`：

```java
private final LongAdder sentFrames = new LongAdder();
private final LongAdder sentBytes = new LongAdder();
```

**优势**：

- 高并发下性能优于 `AtomicLong`（分段累加）
- 适合读少写多的统计场景
- `snapshot()` 方法提供一致的快照视图

#### 14.1.3 优雅关闭

```java
public void close() {
    lifecycleLock.lock();
    try {
        if (closed) return;
        closed = true;
        reconnectSuppressed = true;
        reconnectScheduled.set(false);
        Receiver r = receiver;
        if (r != null) r.stop();
        requestManager.fail(new ConnectionException("client closed"));
        connection.close();
        decoder.reset();
    } finally {
        lifecycleLock.unlock();
    }
    requestExecutor.shutdownNow();
    scheduler.shutdownNow();
}
```

**保证**：

- `volatile closed` 标志跨线程可见
- 先停止接收线程，再取消 pending 请求
- 最后关闭线程池，避免资源泄漏

### 14.2 错误处理

#### 14.2.1 异常层次

```
InstrumentException
├── ConnectionException      // 连接相关错误
├── RequestTimeoutException  // 请求超时
├── RequestCancelledException // 请求取消
├── ProtocolException        // 协议解析错误
└── ConfigurationException   // 配置错误
```

#### 14.2.2 监听器异常隔离

```java
for (var l : connectionListeners) {
    try { l.onConnected(); } catch (Throwable ignored) {}
}
```

单个监听器异常不会影响其他监听器和核心流程。

#### 14.2.3 Receiver 异常传播

Receiver 线程捕获异常后通过回调通知主线程：

```java
catch (Throwable t) {
    if (running) {
        errorHandler.accept(t);  // 触发重连逻辑
    }
}
```

### 14.3 可扩展性

#### 14.3.1 协议扩展

新增协议只需实现 `Protocol` 接口：

```java
public class MyProtocol implements Protocol {
    @Override
    public ProtocolEncoder newEncoder() { ... }
    
    @Override
    public ProtocolDecoder newDecoder() { ... }
}
```

#### 14.3.2 连接类型扩展

新增连接类型（如 TLS、Serial）只需实现 `Connection` 接口：

```java
public interface Connection {
    void connect();
    int read(byte[] buffer) throws IOException;
    void write(byte[] data) throws IOException;
    boolean isConnected();
    ConnectionState state();
    void close();
}
```

#### 14.3.3 匹配器扩展

`ResponseMatcher` 是函数式接口，可以任意组合：

```java
// 自定义复杂匹配逻辑
ResponseMatcher matcher = r -> 
    r.text().contains("OK") && r.bytes().length > 10;
```

### 14.4 测试友好

#### 14.4.1 组件解耦

- `RequestManager` 不依赖 socket，可独立测试
- `ResponseDispatcher` 不依赖网络，可模拟响应
- `Receiver` 可注入 mock Connection

#### 14.4.2 测试覆盖

- 单元测试：`RequestManagerTest`、`ResponseDispatcherTest`、`ReconnectPolicyTest`
- 集成测试：`InstrumentClientIntegrationTest`、`ComprehensiveInstrumentClientTest`
- 协议测试：`LineProtocolTest`、`LengthFieldProtocolTest`

### 14.5 资源管理

#### 14.5.1 AutoCloseable

`InstrumentClient` 实现 `AutoCloseable`，支持 try-with-resources：

```java
try (InstrumentClient client = InstrumentClients.tcp(host, port, protocol, config)) {
    client.connect();
    Response response = client.request(command, matcher, timeout);
}
```

#### 14.5.2 线程生命周期

- Receiver 线程设置为 daemon 线程，JVM 退出时自动清理
- `shutdownNow()` 确保线程池正确关闭
- `volatile running` 标志控制 Receiver 线程退出

### 14.6 性能优化

#### 14.6.1 TCP 优化

- `TCP_NODELAY = true`：禁用 Nagle 算法，减少延迟
- `SO_KEEPALIVE = true`：启用 TCP keepalive，检测连接存活
- 可配置的 `SO_RCVBUF`：优化接收缓冲区大小

#### 14.6.2 内存优化

- Decoder 状态独立，避免共享状态导致的内存竞争
- 固定大小缓冲区复用，减少 GC 压力
- `ArrayBlockingQueue` 预分配异步队列内存

#### 14.6.3 锁优化

- 公平锁避免线程饥饿
- 双锁分离减少锁竞争
- CAS 操作替代部分锁场景

## 15. 核心类职责

| 类 | 职责 | 关键设计 |
|---|---|---|
| `InstrumentClientImpl` | 整合所有组件，实现客户端接口 | 双锁策略、幂等重试、优雅关闭 |
| `RequestManager` | 管理当前 pending request | AtomicReference + CAS |
| `ResponseDispatcher` | 分发响应到请求或异步通道 | OverflowPolicy、多监听器 |
| `Receiver` | 持续读取 TCP 数据并解码 | 独立线程、daemon 模式 |
| `PendingRequest` | 封装 matcher 和 Future | CompletableFuture 异步模型 |
| `TcpConnection` | 封装 Socket 操作 | 生命周期管理、线程安全 |
| `ReconnectPolicy` | 重连策略判断 | 指数退避 + jitter |
| `ClientMetrics` | 指标收集 | LongAdder 高并发统计 |
| `ClientConfig` | 配置管理 | Builder 模式、默认值 |

## 16. 数据流

### 16.1 同步请求流程

```
用户线程                     InstrumentClientImpl              Receiver 线程
   |                                |                              |
   |  request(command, matcher)     |                              |
   |------------------------------>|                              |
   |                                |  requestLock.lock()          |
   |                                |  check pending               |
   |                                |  register(matcher)           |
   |                                |  write(encoded command)      |
   |                                |  future.get(timeout) ─阻塞─> |
   |                                |                              |  read(bytes)
   |                                |                              |  decode(responses)
   |                                |                              |  dispatch(response)
   |                                |  <── tryComplete(response) ──|
   |  <── 返回 response ────────────|                              |
   |                                |  requestLock.unlock()        |
```

### 16.2 异步数据流程

```
仪器                        Receiver 线程              ResponseDispatcher         DataListener
  |                             |                            |                        |
  |── TCP data ────────────────>|                            |                        |
  |                             |── decode(response) ───────>|                        |
  |                             |                            |── 无 pending request   |
  |                             |                            |── asyncQueue.offer()   |
  |                             |                            |── listener.onData() ──>|
  |                             |                            |                        |── 处理异步数据
```

### 16.3 重连流程

```
Receiver 线程              InstrumentClientImpl           Reconnect Scheduler
     |                            |                              |
     |── read error ─────────────>|                              |
     |                            |── fail pending requests      |
     |                            |── disconnect()               |
     |                            |── notifyDisconnected()       |
     |                            |── scheduleReconnect() ──────>|
     |                            |                              |── delay(backoff)
     |                            |                              |── connect()
     |                            |<─────────────────────────────|
     |                            |── startReceiver() ──────────>|
     |                            |── notifyConnected()          |
```

## 17. 协议扩展说明

### 17.1 Protocol 接口

协议只关心两个方向：

```text
Command -> bytes
bytes   -> Response
```

```java
public interface Protocol {
    ProtocolEncoder newEncoder();
    ProtocolDecoder newDecoder();
}
```

### 17.2 新增协议

建议实现：

```java
public final class MyProtocol implements Protocol {
    @Override
    public ProtocolEncoder newEncoder() { ... }

    @Override
    public ProtocolDecoder newDecoder() { ... }
}
```

Decoder 必须支持：

- fragmentation
- sticky packet
- multiple frames
- malformed frame resynchronization
- maximum frame length

### 17.3 不要在 Protocol 中做业务

不要：

```java
if (command.equals("START")) { ... }
```

协议层只处理 frame。

业务层负责：

```text
Why send?
What does response mean?
What should happen after response?
```

### 17.4 Checksum 命名

如果算法是：

```java
checksum ^= value;
```

它是 XOR checksum，不应写成 CRC-8。

真正 CRC-8 需要明确 polynomial、initial、refin、refout、xorout 等参数。

## 18. 总结

本项目通过以下设计原则构建了一个高质量、可扩展的仪器通信框架：

1. **单一职责**：每个组件只关注自己的核心职责
2. **依赖倒置**：面向接口编程，Protocol/Connection 可自由替换
3. **开闭原则**：新增功能无需修改核心代码
4. **并发安全**：双锁 + CAS + volatile + CompletableFuture
5. **错误隔离**：监听器异常不影响核心流程
6. **测试友好**：组件解耦，可独立测试
7. **资源管理**：AutoCloseable + daemon 线程 + 优雅关闭