package com.example.instrument;

import com.example.instrument.engine.SocketClientConfig;
import com.example.instrument.engine.SocketClientITFImpl;
import com.example.instrument.engine.SocketConnectionManager;
import com.example.instrument.model.Command;
import com.example.instrument.model.Response;
import com.example.instrument.protocol.LengthFieldProtocol;
import com.example.instrument.protocol.LineProtocol;

import java.time.Duration;
import java.util.UUID;

public class Demo {

  public static void main(String[] args) throws Exception {
    System.out.println("===== Demo A: 单连接直用 SocketClientITFImpl =====");
    demoSingleClient();

    System.out.println("\n===== Demo B: SocketConnectionManager 多连接管理 =====");
    demoMultiConnection();

    System.out.println("\n===== Demo C: LengthFieldProtocol 二进制协议 =====");
    demoBinaryProtocol();

    System.out.println("\n===== 全部 Demo 完成 =====");
  }

  /**
   * 模式 A：单连接直用 SocketClientITFImpl
   * 适合简单脚本、单仪器测试工具。
   */
  static void demoSingleClient() {
    var client = new SocketClientITFImpl(
        SocketClientConfig.defaults("127.0.0.1", 5025),
        new LineProtocol());

    try {
      client.init();
      client.connect();

      Response resp = client.sendAndRegex(
          "MEAS:VOLT?",
          "VOLT:[0-9.]+\\r\\n",
          Duration.ofSeconds(3));
      System.out.println("[单连接] MEAS:VOLT? -> " + resp.text().trim());

      Response pong = client.sendAndRegex("PING", "PONG.*", Duration.ofSeconds(3));
      System.out.println("[单连接] PING -> " + pong.text().trim());
    } catch (Exception e) {
      System.err.println("[单连接] 出错: " + e.getMessage());
    } finally {
      client.disconnect();
    }
  }

  /**
   * 模式 B：SocketConnectionManager 多连接管理
   * 适合同时连多台仪器的场景。
   */
  static void demoMultiConnection() {
    try (var mgr = new SocketConnectionManager()) {
      UUID scope = mgr.createConnection("127.0.0.1", 5025, new LineProtocol());
      UUID gen = mgr.createConnection("127.0.0.1", 5025, new LineProtocol());

      mgr.addListener(scope, r -> System.out.println("[示波器] " + r.text().trim()));

      Response v = mgr.sendAndRegex(scope, "MEAS:VOLT?", "VOLT:[0-9.]+\\r\\n");
      System.out.println("[多连接] scope 电压: " + v.text().trim());

      Response p = mgr.sendAndRegex(gen, "PING", "PONG.*");
      System.out.println("[多连接] gen 心跳: " + p.text().trim());

      System.out.println("[多连接] 当前连接数: " + mgr.size());
    }
  }

  /**
   * 模式 C：LengthFieldProtocol 二进制协议
   */
  static void demoBinaryProtocol() {
    var client = new SocketClientITFImpl(
        SocketClientConfig.defaults("127.0.0.1", 9000),
        new LengthFieldProtocol());

    try {
      client.init();
      client.connect();

      Response resp = client.sendAndMatch(
          Command.of("PING"),
          r -> r.text().startsWith("PONG"),
          Duration.ofSeconds(3));
      System.out.println("[二进制] PING -> " + resp.text());
    } catch (Exception e) {
      System.err.println("[二进制] 出错: " + e.getMessage());
    } finally {
      client.disconnect();
    }
  }
}