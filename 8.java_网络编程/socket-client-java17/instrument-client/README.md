# instrument-client

> 基于 Java 17 的 TCP 仪器通信客户端 —— SCPI / 二进制协议通吃，单连接 / 多连接自由切换，自动重连 + 正则响应匹配 + 双模式异步监听。

---

## 快速导航

- [项目定位](#项目定位)
- [四层架构](#四层架构)
- [目录结构](#目录结构)
- [核心亮点](#核心亮点)
- [快速开始](#快速开始)
- [用法示例](#用法示例)
- [协议说明](#协议说明)
- [TestServer 测试场景](#testserver-测试场景)
- [FAQ](#faq)

---

## 项目定位

用于与台式仪器、串口网关、工业设备等建立 TCP 连接，完成：

| 能力 | 说明 |
|------|------|
| **命令下发** | SCPI 文本 / 自定义二进制 / 任意字节载荷 |
| **同步等待** | `sendAndRegex(cmd, regex, timeout)` 发完命令自动阻塞到匹配响应 |
| **异步监听** | 回调模式 `addListener()` + 阻塞队列 `blockingListener()` 双模式 |
| **自动重连** | 断了自动恢复，写失败自动重连重试 |
| **粘包拆包** | 协议层内部跨帧缓存，天然解决 |
| **多连接管理** | `SocketConnectionManager` 一个 UUID 管一台仪器 |

---

## 四层架构

```
┌──────────────────────────────────────────────────────────┐
│  业务适配层    Demo / App                                  │  ← 为什么传？传完做什么？
│                选择 SocketClientITFImpl 直用 还是            │
│                SocketConnectionManager 多连接管理             │
├──────────────────────────────────────────────────────────┤
│  接口层 (api)  ClientITF / DataListener / ResponseMatcher  │  ← 契约：确保上层调用一致性
├──────────────────────────────────────────────────────────┤
│  协议层 (protocol)  Protocol 接口                          │  ← 语言：决定命令长什么样
│                     ├── LineProtocol (CRLF 文本 SCPI)       │
│                     └── LengthFieldProtocol (AA+LEN+CRC)  │
├──────────────────────────────────────────────────────────┤
│  通信层 (engine)  SocketClientITFImpl (核心引擎)             │  ← 运输：TCP 连接、信号量、重连
│                  SocketConnectionManager (多连接编排)       │
│                  SocketClientConfig / SocketClientException │
└──────────────────────────────────────────────────────────┘
```

### 层与层的依赖方向

```
业务层 → 接口层 → 协议层
                   ↓
                 通信层 ← 不依赖任何上层，可独立复用
```

---

## 目录结构

```
src/main/java/com/example/instrument/
├── Demo.java                          # 两种使用模式示例
├── TestServer.java                    # 多功能测试服务器
├── api/                               # 接口层（契约）
│   ├── ClientITF.java                 #   客户端核心接口
│   ├── DataListener.java              #   回调监听器
│   ├── BlockingDataListener.java      #   阻塞队列监听器
│   └── ResponseMatcher.java           #   响应匹配器 (Function<Response, Boolean>)
├── engine/                            # 通信层（引擎）
│   ├── SocketClientITFImpl.java       #   ★ 核心引擎：单连接管理
│   ├── SocketConnectionManager.java   #   ★ 多连接编排：UUID 标识
│   ├── SocketClientConfig.java        #   配置（超时、重连等）
│   └── SocketClientException.java     #   运行时异常
├── protocol/                          # 协议层（语言）
│   ├── Protocol.java                  #   协议接口
│   ├── LineProtocol.java              #   CRLF 文本（SCPI 专用）
│   └── LengthFieldProtocol.java       #   固定头+长度+CRC 二进制
└── model/                             # 数据模型
    ├── Command.java                   #   命令载荷（UTF-8 或原始 byte[]）
    └── Response.java                  #   协议解码后的响应（text + bytes 双重支持）
```

---

## 核心亮点

### 1. 异步接收 + 同步阻塞的完美结合

```
  发送线程                               接收线程
    │
    ├─ writePermit.acquire()          保证同一时刻只有一个发送
    ├─ clearBuffers()                 ★ 根治粘包：上一条命令的残余清掉
    ├─ ensureConnected()              ★ 自愈：断链自动重连
    ├─ write(frame) + flush
    ├─ readPermit.release()           通知接收线程"可以读了"  ──┐
    ├─ loop:                                                  │
    │   bufferLock → makeProbe()                             │
    │   matcher.matches()?                                   │
    │   yes → return                                         │
    │   no  → awaitNanos(dataArrived) ◄────────────────────┤
    │                                                        │
    │                     ┌─────────────────────────────────┘
    │                     ▼
    │                readPermit.acquire()
    │                in.read → appendToBuffers()
    │                protocol.decode → dispatch
    │                signalAll(dataArrived)  唤醒上面的 awaitNanos
    └─ writePermit.release()
```

### 2. 信号量协调时序（readPermit 为什么初始 0）

> 如果让接收线程持续读 socket，服务端空闲时主动推送的数据会被塞进 byteBuffer，
> 导致下一条命令的正则匹配**匹配到推送数据**（还没发命令就"有响应"了）。
>
> `readPermit` 初始 0 —— **只有发完命令才开始读**，完美隔离命令响应和推送数据。

### 3. 自动重连与状态自愈

- 每次 `sendAndMatch` 内部先调 `ensureConnected()` 检查 socket 状态
- 写失败 → `closeSocket()` → `ensureConnected()` 重建连接 → 再写一次（可配置）
- 应对网络抖动、服务端自动重启等场景，减少上层处理异常连接的成本

### 4. 双模式数据监听

| 模式 | API | 适合场景 |
|------|-----|----------|
| 回调模式 | `addListener(r -> ...)` | 事件驱动、不需要阻塞等待的场景 |
| 阻塞模式 | `blockingListener().take()` | 生产-消费、需要精确控制消费节奏的场景 |

### 5. 字符 + 字节双重支持

- `Response.text()` —— 文本协议直接读
- `Response.bytes()` —— 二进制协议读原始数据
- `Command.of("MEAS:VOLT?")` —— 字符串命令（UTF-8）
- `Command.of(byte[])` —— 二进制命令

### 6. 多连接管理（SocketConnectionManager）

用 UUID 标识每台仪器，一个管理器统一管理：

```java
try (var mgr = new SocketConnectionManager()) {
    UUID scope = mgr.createConnection("192.168.1.10", 5025, new LineProtocol());
    UUID power = mgr.createConnection("192.168.1.30", 9000, new LengthFieldProtocol());
    
    Response v = mgr.sendAndRegex(scope, "MEAS:VOLT?", "VOLT:[0-9.]+\\r\\n");
    mgr.sendAndRegex(power, "MEAS:POW?", "POW:[0-9.]+");
} // try-with-resources 自动 disconnectAll()
```

---

## 快速开始

### 1. 编译

```bash
cd instrument-client
mvn -q -DskipTests compile
```

### 2. 启动 TestServer（两个端口同时监听）

```bash
java -cp target/classes com.example.instrument.TestServer
```

启动后会监听：
- `127.0.0.1:5025` —— LineProtocol（CRLF 文本）
- `127.0.0.1:9000` —— LengthFieldProtocol（二进制帧）

### 3. 运行 Demo

新开一个终端：

```bash
java -cp target/classes com.example.instrument.Demo
```

Demo 会同时演示 SocketConnectionManager 多连接管理 + SocketClientITFImpl 单连接直用两种模式。

---

## 用法示例

### 模式 A：单连接直用 SocketClientITFImpl

适合简单脚本、单仪器测试工具。务必用 try-finally 保证 disconnect。

```java
var client = new SocketClientITFImpl(
    SocketClientConfig.defaults("192.168.1.10", 5025),
    new LineProtocol());

try {
    client.init();           // 启动后台接收线程
    client.connect();        // 建立 TCP 连接

    // 同步等待 + 正则匹配
    Response resp = client.sendAndRegex(
        "MEAS:VOLT?",                              // 命令
        "VOLT:[0-9.]+\\r\\n",                      // 期望响应格式
        Duration.ofSeconds(3));                    // 超时

    System.out.println(resp.text());   // "VOLT:1.234\r\n"
} finally {
    client.disconnect();    // 清理！不要漏
}
```

### 模式 B：SocketConnectionManager 多连接管理（推荐）

适合同时连多台仪器的场景。try-with-resources 自动管理生命周期。

```java
try (var mgr = new SocketConnectionManager()) {

    // ① 同时连三台仪器，各有各的协议
    UUID scope   = mgr.createConnection("192.168.1.10", 5025, new LineProtocol());
    UUID gen     = mgr.createConnection("192.168.1.20", 5025, new LineProtocol());
    UUID power   = mgr.createConnection("192.168.1.30", 9000, new LengthFieldProtocol());

    // ② 给每个连接注册独立监听器
    mgr.addListener(scope,   r -> System.out.println("[示波器] " + r.text()));
    mgr.addListener(gen,     r -> System.out.println("[信号源] " + r.text()));
    mgr.addListener(power,   r -> System.out.println("[功率计] bytes=" + r.bytes().length));

    // ③ 按 UUID 下发命令
    Response v = mgr.sendAndRegex(scope, "MEAS:VOLT?", "VOLT:[0-9.]+\\r\\n");
    mgr.sendAndRegex(gen,   "OUTP:ON", "OK\\r\\n");

    // ④ 查询 / 断开
    boolean alive = mgr.isConnected(scope);      // 还活着吗
    mgr.disconnect(gen);                         // 单独断开

} // ← 自动 disconnectAll()，清理所有连接
```

### 异步监听：两种模式

```java
// --- 回调模式 ---
client.addListener(response -> {
    String text = response.text();
    if (text.startsWith("ASYNC:")) {
        handleAsyncData(text);
    }
});

// --- 阻塞队列模式（适合消费者/生产者）---
BlockingDataListener queue = client.blockingListener();

// 在业务线程里消费
while (true) {
    Response r = queue.take();     // 阻塞等到有数据
    process(r);
}
// 或者限时轮询
Response r2 = queue.poll(5, TimeUnit.SECONDS);
if (r2 != null) process(r2);
```

---

## 协议说明

### LineProtocol —— CRLF 文本协议（SCPI 专用）

```
┌─────────────────┬──────────┐
│    PAYLOAD       │ TERMINATOR│
│  "MEAS:VOLT?"     │  "\r\n"    │
└─────────────────┴──────────┘
```

- 适用：Tektronix、Keysight、Rigol 等绝大多数品牌的台式仪器
- 编码：UTF-8（可配置）
- 终止符：默认 `\r\n`（可配置为 `\n` 或 `\r`）
- 跨包缓冲：decode 内部维护 ByteArrayOutputStream 跨帧缓存

### LengthFieldProtocol —— 固定头 + 长度 + CRC 二进制协议

```
┌──────┬──────────────┬─────────────────┬───────┐
│ STX  │ LEN_H + LEN_L │ PAYLOAD         │ CRC8  │
│ 1byte│   2 bytes     │   LEN bytes     │ 1byte │
│ 0xAA │ 大端无符号16位 │                 │ 异或  │
└──────┴──────────────┴─────────────────┴───────┘
```

- 适用：自定义二进制协议的设备（部分功率计、频谱仪、工业设备）
- CRC8：8 位异或校验（轻量级），校验范围 = STX + LEN + PAYLOAD（CRC 自身不参与）
- Resync 策略：校验失败时 `cursor++` 继续找下一个 STX，不丢包
- 跨包缓冲 + 粘包处理：decode 内部 ByteArrayOutputStream 累积 + 循环切帧

### 协议接口约束

Protocol 实现必须满足：
1. **对称编解码**：`decode(encode(cmd))` 必须能还原
2. **内部跨包缓存**：TCP 流式协议的必需品
3. **线程安全**：`encode` 和 `decode` 可能并发调用（本实现加 `synchronized`）

---

## TestServer 测试场景

启动后同时监听两个端口，内置多种测试场景命令。

### LineProtocol 端口 5025

| 命令 | 作用 | 测试什么 |
|------|------|----------|
| `MEAS:VOLT?` | 正常返回 `VOLT:1.234\r\n` | 基础请求-响应 |
| `MEAS:VOLT? #3000` | 延迟 3s 返回 | **responseTimeout** |
| `PING` | `PONG\r\n` | 心跳保活 |
| `STATUS?` | `READY\r\n` | 状态查询 |
| `PUSH` | 主动推 3 条异步数据 | **addListener** 异步监听 |
| `STICKY` | 一次 write 粘 3 帧 | **粘包处理** |
| `FRAG` | 一帧拆 5 次小 write（2字节/次，间隔30ms）| **拆包处理** |
| `DISCONNECT` | 处理完主动关 socket | **自动重连** |
| `BYE` | 正常断开当前连接 | — |

### LengthFieldProtocol 端口 9000

帧格式 `AA LEN_H LEN_L PAYLOAD CRC`，支持 `MEAS:VOLT?` / `PING` 等基础命令，响应也是完整二进制帧。

---

## 线程模型与并发控制

### 信号量时序

| 信号量 | 初始值 | 作用 |
|--------|--------|------|
| `writePermit` | 1（公平） | 保证同一时刻只有一个发送操作 |
| `readPermit` | 0（公平） | 发完命令才启动读取循环 |

### 两把锁各管一摊（严禁交叉持有）

| 锁 | 保护对象 | 持有者 |
|----|----------|--------|
| `socketLock` | Socket 创建/关闭/input/output | `ensureConnected` / `closeSocket` |
| `bufferLock` | byteBuffer + textBuffer + dataArrived | `appendToBuffers` / `dispatchFromBuffers` / `makeProbe` |

### 为什么这样设计

- `writePermit` 用 Semaphore 而不是 synchronized —— 发送可能阻塞很久（等响应），synchronized 会卡别的线程且不可中断
- `readPermit` 初始 0 —— 隔离命令响应和服务端推送数据
- `Condition dataArrived` 替代裸轮询 —— 实时性 + 省 CPU
- `CopyOnWriteArraySet` 存储监听器 —— 遍历安全 + 写少读多
- `ArrayBlockingQueue(256)` 有界队列 —— 避免 OOM 背压失效

---

## FAQ

### 连接被拒绝

- 确认 `TestServer` 已启动（5025 / 9000）
- 确认端口没被占用：`netstat -ano | findstr :5025`
- 确认 config 的 host/port 和服务端匹配

### 响应超时怎么办

- 检查正则表达式是否正确（特别注意 `\r\n` 不要写成 `\n`）
- 服务端慢的话调大 `SocketClientConfig.responseTimeout`
- 用 TestServer 的 `MEAS:VOLT? #3000` 复现超时场景测试客户端是否正确处理

### 自动重连不起作用

- 确认 `SocketClientITFImpl` 已经调用过 `init()`（启动接收线程）
- 看日志里有没有重连失败的异常（SocketClientException）
- `maxReconnectAttempts=0` 代表不重连，检查配置值

### 粘包拆包会丢数据吗

不会。Protocol 实现内部维护了自己的跨帧缓存：
- **拆包**：半截帧留在 buffer 等下次 decode 继续拼
- **粘包**：一次 decode 可能返回多个 Response，全部 dispatch
- 发送前 `clearBuffers()` —— 根治"上一条命令的残余响应匹配到下一条命令"

### 为什么 disconnect 后不能再 send

`disconnect()` 会停掉接收线程 + 关闭 socket。如果要重用，需要先 `init()` 再 `connect()`。
SocketConnectionManager 的做法是：断开就移除 UUID，要重连就 `createConnection` 拿新 UUID。

---

## 依赖

```xml
<!-- pom.xml 核心依赖（已经加好） -->
<dependencies>
  <dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>2.0.13</version>
  </dependency>
  <dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>2.0.13</version>
  </dependency>
</dependencies>
```

---

## 结语

这个项目既是 TCP 客户端实现的**学习样例**（信号量时序、Condition 高效唤醒、粘包拆包处理），也是接入真实仪器设备时的**生产基础框架**。注释覆盖率达到教学级别 —— 每个字段、每个方法、每个锁的"为什么这么设计"都写清楚了。

可以直接基于此扩展具体的设备驱动（电源、示波器、温控器、信号源……）。