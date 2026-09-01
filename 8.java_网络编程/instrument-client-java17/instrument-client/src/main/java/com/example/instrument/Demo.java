package com.example.instrument;

import com.example.instrument.engine.SocketClientConfig;
import com.example.instrument.engine.SocketClientITFImpl;
import com.example.instrument.model.Response;
import com.example.instrument.protocol.LineProtocol;

import java.time.Duration;

/** 示例入口，展示如何初始化客户端、监听响应并发送一个查询命令。 */
public final class Demo {
  public static void main(String[] args) {
    // 这里以本地仪器地址 127.0.0.1:5025 为例，使用 CRLF 文本协议进行通信。
    var client =
        new SocketClientITFImpl(SocketClientConfig.defaults("127.0.0.1", 5025), new LineProtocol());
    client.init();
    client.addListener(
        r -> System.out.println("RX: " + r.text().replace("\r", "\\r").replace("\n", "\\n")));
    client.connect();

    // 发送一条 SCPI 风格测量命令，并使用正则匹配期望响应格式。

    Response response =
        client.sendAndRegex("MEAS:VOLT?", "VOLT:[0-9.]+\\r\\n", Duration.ofSeconds(3));
    System.out.println("Matched: " + response.text());

    client.disconnect();
  }
}
