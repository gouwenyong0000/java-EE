# Instrument Client

面向实验室仪器、测试设备及支持 TCP 自定义协议设备的 Java 17 通信客户端。提供 TCP 连接管理、协议编解码、请求/响应匹配、异步数据接收和断线重连能力。

## 主要功能

- TCP 连接建立、状态监听、关闭及自动重连。
- 行协议（LF/CRLF）和长度字段二进制协议，可扩展自定义协议。
- 同步请求、异步请求和无应答发送。
- 通过响应匹配器区分请求响应与非请求数据。
- 数据监听器、阻塞式数据读取及请求/响应拦截器。
- 命令幂等性标记，帮助控制网络异常时的重试行为。
- 客户端指标与诊断信息。

## 环境要求

- JDK 17 或更高版本
- Maven 3.6 或更高版本

## 包结构

```text
com.example.instrument
├── api                 # 客户端接口、工厂、监听器和响应匹配器
├── protocol            # 协议接口、编解码器、行协议和长度字段协议
├── transport           # 客户端实现、请求管理、接收分发和连接管理
│   ├── tcp             # TCP 连接接口与 Socket 实现
│   └── reconnect       # 重连配置与策略
├── core                # 公共配置、模型、异常、指标和诊断信息
│   ├── config
│   ├── model
│   ├── exception
│   ├── metrics
│   └── diagnostics
└── example             # 使用示例
```

## 快速开始

以下示例使用行协议连接本机 `5025` 端口的仪器。请按实际设备地址、端口和协议调整配置。

```java
import com.example.instrument.api.InstrumentClient;
import com.example.instrument.api.InstrumentClients;
import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.core.config.ClientConfig;
import com.example.instrument.core.model.Command;
import com.example.instrument.core.model.CommandIdempotency;
import com.example.instrument.protocol.LineProtocol;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class Main {
    public static void main(String[] args) throws Exception {
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
    }
}
```

## 协议支持

### 行协议

通过 `LineProtocol` 配置 LF 或 CRLF 行结束符及字符集，适用于常见文本命令/响应设备。

### 长度字段协议

`LengthFieldProtocol` 用于根据帧头、长度字段和配置的校验规则解析二进制帧。具体帧格式应以设备协议为准；如设备使用 CRC
等校验算法，应实现与设备协议一致的编解码逻辑。

TCP 是字节流，单次读取可能得到半帧或多帧，因此自定义协议解码器需要正确处理半包、粘包及非法帧。

## 请求与重试注意事项

- `connectTimeout` 用于连接建立；请求等待时间由请求调用的超时参数控制。
- `IDEMPOTENT` 适合重复执行不会产生额外副作用的命令；执行动作类命令应根据设备行为谨慎选择 `NON_IDEMPOTENT` 或 `UNKNOWN`。
- 连接中断或响应超时，并不一定代表设备没有执行命令。对于启动、触发、复位、写入设定值等命令，不应未经判断就自动重发。
- `DataListener` 应尽量快速返回，耗时业务处理建议交给业务线程池，避免阻塞接收流程。
- 使用完客户端后调用 `close()` 释放连接和相关资源；关闭后不要继续使用该实例。
- 调试原始设备报文时注意日志可能包含设备数据或业务信息。

## 测试用例

测试代码位于 `src/test/java`，主要覆盖以下方面：

- **协议解析**：行协议、长度字段协议、半包/粘包及协议边界。
- **请求响应**：请求注册与完成、响应匹配、异步数据分发和超时处理。
- **连接生命周期**：连接、关闭、重连策略及网络故障场景。
- **并发与异步**：并发请求、异步数据接收和客户端生命周期交互。
- **配置与模型**：客户端配置、命令/响应模型和指标相关逻辑。

运行全部测试：

```bash
mvn clean test
```

## 构建

```bash
mvn clean package
```