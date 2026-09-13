# 设计说明

## 1. 目标

这个项目的目标不是做一个“能连接 TCP 的 Socket 工具类”，而是提供一个可以长期扩展的仪器通信基础设施。

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

## 2. 为什么一个连接默认只允许一个 pending request

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

## 3. Receiver 为什么持续运行

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

## 4. RequestManager

RequestManager 只有一个核心职责：

```text
当前 pending request
```

它不负责 socket，不负责 reconnect，不负责 protocol decode。

这使测试非常简单：给一个 Response，看它是否完成 Future。

## 5. ResponseDispatcher

分发规则：

```text
Response
   |
   +--> PendingRequest matcher == true --> complete future
   |
   +--> otherwise ----------------------> async channel
```

这里有一个重要取舍：

“当前 request 没匹配上的 response”不会被静默丢弃，而会进入 async 通道。

这样即使仪器发送 unsolicited event，也不会因为当前存在同步请求而丢失。

## 6. Reconnect 与 Retry

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

## 7. Backoff

使用指数退避：

```text
delay = min(initial * 2^(attempt-1), max)
```

并加入 jitter，避免多个客户端同时断线后形成 reconnect thundering herd。

## 8. Decoder

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

## 9. 生命周期

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

## 10. 并发模型

每个 client：

```text
Request Executor : 单线程，保证 request 顺序
Receiver Thread  : 一个，专门读 TCP
Reconnect Scheduler: 一个，负责延迟 reconnect
```

DataListener 默认在 receiver thread 上调用，因此 listener 必须快速返回。

如果业务需要耗时处理，应在 listener 中提交自己的业务线程池。

## 11. 为什么不再使用 Condition

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

`CompletableFuture` 更适合“一次请求对应一次结果”的语义。

## 12. 未来扩展

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
