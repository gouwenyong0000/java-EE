package com.example.instrument;

import com.example.instrument.engine.SocketClientConfig;
import com.example.instrument.engine.SocketClientITFImpl;
import com.example.instrument.engine.SocketConnectionManager;
import com.example.instrument.model.Response;
import com.example.instrument.protocol.LengthFieldProtocol;
import com.example.instrument.protocol.LineProtocol;

import java.time.Duration;
import java.util.UUID;

/**
 * 使用示例 —— 单连接直用 + 多连接管理器两种模式。
 *
 * <h3>模式 A：单连接直用 SocketClientITFImpl</h3>
 * <pre>
 *   var client = new SocketClientITFImpl(config, protocol);
 *   client.init();
 *   client.connect();
 *   try { client.sendAndRegex(...); }
 *   finally { client.disconnect(); }
 * </pre>
 *
 * <h3>模式 B：SocketConnectionManager 管理多台仪器（推荐）</h3>
 * <pre>
 *   try (var mgr = new SocketConnectionManager()) {
 *     UUID scope = mgr.createConnection("192.168.1.10", 5025, new LineProtocol());
 *     UUID gen   = mgr.createConnection("192.168.1.20", 5025, new LineProtocol());
 *     mgr.sendAndRegex(scope, "MEAS:VOLT?", "VOLT:[0-9.]+\\r\\n");
 *     mgr.sendAndRegex(gen,   "OUTP:ON",    "OK\\r\\n");
 *   } // ← 退出时自动 disconnectAll()，无需手动清理
 * </pre>
 */
public final class Demo {

  // ====================================================================
  //  模式 B 示例（推荐）：SocketConnectionManager 多连接管理
  // ====================================================================

  /**
   * 场景：同时连三台不同协议的仪器，并行下发命令。
   * 真实场景里 UUID 会存下来（比如配置文件里），这里为了演示用局部变量接收。
   */
  static void demoMultiConnection() {
    System.out.println("=== 模式 B：SocketConnectionManager 多连接管理 ===");

    try (var mgr = new SocketConnectionManager()) {

      // ---------- ① 同时连三台仪器，各有各的协议 ----------
      UUID scope = mgr.createConnection(
          "127.0.0.1", 5025, new LineProtocol());                    // 示波器：CRLF 文本
      UUID generator = mgr.createConnection(
          "127.0.0.1", 5026, new LineProtocol());                    // 信号源：CRLF 文本
      UUID binaryMeter = mgr.createConnection(
          "127.0.0.1", 9000, new LengthFieldProtocol());            // 功率计：二进制帧

      System.out.println("已创建 " + mgr.size() + " 个连接");
      System.out.println("活跃连接 UUID: " + mgr.activeConnections());

      // ---------- ② 给每个连接注册独立的异步监听器 ----------
      mgr.addListener(scope,
          r -> System.out.println("[示波器] RX: " + pretty(r.text())));
      mgr.addListener(generator,
          r -> System.out.println("[信号源] RX: " + pretty(r.text())));
      mgr.addListener(binaryMeter,
          r -> System.out.println("[功率计] RX bytes=" + r.bytes().length));

      // ---------- ③ 通过 UUID 直接下发命令 ----------
      Response voltage = mgr.sendAndRegex(scope,
          "MEAS:VOLT?", "VOLT:[0-9.]+\\r\\n", Duration.ofSeconds(3));
      System.out.println("电压测量结果: " + pretty(voltage.text()));

      mgr.sendAndRegex(generator,
          "OUTP:ON", "OK\\r\\n", Duration.ofSeconds(2));
      System.out.println("信号源输出已开启");

      // ---------- ④ 动态断开 / 查询状态 ----------
      System.out.println("示波器还活着? " + mgr.isConnected(scope));
      mgr.disconnect(generator);                          // 单独断开信号源
      System.out.println("断开信号源后，活跃连接数: " + mgr.size());

      // ---------- ⑤ 高级：拿到底层 ClientITF 手动操作 ----------
      var scopeClient = mgr.getConnection(scope);         // 返回 ClientITF 或 null
      if (scopeClient != null) {
        scopeClient.connect();                             // 手动触发一次重连
      }

    } // ← try-with-resources 自动调用 disconnectAll()，关闭所有连接
  }

  // ====================================================================
  //  模式 A 示例：单连接直用 SocketClientITFImpl
  // ====================================================================

  /**
   * 场景：只连一台仪器，不需要管理器 overhead。
   * 适合简单脚本 / 单仪器测试工具。
   */
  static void demoSingleConnection() {
    System.out.println("\n=== 模式 A：单连接直用（无管理器） ===");

    var client =
        new SocketClientITFImpl(SocketClientConfig.defaults("127.0.0.1", 5025), new LineProtocol());

    try {
      client.init();
      client.connect();

      client.addListener(
          r -> System.out.println("[ASYNC] RX: " + pretty(r.text())));

      Response response =
          client.sendAndRegex("MEAS:VOLT?", "VOLT:[0-9.]+\\r\\n", Duration.ofSeconds(3));
      System.out.println("[SYNC ] RX: " + pretty(response.text()));

    } finally {
      client.disconnect();  // 单连接务必自己负责清理
    }
  }

  // ====================================================================
  //  入口
  // ====================================================================

  public static void main(String[] args) {
    // 真实仪器场景：注释掉 demoSingleConnection，打开 demoMultiConnection
    // 本地无仪器时可以用 TestServer 起一个 mock server 来测
    demoMultiConnection();
    demoSingleConnection();
  }

  /** 把 \r\n 转义出来，方便日志观察。 */
  static String pretty(String s) {
    return s == null ? "(null)" : s.replace("\r", "\\r").replace("\n", "\\n");
  }
}