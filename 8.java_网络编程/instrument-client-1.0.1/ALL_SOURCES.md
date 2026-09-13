# Instrument Client - All Sources

## `CHANGELOG.md`

```markdown
# Changelog

## 1.0.0

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

```

## `README.md`

```markdown
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

```

## `docs/DESIGN.md`

```markdown
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

```

## `docs/PROTOCOL_EXTENSION.md`

```markdown
# 协议扩展说明

## 1. Protocol 接口

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

## 2. 新增协议

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

## 3. 不要在 Protocol 中做业务

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

## 4. Checksum 命名

如果算法是：

```java
checksum ^= value;
```

它是 XOR checksum，不应写成 CRC-8。

真正 CRC-8 需要明确 polynomial、initial、refin、refout、xorout 等参数。

```

## `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>instrument-client</artifactId>
    <version>1.0.0</version>
    <name>instrument-client</name>
    <description>Maintainable Java 17 TCP instrument communication client framework</description>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <junit.version>5.12.2</junit.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.14.0</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.2</version>
            </plugin>
        </plugins>
    </build>
</project>

```

## `src/main/java/com/example/instrument/Demo.java`

```java
package com.example.instrument;

import com.example.instrument.api.InstrumentClient;
import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.model.Command;
import com.example.instrument.model.CommandIdempotency;
import com.example.instrument.protocol.LineProtocol;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Minimal usage example. */
public final class Demo {
    private Demo() {}
    public static void main(String[] args) {
        ClientConfig config=ClientConfig.defaults();
        InstrumentClient client=InstrumentClients.tcp(
                new InetSocketAddress("127.0.0.1",5025),
                LineProtocol.crlf(StandardCharsets.UTF_8),config);
        try {
            client.connect();
            var response=client.request(
                    Command.text("*IDN?",StandardCharsets.UTF_8),
                    ResponseMatcher.any(),Duration.ofSeconds(3),CommandIdempotency.IDEMPOTENT);
            System.out.println(response.text(StandardCharsets.UTF_8));
        } finally { client.close(); }
    }
}

```

## `src/main/java/com/example/instrument/InstrumentClients.java`

```java
package com.example.instrument;

import com.example.instrument.api.InstrumentClient;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.core.InstrumentClientImpl;
import com.example.instrument.protocol.Protocol;
import java.net.InetSocketAddress;
import java.util.Objects;

public final class InstrumentClients {
    private InstrumentClients() {}
    public static InstrumentClient tcp(InetSocketAddress address,Protocol protocol,ClientConfig config){
        return new InstrumentClientImpl(Objects.requireNonNull(address),Objects.requireNonNull(protocol),Objects.requireNonNull(config));
    }
    public static InstrumentClient tcp(String host,int port,Protocol protocol,ClientConfig config){
        return tcp(new InetSocketAddress(host,port),protocol,config);
    }
}

```

## `src/main/java/com/example/instrument/api/BlockingDataListener.java`

```java
package com.example.instrument.api;

import com.example.instrument.model.Response;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public final class BlockingDataListener implements DataListener {
    private final BlockingQueue<Response> queue;

    public BlockingDataListener(BlockingQueue<Response> queue) { this.queue = queue; }
    @Override public void onData(Response response) { queue.offer(response); }
    public Response take() throws InterruptedException { return queue.take(); }
    public Response poll(Duration timeout) throws InterruptedException {
        return queue.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }
    public int size() { return queue.size(); }
}

```

## `src/main/java/com/example/instrument/api/ConnectionListener.java`

```java
package com.example.instrument.api;

public interface ConnectionListener {
    default void onConnected() {}
    default void onDisconnected(Throwable cause) {}
    default void onReconnecting(int attempt, java.time.Duration delay, Throwable cause) {}
    default void onReconnectFailed(Throwable cause) {}
}

```

## `src/main/java/com/example/instrument/api/DataListener.java`

```java
package com.example.instrument.api;

import com.example.instrument.model.Response;

@FunctionalInterface
public interface DataListener {
    void onData(Response response);
}

```

## `src/main/java/com/example/instrument/api/InstrumentClient.java`

```java
package com.example.instrument.api;

import com.example.instrument.model.Command;
import com.example.instrument.model.CommandIdempotency;
import com.example.instrument.model.Response;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public interface InstrumentClient extends AutoCloseable {
    void connect();
    void disconnect();
    boolean isConnected();
    Response request(Command command, ResponseMatcher matcher, Duration timeout, CommandIdempotency idempotency);
    default Response request(Command command, ResponseMatcher matcher, Duration timeout) {
        return request(command, matcher, timeout, CommandIdempotency.UNKNOWN);
    }
    CompletableFuture<Response> requestAsync(Command command, ResponseMatcher matcher, Duration timeout,
                                              CommandIdempotency idempotency);
    void send(Command command);
    void addDataListener(DataListener listener);
    void removeDataListener(DataListener listener);
    void addConnectionListener(ConnectionListener listener);
    void removeConnectionListener(ConnectionListener listener);
    BlockingDataListener blockingDataListener();
    @Override void close();
}

```

## `src/main/java/com/example/instrument/api/ResponseMatcher.java`

```java
package com.example.instrument.api;

import com.example.instrument.model.Response;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@FunctionalInterface
public interface ResponseMatcher {
    boolean matches(Response response);

    static ResponseMatcher any() { return r -> true; }
    static ResponseMatcher predicate(Predicate<Response> predicate) {
        Objects.requireNonNull(predicate);
        return predicate::test;
    }
    static ResponseMatcher contains(String text) { return contains(text, StandardCharsets.UTF_8); }
    static ResponseMatcher contains(String text, Charset charset) {
        Objects.requireNonNull(text); Objects.requireNonNull(charset);
        return r -> r.text(charset).contains(text);
    }
    static ResponseMatcher equalsText(String text) { return equalsText(text, StandardCharsets.UTF_8); }
    static ResponseMatcher equalsText(String text, Charset charset) {
        Objects.requireNonNull(text); Objects.requireNonNull(charset);
        return r -> r.text(charset).equals(text);
    }
    static ResponseMatcher regex(String regex) { return regex(regex, StandardCharsets.UTF_8); }
    static ResponseMatcher regex(String regex, Charset charset) {
        Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);
        return r -> pattern.matcher(r.text(charset)).matches();
    }
}

```

## `src/main/java/com/example/instrument/config/ClientConfig.java`

```java
package com.example.instrument.config;

import java.net.SocketOption;
import java.time.Duration;
import java.util.Objects;

public final class ClientConfig {
    private final Duration connectTimeout;
    private final Duration responseTimeout;
    private final int socketReadTimeoutMillis;
    private final int receiveBufferSize;
    private final int asyncQueueCapacity;
    private final OverflowPolicy overflowPolicy;
    private final boolean tcpNoDelay;
    private final boolean keepAlive;
    private final ReconnectConfig reconnect;

    private ClientConfig(Builder b) {
        connectTimeout=b.connectTimeout; responseTimeout=b.responseTimeout; socketReadTimeoutMillis=b.socketReadTimeoutMillis;
        receiveBufferSize=b.receiveBufferSize; asyncQueueCapacity=b.asyncQueueCapacity; overflowPolicy=b.overflowPolicy;
        tcpNoDelay=b.tcpNoDelay; keepAlive=b.keepAlive; reconnect=b.reconnect;
    }
    public static Builder builder() { return new Builder(); }
    public static ClientConfig defaults() { return builder().build(); }
    public Duration connectTimeout(){return connectTimeout;}
    public Duration responseTimeout(){return responseTimeout;}
    public int socketReadTimeoutMillis(){return socketReadTimeoutMillis;}
    public int receiveBufferSize(){return receiveBufferSize;}
    public int asyncQueueCapacity(){return asyncQueueCapacity;}
    public OverflowPolicy overflowPolicy(){return overflowPolicy;}
    public boolean tcpNoDelay(){return tcpNoDelay;}
    public boolean keepAlive(){return keepAlive;}
    public ReconnectConfig reconnect(){return reconnect;}

    public enum OverflowPolicy { DROP_OLDEST, DROP_NEWEST, BLOCK, FAIL }

    public static final class Builder {
        private Duration connectTimeout=Duration.ofSeconds(5), responseTimeout=Duration.ofSeconds(10);
        private int socketReadTimeoutMillis=0, receiveBufferSize=8192, asyncQueueCapacity=256;
        private OverflowPolicy overflowPolicy=OverflowPolicy.DROP_OLDEST;
        private boolean tcpNoDelay=true, keepAlive=true;
        private ReconnectConfig reconnect=ReconnectConfig.defaults();
        public Builder connectTimeout(Duration v){connectTimeout=Objects.requireNonNull(v);return this;}
        public Builder responseTimeout(Duration v){responseTimeout=Objects.requireNonNull(v);return this;}
        public Builder socketReadTimeoutMillis(int v){if(v<0)throw new IllegalArgumentException();socketReadTimeoutMillis=v;return this;}
        public Builder receiveBufferSize(int v){if(v<=0)throw new IllegalArgumentException();receiveBufferSize=v;return this;}
        public Builder asyncQueueCapacity(int v){if(v<=0)throw new IllegalArgumentException();asyncQueueCapacity=v;return this;}
        public Builder overflowPolicy(OverflowPolicy v){overflowPolicy=Objects.requireNonNull(v);return this;}
        public Builder tcpNoDelay(boolean v){tcpNoDelay=v;return this;}
        public Builder keepAlive(boolean v){keepAlive=v;return this;}
        public Builder reconnect(ReconnectConfig v){reconnect=Objects.requireNonNull(v);return this;}
        public ClientConfig build(){return new ClientConfig(this);}
    }
}

```

## `src/main/java/com/example/instrument/config/ReconnectConfig.java`

```java
package com.example.instrument.config;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public record ReconnectConfig(boolean enabled, int maxAttempts, Duration initialDelay,
                              Duration maxDelay, double jitterRatio) {
    public ReconnectConfig {
        Objects.requireNonNull(initialDelay); Objects.requireNonNull(maxDelay);
        if (maxAttempts < 0) throw new IllegalArgumentException("maxAttempts < 0");
        if (initialDelay.isNegative() || maxDelay.isNegative() || maxDelay.compareTo(initialDelay) < 0)
            throw new IllegalArgumentException("invalid reconnect delay");
        if (jitterRatio < 0 || jitterRatio > 1) throw new IllegalArgumentException("jitterRatio must be 0..1");
    }
    public static ReconnectConfig defaults() {
        return new ReconnectConfig(true, 3, Duration.ofSeconds(1), Duration.ofSeconds(30), 0.20);
    }
    public Duration delayForAttempt(int attempt) {
        if (attempt <= 0) return Duration.ZERO;
        long base = initialDelay.toMillis();
        long max = maxDelay.toMillis();
        long value = base;
        for (int i = 1; i < attempt && value < max; i++) value = Math.min(max, Math.max(1, value * 2));
        if (jitterRatio == 0 || value == 0) return Duration.ofMillis(value);
        long spread = Math.max(1, (long) (value * jitterRatio));
        long jittered = value - spread + ThreadLocalRandom.current().nextLong(spread * 2 + 1);
        return Duration.ofMillis(Math.max(0, Math.min(max, jittered)));
    }
}

```

## `src/main/java/com/example/instrument/connection/Connection.java`

```java
package com.example.instrument.connection;

import java.io.IOException;

public interface Connection extends AutoCloseable {
    void connect();
    int read(byte[] buffer) throws IOException;
    void write(byte[] data) throws IOException;
    boolean isConnected();
    ConnectionState state();
    @Override void close();
}

```

## `src/main/java/com/example/instrument/connection/ConnectionManager.java`

```java
package com.example.instrument.connection;

import com.example.instrument.api.InstrumentClient;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.core.InstrumentClientImpl;
import com.example.instrument.protocol.Protocol;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ConnectionManager implements AutoCloseable {
    private final Map<UUID,InstrumentClient> clients=new ConcurrentHashMap<>();
    public UUID add(InetSocketAddress address,Protocol protocol,ClientConfig config){UUID id=UUID.randomUUID();clients.put(id,new InstrumentClientImpl(address,protocol,config));return id;}
    public InstrumentClient get(UUID id){InstrumentClient c=clients.get(id);if(c==null)throw new NoSuchElementException("client not found: "+id);return c;}
    public Optional<InstrumentClient> find(UUID id){return Optional.ofNullable(clients.get(id));}
    public void remove(UUID id){InstrumentClient c=clients.remove(id);if(c!=null)c.close();}
    public Set<UUID> ids(){return Set.copyOf(clients.keySet());}
    @Override public void close(){clients.values().forEach(InstrumentClient::close);clients.clear();}
}

```

## `src/main/java/com/example/instrument/connection/ConnectionState.java`

```java
package com.example.instrument.connection;

public enum ConnectionState { NEW, CONNECTING, CONNECTED, DISCONNECTED, RECONNECTING, CLOSING, CLOSED }

```

## `src/main/java/com/example/instrument/connection/ReconnectPolicy.java`

```java
package com.example.instrument.connection;

import com.example.instrument.config.ReconnectConfig;
import java.time.Duration;
import java.util.Objects;

public final class ReconnectPolicy {
    private final ReconnectConfig config;
    public ReconnectPolicy(ReconnectConfig config){this.config=Objects.requireNonNull(config);}
    public boolean enabled(){return config.enabled() && config.maxAttempts()>0;}
    public boolean canAttempt(int attempt){return enabled() && attempt<=config.maxAttempts();}
    public Duration delay(int attempt){return config.delayForAttempt(attempt);}
    public int maxAttempts(){return config.maxAttempts();}
}

```

## `src/main/java/com/example/instrument/connection/TcpConnection.java`

```java
package com.example.instrument.connection;

import com.example.instrument.config.ClientConfig;
import com.example.instrument.exception.ConnectionException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public final class TcpConnection implements Connection {
    private final InetSocketAddress address; private final ClientConfig config; private final ReentrantLock lifecycleLock=new ReentrantLock();
    private volatile Socket socket; private volatile InputStream input; private volatile OutputStream output; private volatile ConnectionState state=ConnectionState.NEW;
    public TcpConnection(InetSocketAddress address,ClientConfig config){this.address=Objects.requireNonNull(address);this.config=Objects.requireNonNull(config);}
    @Override public void connect(){
        lifecycleLock.lock();
        try {
            if(state==ConnectionState.CLOSED)throw new ConnectionException("connection is closed");
            if(state==ConnectionState.CONNECTED)return;
            state=ConnectionState.CONNECTING;
            Socket s=new Socket();
            try{
                s.setTcpNoDelay(config.tcpNoDelay()); s.setKeepAlive(config.keepAlive());
                s.setReceiveBufferSize(config.receiveBufferSize()); s.setSoTimeout(config.socketReadTimeoutMillis());
                s.connect(address,(int)Math.min(Integer.MAX_VALUE,config.connectTimeout().toMillis()));
                InputStream in=s.getInputStream(); OutputStream out=s.getOutputStream();
                socket=s;input=in;output=out;state=ConnectionState.CONNECTED;
            }catch(IOException e){try{s.close();}catch(IOException ignored){} state=ConnectionState.DISCONNECTED;throw new ConnectionException("connect failed: "+address,e);}
        } finally {lifecycleLock.unlock();}
    }
    @Override public int read(byte[] buffer)throws IOException{
        InputStream in=input; if(in==null||!isConnected())throw new IOException("not connected"); return in.read(buffer);
    }
    @Override public void write(byte[] data)throws IOException{
        OutputStream out=output; if(out==null||!isConnected())throw new IOException("not connected"); out.write(data);out.flush();
    }
    @Override public boolean isConnected(){Socket s=socket;return state==ConnectionState.CONNECTED&&s!=null&&s.isConnected()&&!s.isClosed();}
    @Override public ConnectionState state(){return state;}
    @Override public void close(){
        lifecycleLock.lock();
        try{if(state==ConnectionState.CLOSED)return;state=ConnectionState.CLOSING;closeQuietly(socket);socket=null;input=null;output=null;state=ConnectionState.CLOSED;}
        finally{lifecycleLock.unlock();}
    }
    public void disconnect(){
        lifecycleLock.lock();
        try{if(state==ConnectionState.CLOSED)return;closeQuietly(socket);socket=null;input=null;output=null;state=ConnectionState.DISCONNECTED;}
        finally{lifecycleLock.unlock();}
    }
    private static void closeQuietly(Socket s){if(s!=null)try{s.close();}catch(IOException ignored){}}
    @Override public String toString(){return "TcpConnection["+address+"]";}
}

```

## `src/main/java/com/example/instrument/core/InstrumentClientImpl.java`

```java
package com.example.instrument.core;

import com.example.instrument.api.*;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.connection.*;
import com.example.instrument.exception.ConnectionException;
import com.example.instrument.exception.RequestTimeoutException;
import com.example.instrument.model.*;
import com.example.instrument.metrics.ClientMetrics;
import com.example.instrument.protocol.*;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

public final class InstrumentClientImpl implements InstrumentClient {
    private final InetSocketAddress address; private final Protocol protocol; private final ClientConfig config;
    private final TcpConnection connection; private final ProtocolEncoder encoder; private final ProtocolDecoder decoder;
    private final RequestManager requestManager=new RequestManager(); private final ClientMetrics metrics=new ClientMetrics();
    private final ResponseDispatcher dispatcher; private final ReconnectPolicy reconnectPolicy;
    private final ReentrantLock requestLock=new ReentrantLock(true); private final ReentrantLock lifecycleLock=new ReentrantLock(true);
    private final ExecutorService requestExecutor=Executors.newSingleThreadExecutor(r->new Thread(r,"instrument-request"));
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"instrument-reconnect"));
    private final CopyOnWriteArrayList<ConnectionListener> connectionListeners=new CopyOnWriteArrayList<>();
    private final AtomicBoolean reconnectScheduled=new AtomicBoolean();
    private volatile Receiver receiver; private volatile Thread receiverThread; private volatile boolean closed; private volatile boolean reconnectSuppressed=true;
    private volatile int reconnectAttempt;

    public InstrumentClientImpl(InetSocketAddress address,Protocol protocol,ClientConfig config){
        this.address=Objects.requireNonNull(address);this.protocol=Objects.requireNonNull(protocol);this.config=Objects.requireNonNull(config);
        this.connection=new TcpConnection(address,config);this.encoder=protocol.newEncoder();this.decoder=protocol.newDecoder();this.dispatcher=new ResponseDispatcher(requestManager,config,metrics);this.reconnectPolicy=new ReconnectPolicy(config.reconnect());
    }

    @Override public void connect(){
        lifecycleLock.lock();
        try{ensureNotClosed(); reconnectSuppressed=false; if(connection.isConnected())return; connection.connect(); decoder.reset(); startReceiver(); reconnectAttempt=0; notifyConnected();}
        finally{lifecycleLock.unlock();}
    }

    private void startReceiver(){
        Receiver old=receiver;if(old!=null)old.stop();
        Receiver r=new Receiver(connection,decoder,dispatcher,config.receiveBufferSize(),this::onReceiverFailure);receiver=r;
        Thread t=new Thread(r,"instrument-receiver");t.setDaemon(true);receiverThread=t;t.start();
    }

    private void onReceiverFailure(Throwable cause){
        if(closed)return;
        lifecycleLock.lock();
        try{
            if(!connection.isConnected()){}
            requestManager.fail(new ConnectionException("connection lost: "+address,cause));
            notifyDisconnected(cause);
            connection.disconnect();
            if(!reconnectSuppressed)scheduleReconnect(cause);
        }finally{lifecycleLock.unlock();}
    }

    private void scheduleReconnect(Throwable cause){
        if(!reconnectPolicy.enabled()||reconnectScheduled.getAndSet(true))return;
        reconnectAttempt=0; scheduleNextReconnect(cause);
    }
    private void scheduleNextReconnect(Throwable cause){
        int attempt=++reconnectAttempt;
        if(!reconnectPolicy.canAttempt(attempt)){reconnectScheduled.set(false);notifyReconnectFailed(cause);return;}
        Duration delay=reconnectPolicy.delay(attempt);notifyReconnecting(attempt,delay,cause);
        scheduler.schedule(()->{
            if(closed||reconnectSuppressed){reconnectScheduled.set(false);return;}
            try{connect();reconnectScheduled.set(false);}
            catch(Throwable e){scheduleNextReconnect(e);}
        },delay.toMillis(),TimeUnit.MILLISECONDS);
    }

    @Override public void disconnect(){
        lifecycleLock.lock();
        try{if(closed)return;reconnectSuppressed=true;reconnectScheduled.set(false);Receiver r=receiver;if(r!=null)r.stop();requestManager.fail(new ConnectionException("client disconnected"));connection.disconnect();decoder.reset();notifyDisconnected(null);}
        finally{lifecycleLock.unlock();}
    }

    @Override public boolean isConnected(){return connection.isConnected();}

    @Override public Response request(Command command,ResponseMatcher matcher,Duration timeout,CommandIdempotency idempotency){
        Objects.requireNonNull(command);Objects.requireNonNull(matcher);Objects.requireNonNull(timeout);Objects.requireNonNull(idempotency);
        Request request=new Request(command,matcher,timeout,idempotency);int attempts=0;
        while(true){
            try{return executeOnce(request);}
            catch(ConnectionException e){
                if(request.idempotency()!=CommandIdempotency.IDEMPOTENT || attempts>=reconnectPolicy.maxAttempts())throw e;
                attempts++; reconnectForRetry();
            }
        }
    }

    private Response executeOnce(Request request){
        requestLock.lock();
        PendingRequest pending=null;
        try{
            if(requestManager.hasPending())throw new IllegalStateException("another request is pending");
            if(!isConnected()) connect();
            pending=requestManager.register(request.matcher());
            try{connection.write(encoder.encode(request.command()));metrics.sent(request.command().length());}
            catch(Exception e){requestManager.remove(pending);onReceiverFailure(e);throw new ConnectionException("write failed: "+address,e);}
            try{return pending.future().get(request.timeout().toNanos(),TimeUnit.NANOSECONDS);}
            catch(TimeoutException e){requestManager.remove(pending);throw new RequestTimeoutException("request timeout after "+request.timeout()+": "+request.command());}
            catch(InterruptedException e){Thread.currentThread().interrupt();requestManager.remove(pending);throw new ConnectionException("request interrupted",e);}
            catch(ExecutionException e){requestManager.remove(pending);Throwable c=e.getCause();if(c instanceof ConnectionException ce)throw ce;throw new ConnectionException("request failed",c);}
        } finally {requestLock.unlock();}
    }

    private void reconnectForRetry(){
        lifecycleLock.lock();
        try{if(closed)throw new ConnectionException("client is closed");reconnectSuppressed=false;connection.disconnect();decoder.reset();connection.connect();startReceiver();notifyConnected();}
        catch(ConnectionException e){throw e;} finally{lifecycleLock.unlock();}
    }

    @Override public CompletableFuture<Response> requestAsync(Command command,ResponseMatcher matcher,Duration timeout,CommandIdempotency idempotency){
        return CompletableFuture.supplyAsync(()->request(command,matcher,timeout,idempotency),requestExecutor);
    }
    @Override public void send(Command command){
        requestLock.lock();
        try{if(requestManager.hasPending())throw new IllegalStateException("cannot send while a request is pending");if(!isConnected())connect();try{connection.write(encoder.encode(command));metrics.sent(command.length());}catch(Exception e){onReceiverFailure(e);throw new ConnectionException("write failed: "+address,e);}}
        finally{requestLock.unlock();}
    }
    @Override public void addDataListener(DataListener listener){dispatcher.addListener(listener);}
    @Override public void removeDataListener(DataListener listener){dispatcher.removeListener(listener);}
    @Override public void addConnectionListener(ConnectionListener listener){connectionListeners.add(Objects.requireNonNull(listener));}
    @Override public void removeConnectionListener(ConnectionListener listener){connectionListeners.remove(listener);}
    @Override public BlockingDataListener blockingDataListener(){return new BlockingDataListener(dispatcher.queue());}
    public ClientMetrics.Snapshot metrics(){return metrics.snapshot();}

    private void ensureNotClosed(){if(closed)throw new ConnectionException("client is closed");}
    private void notifyConnected(){for(var l:connectionListeners)try{l.onConnected();}catch(Throwable ignored){}}
    private void notifyDisconnected(Throwable cause){for(var l:connectionListeners)try{l.onDisconnected(cause);}catch(Throwable ignored){}}
    private void notifyReconnecting(int a,Duration d,Throwable c){for(var l:connectionListeners)try{l.onReconnecting(a,d,c);}catch(Throwable ignored){}}
    private void notifyReconnectFailed(Throwable c){for(var l:connectionListeners)try{l.onReconnectFailed(c);}catch(Throwable ignored){}}

    @Override public void close(){
        lifecycleLock.lock();
        try{if(closed)return;closed=true;reconnectSuppressed=true;reconnectScheduled.set(false);Receiver r=receiver;if(r!=null)r.stop();requestManager.fail(new ConnectionException("client closed"));connection.close();decoder.reset();}
        finally{lifecycleLock.unlock();}
        requestExecutor.shutdownNow();scheduler.shutdownNow();
    }
}

```

## `src/main/java/com/example/instrument/core/PendingRequest.java`

```java
package com.example.instrument.core;

import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.model.Response;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

final class PendingRequest {
    private final ResponseMatcher matcher;
    private final CompletableFuture<Response> future=new CompletableFuture<>();
    PendingRequest(ResponseMatcher matcher){this.matcher=Objects.requireNonNull(matcher);}
    boolean tryComplete(Response response){
        if(matcher.matches(response)) return future.complete(response);
        return false;
    }
    CompletableFuture<Response> future(){return future;}
}

```

## `src/main/java/com/example/instrument/core/Receiver.java`

```java
package com.example.instrument.core;

import com.example.instrument.connection.Connection;
import com.example.instrument.protocol.ProtocolDecoder;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class Receiver implements Runnable {
    private final Connection connection; private final ProtocolDecoder decoder; private final ResponseDispatcher dispatcher;
    private final int bufferSize; private final Consumer<Throwable> errorHandler;
    private volatile boolean running;
    Receiver(Connection connection,ProtocolDecoder decoder,ResponseDispatcher dispatcher,int bufferSize,Consumer<Throwable> errorHandler){
        this.connection=Objects.requireNonNull(connection);this.decoder=Objects.requireNonNull(decoder);this.dispatcher=Objects.requireNonNull(dispatcher);this.bufferSize=bufferSize;this.errorHandler=Objects.requireNonNull(errorHandler);
    }
    void stop(){running=false;}
    @Override public void run(){
        running=true;byte[] buffer=new byte[bufferSize];
        try{
            while(running && connection.isConnected()){
                int n=connection.read(buffer);
                if(n<0)throw new IOException("remote peer closed connection");
                if(n==0)continue;
                List<com.example.instrument.model.Response> frames=decoder.decode(buffer,0,n);
                for(var frame:frames)dispatcher.dispatch(frame);
            }
        }catch(Throwable t){if(running)errorHandler.accept(t);}finally{running=false;}
    }
}

```

## `src/main/java/com/example/instrument/core/RequestManager.java`

```java
package com.example.instrument.core;

import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.model.Response;
import java.util.concurrent.atomic.AtomicReference;

final class RequestManager {
    private final AtomicReference<PendingRequest> pending=new AtomicReference<>();
    PendingRequest register(ResponseMatcher matcher){
        PendingRequest request=new PendingRequest(matcher);
        if(!pending.compareAndSet(null,request))throw new IllegalStateException("another request is already pending");
        return request;
    }
    boolean dispatch(Response response){
        PendingRequest request=pending.get();
        if(request==null)return false;
        if(!request.tryComplete(response))return false;
        pending.compareAndSet(request,null); return true;
    }
    void remove(PendingRequest request){pending.compareAndSet(request,null);}
    void fail(Throwable cause){PendingRequest r=pending.getAndSet(null);if(r!=null)r.future().completeExceptionally(cause);}
    boolean hasPending(){return pending.get()!=null;}
}

```

## `src/main/java/com/example/instrument/core/ResponseDispatcher.java`

```java
package com.example.instrument.core;

import com.example.instrument.api.DataListener;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.metrics.ClientMetrics;
import com.example.instrument.model.Response;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.BlockingQueue;

final class ResponseDispatcher {
    private final RequestManager requestManager; private final ClientConfig.OverflowPolicy overflowPolicy;
    private final BlockingQueue<Response> asyncQueue; private final CopyOnWriteArrayList<DataListener> listeners=new CopyOnWriteArrayList<>();
    private final ClientMetrics metrics;
    ResponseDispatcher(RequestManager requestManager,ClientConfig config,ClientMetrics metrics){
        this.requestManager=Objects.requireNonNull(requestManager);overflowPolicy=config.overflowPolicy();asyncQueue=new ArrayBlockingQueue<>(config.asyncQueueCapacity());this.metrics=metrics;
    }
    void dispatch(Response response){
        if(requestManager.dispatch(response)){metrics.receivedMatched();return;}
        metrics.receivedAsync(); offer(response); for(DataListener listener:listeners){try{listener.onData(response);}catch(Throwable t){metrics.listenerErrors();}}
    }
    private void offer(Response response){
        switch(overflowPolicy){
            case DROP_NEWEST -> {if(!asyncQueue.offer(response))metrics.droppedAsync();}
            case DROP_OLDEST -> {if(!asyncQueue.offer(response)){asyncQueue.poll();if(!asyncQueue.offer(response))metrics.droppedAsync();}}
            case BLOCK -> {try{asyncQueue.put(response);}catch(InterruptedException e){Thread.currentThread().interrupt();metrics.droppedAsync();}}
            case FAIL -> {if(!asyncQueue.offer(response))throw new IllegalStateException("async response queue is full");}
        }
    }
    void addListener(DataListener listener){listeners.add(Objects.requireNonNull(listener));}
    void removeListener(DataListener listener){listeners.remove(listener);}
    BlockingQueue<Response> queue(){return asyncQueue;}
    void clearQueue(){asyncQueue.clear();}
}

```

## `src/main/java/com/example/instrument/exception/ConfigurationException.java`

```java
package com.example.instrument.exception;

public class ConfigurationException extends InstrumentException {
    public ConfigurationException(String message) { super(message); }
}

```

## `src/main/java/com/example/instrument/exception/ConnectionException.java`

```java
package com.example.instrument.exception;

public class ConnectionException extends InstrumentException {
    public ConnectionException(String message) { super(message); }
    public ConnectionException(String message, Throwable cause) { super(message, cause); }
}

```

## `src/main/java/com/example/instrument/exception/InstrumentException.java`

```java
package com.example.instrument.exception;

public class InstrumentException extends RuntimeException {
    public InstrumentException(String message) { super(message); }
    public InstrumentException(String message, Throwable cause) { super(message, cause); }
}

```

## `src/main/java/com/example/instrument/exception/ProtocolException.java`

```java
package com.example.instrument.exception;

public class ProtocolException extends InstrumentException {
    public ProtocolException(String message) { super(message); }
    public ProtocolException(String message, Throwable cause) { super(message, cause); }
}

```

## `src/main/java/com/example/instrument/exception/RequestCancelledException.java`

```java
package com.example.instrument.exception;

public class RequestCancelledException extends InstrumentException {
    public RequestCancelledException(String message) { super(message); }
}

```

## `src/main/java/com/example/instrument/exception/RequestTimeoutException.java`

```java
package com.example.instrument.exception;

public class RequestTimeoutException extends InstrumentException {
    public RequestTimeoutException(String message) { super(message); }
}

```

## `src/main/java/com/example/instrument/metrics/ClientMetrics.java`

```java
package com.example.instrument.metrics;

import java.util.concurrent.atomic.LongAdder;

public final class ClientMetrics {
    private final LongAdder sentFrames=new LongAdder(), sentBytes=new LongAdder(), receivedFrames=new LongAdder(), matchedFrames=new LongAdder(), asyncFrames=new LongAdder(), droppedAsync=new LongAdder(), listenerErrors=new LongAdder(), reconnects=new LongAdder(), timeouts=new LongAdder();
    public void sent(long bytes){sentFrames.increment();sentBytes.add(bytes);}
    public void receivedMatched(){receivedFrames.increment();matchedFrames.increment();}
    public void receivedAsync(){receivedFrames.increment();asyncFrames.increment();}
    public void droppedAsync(){droppedAsync.increment();}
    public void listenerErrors(){listenerErrors.increment();}
    public void reconnect(){reconnects.increment();}
    public void timeout(){timeouts.increment();}
    public Snapshot snapshot(){return new Snapshot(sentFrames.sum(),sentBytes.sum(),receivedFrames.sum(),matchedFrames.sum(),asyncFrames.sum(),droppedAsync.sum(),listenerErrors.sum(),reconnects.sum(),timeouts.sum());}
    public record Snapshot(long sentFrames,long sentBytes,long receivedFrames,long matchedFrames,long asyncFrames,long droppedAsync,long listenerErrors,long reconnects,long timeouts){}
}

```

## `src/main/java/com/example/instrument/model/Command.java`

```java
package com.example.instrument.model;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

public final class Command {
    private final byte[] payload;

    private Command(byte[] payload) {
        this.payload = payload.clone();
    }

    public static Command of(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        return new Command(payload);
    }

    public static Command text(String text) {
        return text(text, StandardCharsets.UTF_8);
    }

    public static Command text(String text, Charset charset) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(charset, "charset");
        return new Command(text.getBytes(charset));
    }

    public byte[] bytes() { return payload.clone(); }
    public int length() { return payload.length; }

    @Override public String toString() { return "Command[length=" + payload.length + "]"; }
    @Override public boolean equals(Object o) {
        return o instanceof Command c && Arrays.equals(payload, c.payload);
    }
    @Override public int hashCode() { return Arrays.hashCode(payload); }
}

```

## `src/main/java/com/example/instrument/model/CommandIdempotency.java`

```java
package com.example.instrument.model;

public enum CommandIdempotency {
    IDEMPOTENT,
    NON_IDEMPOTENT,
    UNKNOWN
}

```

## `src/main/java/com/example/instrument/model/Request.java`

```java
package com.example.instrument.model;

import com.example.instrument.api.ResponseMatcher;
import java.time.Duration;
import java.util.Objects;

public record Request(Command command, ResponseMatcher matcher, Duration timeout,
                      CommandIdempotency idempotency) {
    public Request {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(matcher, "matcher");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(idempotency, "idempotency");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be > 0");
    }
}

```

## `src/main/java/com/example/instrument/model/Response.java`

```java
package com.example.instrument.model;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

public final class Response {
    private final byte[] frame;
    private final byte[] body;
    private final long receivedAtNanos;

    public Response(byte[] frame, byte[] body) {
        this(frame, body, System.nanoTime());
    }

    public Response(byte[] frame, byte[] body, long receivedAtNanos) {
        this.frame = Objects.requireNonNull(frame).clone();
        this.body = Objects.requireNonNull(body).clone();
        this.receivedAtNanos = receivedAtNanos;
    }

    public byte[] bytes() { return frame.clone(); }
    public byte[] body() { return body.clone(); }
    public int length() { return frame.length; }
    public long receivedAtNanos() { return receivedAtNanos; }
    public String text() { return text(StandardCharsets.UTF_8); }
    public String text(Charset charset) { return new String(body, Objects.requireNonNull(charset)); }

    @Override public String toString() { return "Response[length=" + frame.length + ", bodyLength=" + body.length + "]"; }
    @Override public boolean equals(Object o) {
        return o instanceof Response r && Arrays.equals(frame, r.frame) && Arrays.equals(body, r.body);
    }
    @Override public int hashCode() { return 31 * Arrays.hashCode(frame) + Arrays.hashCode(body); }
}

```

## `src/main/java/com/example/instrument/protocol/LengthFieldProtocol.java`

```java
package com.example.instrument.protocol;

import com.example.instrument.exception.ProtocolException;
import com.example.instrument.model.Command;
import com.example.instrument.model.Response;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class LengthFieldProtocol implements Protocol {
    private final Charset charset; private final int maxPayloadLength;
    public LengthFieldProtocol(Charset charset,int maxPayloadLength){
        this.charset=Objects.requireNonNull(charset); if(maxPayloadLength<0||maxPayloadLength>65535)throw new IllegalArgumentException();this.maxPayloadLength=maxPayloadLength;
    }
    public static LengthFieldProtocol defaults(Charset charset){return new LengthFieldProtocol(charset,65535);}
    @Override public Charset charset(){return charset;}
    @Override public ProtocolEncoder newEncoder(){
        return command -> {
            byte[] payload=command.bytes(); if(payload.length>maxPayloadLength)throw new ProtocolException("payload too large: "+payload.length);
            byte[] out=new byte[payload.length+4]; out[0]=(byte)0xAA; out[1]=(byte)(payload.length>>>8); out[2]=(byte)payload.length;
            System.arraycopy(payload,0,out,3,payload.length); out[out.length-1]=checksum(out,0,out.length-1); return out;
        };
    }
    @Override public ProtocolDecoder newDecoder(){return new Decoder(maxPayloadLength);}
    private static byte checksum(byte[] b,int off,int len){byte x=0;for(int i=off;i<off+len;i++)x^=b[i];return x;}

    private static final class Decoder implements ProtocolDecoder {
        private final int max; private final ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        Decoder(int max){this.max=max;}
        @Override public synchronized List<Response> decode(byte[] data,int offset,int length){
            if(length==0)return List.of(); buffer.write(data,offset,length); List<Response> out=new ArrayList<>();
            while(true){
                byte[] b=buffer.toByteArray(); if(b.length<4)break;
                int stx=indexOfStx(b); if(stx<0){buffer.reset();break;}
                if(stx>0){buffer.reset();buffer.write(b,stx,b.length-stx);b=buffer.toByteArray();if(b.length<4)break;}
                int payloadLen=((b[1]&0xff)<<8)|(b[2]&0xff);
                if(payloadLen>max){discardOne();continue;}
                int total=4+payloadLen; if(b.length<total)break;
                byte expected=checksum(b,0,total-1); byte actual=b[total-1];
                if(expected!=actual){discardOne();continue;}
                byte[] frame=Arrays.copyOfRange(b,0,total); byte[] body=Arrays.copyOfRange(b,3,total-1); out.add(new Response(frame,body));
                buffer.reset(); if(b.length>total)buffer.write(b,total,b.length-total);
            }
            if(buffer.size()>max+4)throw new ProtocolException("binary buffer exceeds configured maximum");
            return out;
        }
        private int indexOfStx(byte[] b){for(int i=0;i<b.length;i++)if((b[i]&0xff)==0xAA)return i;return -1;}
        private void discardOne(){byte[] b=buffer.toByteArray();buffer.reset();if(b.length>1)buffer.write(b,1,b.length-1);}
        @Override public synchronized void reset(){buffer.reset();}
    }
}

```

## `src/main/java/com/example/instrument/protocol/LineProtocol.java`

```java
package com.example.instrument.protocol;

import com.example.instrument.exception.ProtocolException;
import com.example.instrument.model.Command;
import com.example.instrument.model.Response;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class LineProtocol implements Protocol {
    private final byte[] delimiter;
    private final Charset charset;
    private final int maxFrameLength;

    private LineProtocol(byte[] delimiter, Charset charset, int maxFrameLength) {
        this.delimiter=delimiter.clone(); this.charset=Objects.requireNonNull(charset); this.maxFrameLength=maxFrameLength;
        if (delimiter.length == 0) throw new IllegalArgumentException("delimiter is empty");
        if (maxFrameLength <= delimiter.length) throw new IllegalArgumentException("maxFrameLength too small");
    }
    public static LineProtocol crlf(Charset charset) { return new LineProtocol(new byte[]{'\r','\n'}, charset, 1024 * 1024); }
    public static LineProtocol lf(Charset charset) { return new LineProtocol(new byte[]{'\n'}, charset, 1024 * 1024); }
    public LineProtocol withMaxFrameLength(int max) { return new LineProtocol(delimiter, charset, max); }
    @Override public Charset charset(){return charset;}
    @Override public ProtocolEncoder newEncoder() {
        return command -> {
            byte[] body=command.bytes();
            byte[] out=Arrays.copyOf(body, body.length + delimiter.length);
            System.arraycopy(delimiter,0,out,body.length,delimiter.length);
            return out;
        };
    }
    @Override public ProtocolDecoder newDecoder() { return new Decoder(delimiter, maxFrameLength); }

    private static final class Decoder implements ProtocolDecoder {
        private final byte[] delimiter; private final int max; private final ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        Decoder(byte[] delimiter,int max){this.delimiter=delimiter.clone();this.max=max;}
        @Override public synchronized List<Response> decode(byte[] data,int offset,int length){
            if(length==0)return List.of();
            if(offset<0||length<0||offset+length>data.length)throw new IndexOutOfBoundsException();
            buffer.write(data,offset,length);
            if(buffer.size()>max+delimiter.length) throw new ProtocolException("line frame exceeds max length: "+max);
            byte[] bytes=buffer.toByteArray(); List<Response> out=new ArrayList<>(); int start=0;
            while(true){
                int pos=indexOf(bytes,delimiter,start); if(pos<0)break;
                int frameEnd=pos+delimiter.length; int bodyLen=pos-start;
                byte[] frame=Arrays.copyOfRange(bytes,start,frameEnd);
                byte[] body=Arrays.copyOfRange(bytes,start,pos);
                out.add(new Response(frame,body)); start=frameEnd;
            }
            if(start>0){buffer.reset();buffer.write(bytes,start,bytes.length-start);}
            if(buffer.size()>max) throw new ProtocolException("line frame exceeds max length: "+max);
            return out;
        }
        private static int indexOf(byte[] data,byte[] target,int from){
            outer: for(int i=from;i<=data.length-target.length;i++){for(int j=0;j<target.length;j++)if(data[i+j]!=target[j])continue outer;return i;} return -1;
        }
        @Override public synchronized void reset(){buffer.reset();}
    }
}

```

## `src/main/java/com/example/instrument/protocol/Protocol.java`

```java
package com.example.instrument.protocol;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public interface Protocol {
    ProtocolEncoder newEncoder();
    ProtocolDecoder newDecoder();
    default Charset charset() { return StandardCharsets.UTF_8; }
}

```

## `src/main/java/com/example/instrument/protocol/ProtocolDecoder.java`

```java
package com.example.instrument.protocol;

import com.example.instrument.model.Response;
import java.util.List;

public interface ProtocolDecoder {
    List<Response> decode(byte[] data, int offset, int length);
    void reset();
}

```

## `src/main/java/com/example/instrument/protocol/ProtocolEncoder.java`

```java
package com.example.instrument.protocol;

import com.example.instrument.model.Command;

@FunctionalInterface
public interface ProtocolEncoder {
    byte[] encode(Command command);
}

```

## `src/test/java/com/example/instrument/core/RequestManagerTest.java`

```java
package com.example.instrument.core;

import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.model.Response;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestManagerTest {
    @Test void onlyMatchingResponseCompletesRequest(){
        RequestManager m=new RequestManager();PendingRequest p=m.register(ResponseMatcher.equalsText("OK",StandardCharsets.UTF_8));
        assertFalse(m.dispatch(new Response("NO\r\n".getBytes(StandardCharsets.UTF_8),"NO".getBytes(StandardCharsets.UTF_8))));assertFalse(p.future().isDone());
        assertTrue(m.dispatch(new Response("OK\r\n".getBytes(StandardCharsets.UTF_8),"OK".getBytes(StandardCharsets.UTF_8))));assertEquals("OK",p.future().join().text(StandardCharsets.UTF_8));
    }
}

```

## `src/test/java/com/example/instrument/protocol/LengthFieldProtocolTest.java`

```java
package com.example.instrument.protocol;

import com.example.instrument.model.Command;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LengthFieldProtocolTest {
    @Test void roundTrip(){
        LengthFieldProtocol p=LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        byte[] frame=p.newEncoder().encode(Command.text("HELLO",StandardCharsets.UTF_8));
        var d=p.newDecoder(); var a=d.decode(frame,0,2); assertTrue(a.isEmpty()); var b=d.decode(frame,2,frame.length-2);
        assertEquals(1,b.size()); assertEquals("HELLO",b.get(0).text(StandardCharsets.UTF_8));
    }
    @Test void badChecksumIsResynchronized(){
        LengthFieldProtocol p=LengthFieldProtocol.defaults(StandardCharsets.UTF_8);byte[] f=p.newEncoder().encode(Command.text("A",StandardCharsets.UTF_8));f[f.length-1]^=1;
        var good=p.newEncoder().encode(Command.text("B",StandardCharsets.UTF_8));byte[] all=new byte[f.length+good.length];System.arraycopy(f,0,all,0,f.length);System.arraycopy(good,0,all,f.length,good.length);
        var result=p.newDecoder().decode(all,0,all.length);assertEquals(1,result.size());assertEquals("B",result.get(0).text(StandardCharsets.UTF_8));
    }
}

```

## `src/test/java/com/example/instrument/protocol/LineProtocolTest.java`

```java
package com.example.instrument.protocol;

import com.example.instrument.model.Command;
import com.example.instrument.model.Response;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LineProtocolTest {
    @Test void supportsFragmentationAndStickyPackets(){
        LineProtocol p=LineProtocol.crlf(StandardCharsets.UTF_8);
        ProtocolDecoder d=p.newDecoder();
        assertTrue(d.decode("HEL".getBytes(StandardCharsets.UTF_8),0,3).isEmpty());
        var frames=d.decode("LO\r\nWORLD\r\n".getBytes(StandardCharsets.UTF_8),0,"LO\r\nWORLD\r\n".getBytes(StandardCharsets.UTF_8).length);
        assertEquals(2,frames.size()); assertEquals("HELLO",frames.get(0).text(StandardCharsets.UTF_8)); assertEquals("WORLD",frames.get(1).text(StandardCharsets.UTF_8));
    }
    @Test void encoderAddsCrlf(){
        byte[] actual=LineProtocol.crlf(StandardCharsets.UTF_8).newEncoder().encode(Command.text("PING",StandardCharsets.UTF_8));
        assertArrayEquals(new byte[]{'P','I','N','G','\r','\n'},actual);
    }
}

```

