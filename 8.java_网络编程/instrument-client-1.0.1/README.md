# Instrument Client

一个面向**实验室仪器、测试设备、工控设备、SCPI/TCP 自定义协议**的 Java 17 通信客户端框架。

本版本不是在旧 `SocketClientITFImpl` 上继续堆功能，而是按照长期维护的目标重新划分职责：

- TCP 连接层：只负责连接、读写、关闭、状态。
- 接收层：持续读取 TCP 字节流，不再使用 `readPermit` 控制“什么时候读”。
- 协议层：只负责字节流 → 帧、命令 → 帧。
- 请求管理层：维护当前请求及响应匹配关系。
- 分发层：区分“当前请求响应”和“异步/未匹配数据”。
- 重连层：只负责连接恢复策略，不负责业务重试语义。
- 幂等性：明确区分 IDEMPOTENT / NON_IDEMPOTENT / UNKNOWN，避免因为自动重发导致仪器重复执行命令。

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

### 3.2 不使用“清空旧响应”解决匹配问题

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
