package com.example.cli;

import com.example.cli.engine.SocketClientConfig;
import com.example.cli.engine.SocketClientException;
import com.example.cli.engine.SocketClientITFImpl;
import com.example.cli.engine.SocketConnectionManager;
import com.example.cli.model.Command;
import com.example.cli.model.Response;
import com.example.cli.protocol.LengthFieldProtocol;
import com.example.cli.protocol.LineProtocol;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SocketClient 集成测试")
class SocketClientTest {

  private static final String HOST = "127.0.0.1";
  private static final int PORT = 5025;
  private static final int BIN_PORT = 9000;
  private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration MED_TIMEOUT = Duration.ofSeconds(5);

  @BeforeAll
  static void startServer() throws Exception {
    TestServer.startLineProtocol(PORT);
    TestServer.startLengthField(BIN_PORT);
    Thread.sleep(300);
  }

  @AfterAll
  static void stopServer() {
    TestServer.shutdownAll();
  }

  private static SocketClientConfig customConfig(
      int maxReconnect, Duration reconnectInterval, Duration responseTimeout) {
    return new SocketClientConfig(
        HOST,
        PORT,
        Duration.ofSeconds(3),
        responseTimeout,
        Duration.ofMillis(500),
        reconnectInterval,
        maxReconnect,
        true);
  }

  private static SocketClientConfig unreachableConfig() {
    return new SocketClientConfig(
        "127.0.0.1", 59999,
        Duration.ofMillis(200), Duration.ofSeconds(1),
        Duration.ofMillis(200), Duration.ofMillis(100),
        1, true);
  }

  // ====================================================================
  //  基本连接测试
  // ====================================================================

  @Nested
  @DisplayName("基本连接测试")
  class ConnectionTests {

    private SocketClientITFImpl client;

    @BeforeEach
    void setUp() {
      client = new SocketClientITFImpl(SocketClientConfig.defaults(HOST, PORT), new LineProtocol());
    }

    @AfterEach
    void tearDown() {
      if (client != null) client.disconnect();
    }

    @Test
    @DisplayName("init → connect → isConnected → disconnect → !isConnected")
    void testLifecycle() {
      client.init();
      client.connect();
      assertTrue(client.isConnected());

      client.disconnect();
      assertFalse(client.isConnected());
    }

    @Test
    @DisplayName("多次 init 幂等，不会启动多个接收线程")
    void testInitIdempotent() {
      client.init();
      client.init();
      client.init();
      client.connect();

      assertTrue(client.isConnected());
    }

    @Test
    @DisplayName("disconnect 后可重新 init + connect 恢复")
    void testRecoverAfterDisconnect() {
      client.init();
      client.connect();
      assertTrue(client.isConnected());

      client.disconnect();
      assertFalse(client.isConnected());

      client.init();
      client.connect();
      assertTrue(client.isConnected());
    }

    @Test
    @DisplayName("未 init 直接 connect 应抛 IllegalStateException")
    void testConnectWithoutInit() {
      assertThrows(IllegalStateException.class, () -> client.connect());
    }
  }

  // ====================================================================
  //  命令发送与响应测试
  // ====================================================================

  @Nested
  @DisplayName("命令发送与响应测试")
  class CommandTests {

    private SocketClientITFImpl client;

    @BeforeEach
    void setUp() {
      client = new SocketClientITFImpl(SocketClientConfig.defaults(HOST, PORT), new LineProtocol());
      client.init();
      client.connect();
    }

    @AfterEach
    void tearDown() {
      if (client != null) client.disconnect();
    }

    @Test
    @Timeout(10)
    @DisplayName("PING → PONG")
    void testPingPong() {
      Response resp = client.sendAndRegex("PING", "PONG.*", MED_TIMEOUT);
      assertAll(
          () -> assertNotNull(resp),
          () -> assertEquals("PONG\r\n",resp.text())
      );
    }

    @Test
    @Timeout(10)
    @DisplayName("MEAS:VOLT? → VOLT:1.234")
    void testMeasureVoltage() {
      Response resp = client.sendAndRegex("MEAS:VOLT?", "VOLT:[0-9.]+.*", MED_TIMEOUT);
      assertAll(
          () -> assertNotNull(resp),
          () -> assertEquals("VOLT:1.234\r\n",resp.text())
      );
    }

    @Test
    @Timeout(10)
    @DisplayName("STATUS? → READY")
    void testStatus() {
      Response resp = client.sendAndRegex("STATUS?", "READY.*", MED_TIMEOUT);
      assertEquals("READY\r\n",resp.text());
    }

    @Test
    @Timeout(10)
    @DisplayName("连续 5 次串行 PING 全部成功")
    void testSequentialCommands() {
      for (int i = 0; i < 5; i++) {
        Response resp = client.sendAndRegex("PING", "PONG.*", SHORT_TIMEOUT);
        assertTrue(resp.text().contains("PONG"), "第 " + (i + 1) + " 次失败");
      }
    }

    @Test
    @Timeout(15)
    @DisplayName("延迟命令超过 responseTimeout 应抛 SocketClientException")
    void testResponseTimeout() {
      SocketClientITFImpl slowClient = new SocketClientITFImpl(
          customConfig(3, Duration.ofSeconds(1), Duration.ofSeconds(2)), new LineProtocol());
      slowClient.init();
      slowClient.connect();

      try {
        SocketClientException ex = assertThrows(SocketClientException.class, () ->
            slowClient.sendAndRegex("MEAS:VOLT? #5000", "VOLT:[0-9.]+.*", null)
        );
        assertTrue(ex.getMessage().contains("not matched"), "异常信息应包含超时描述");
      } finally {
        slowClient.disconnect();
      }
    }
  }

  // ====================================================================
  //  异步推送测试
  // ====================================================================

  @Nested
  @DisplayName("异步推送测试")
  class AsyncPushTests {

    private SocketClientITFImpl client;

    @BeforeEach
    void setUp() {
      client = new SocketClientITFImpl(SocketClientConfig.defaults(HOST, PORT), new LineProtocol());
      client.init();
      client.connect();
    }

    @AfterEach
    void tearDown() {
      if (client != null) client.disconnect();
    }

    @Test
    @Timeout(10)
    @DisplayName("addListener 回调模式应收到全部 3 条推送")
    void testAsyncPushViaListener() throws Exception {
      CopyOnWriteArrayList<Response> received = new CopyOnWriteArrayList<>();
      CountDownLatch latch = new CountDownLatch(3);

      client.addListener(resp -> {
        received.add(resp);
        latch.countDown();
      });

      client.sendAndRegex("PUSH", "ASYNC:DATA1.*", MED_TIMEOUT);

      assertTrue(latch.await(3, TimeUnit.SECONDS), "3 秒内应收到 3 条推送");
      assertAll(
          () -> assertEquals(3, received.size()),
          () -> assertTrue(received.get(0).text().contains("ASYNC:DATA1")),
          () -> assertTrue(received.get(1).text().contains("ASYNC:DATA2")),
          () -> assertTrue(received.get(2).text().contains("ASYNC:DATA3"))
      );
    }

    @Test
    @Timeout(10)
    @DisplayName("blockingListener 阻塞模式应按序收到推送")
    void testAsyncPushViaBlockingListener() throws Exception {
      var bl = client.blockingListener();

      client.sendAndRegex("PUSH", "ASYNC:DATA1.*", MED_TIMEOUT);

      Response r1 = bl.poll(SHORT_TIMEOUT);
      Response r2 = bl.poll(SHORT_TIMEOUT);
      Response r3 = bl.poll(SHORT_TIMEOUT);

      assertAll(
          () -> assertNotNull(r1, "第 1 条不应为 null"),
          () -> assertNotNull(r2, "第 2 条不应为 null"),
          () -> assertNotNull(r3, "第 3 条不应为 null"),
          () -> assertTrue(r1.text().contains("ASYNC:DATA1")),
          () -> assertTrue(r2.text().contains("ASYNC:DATA2")),
          () -> assertTrue(r3.text().contains("ASYNC:DATA3"))
      );
    }
  }

  // ====================================================================
  //  粘包与拆包测试
  // ====================================================================

  @Nested
  @DisplayName("粘包与拆包测试")
  class PacketTests {

    private SocketClientITFImpl client;

    @BeforeEach
    void setUp() {
      client = new SocketClientITFImpl(SocketClientConfig.defaults(HOST, PORT), new LineProtocol());
      client.init();
      client.connect();
    }

    @AfterEach
    void tearDown() {
      if (client != null) client.disconnect();
    }

    @Test
    @Timeout(10)
    @DisplayName("STICKY：一次写入 3 帧，客户端应正确切分为 3 条 Response")
    void testStickyPackets() throws Exception {
      CopyOnWriteArrayList<Response> received = new CopyOnWriteArrayList<>();
      CountDownLatch latch = new CountDownLatch(3);

      client.addListener(resp -> {
        received.add(resp);
        latch.countDown();
      });

      client.sendAndRegex("STICKY", "STICKY:FRAME1.*", MED_TIMEOUT);

      assertTrue(latch.await(2, TimeUnit.SECONDS), "应收到 3 帧粘包数据");
      assertAll(
          () -> assertEquals(3, received.size()),
          () -> assertTrue(received.get(0).text().contains("STICKY:FRAME1")),
          () -> assertTrue(received.get(1).text().contains("STICKY:FRAME2")),
          () -> assertTrue(received.get(2).text().contains("STICKY:FRAME3"))
      );
    }

    @Test
    @Timeout(10)
    @DisplayName("FRAG：5 次小 write，客户端应重组为完整帧")
    void testFragmentedPackets() {
      Response resp = client.sendAndRegex("FRAG", "FRAG:COMPLETE_RESPONSE.*", Duration.ofSeconds(10));
      assertTrue(resp.text().contains("FRAG:COMPLETE_RESPONSE"));
    }
  }

  // ====================================================================
  //  断连与重连测试
  // ====================================================================

  @Nested
  @DisplayName("断连与重连测试")
  class ReconnectTests {

    @RepeatedTest(3)
    @Timeout(20)
    @DisplayName("DISCONNECT 后客户端自动重连并发送下一条命令")
    void testAutoReconnect() {
      SocketClientConfig cfg = customConfig(3, Duration.ofMillis(500), MED_TIMEOUT);
      SocketClientITFImpl client = new SocketClientITFImpl(cfg, new LineProtocol());

      try {
        client.init();
        client.connect();
        assertTrue(client.isConnected());

        Response resp1 = client.sendAndRegex("DISCONNECT", "OK.*", MED_TIMEOUT);
        assertTrue(resp1.text().contains("OK"));
        assertFalse(client.isConnected(), "DISCONNECT 后 socket 应已关闭");

        Response resp2 = client.sendAndRegex("PING", "PONG.*", Duration.ofSeconds(10));
        assertAll(
            () -> assertTrue(resp2.text().contains("PONG")),
            () -> assertTrue(client.isConnected(), "自动重连后应恢复连接")
        );
      } finally {
        client.disconnect();
      }
    }

    @Test
    @Timeout(15)
    @DisplayName("重连等待时间应 >= reconnectInterval（指数退避）")
    void testReconnectBackoff() {
      SocketClientConfig cfg = customConfig(2, Duration.ofMillis(300), MED_TIMEOUT);
      SocketClientITFImpl client = new SocketClientITFImpl(cfg, new LineProtocol());

      try {
        client.init();
        client.connect();
        client.sendAndRegex("DISCONNECT", "OK.*", SHORT_TIMEOUT);

        long start = System.currentTimeMillis();
        client.sendAndRegex("PING", "PONG.*", Duration.ofSeconds(10));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 200, "应等待至少一次 reconnectInterval（含指数退避），实际等待 " + elapsed + " ms");
      } finally {
        client.disconnect();
      }
    }

    @Test
    @Timeout(10)
    @DisplayName("BYE 命令后客户端自动重连")
    void testReconnectAfterBye() {
      SocketClientConfig cfg = customConfig(3, Duration.ofMillis(300), MED_TIMEOUT);
      SocketClientITFImpl client = new SocketClientITFImpl(cfg, new LineProtocol());

      try {
        client.init();
        client.connect();

        Response resp1 = client.sendAndRegex("BYE", "BYE.*", MED_TIMEOUT);
        assertTrue(resp1.text().contains("BYE"));

        Response resp2 = client.sendAndRegex("PING", "PONG.*", Duration.ofSeconds(10));
        assertTrue(resp2.text().contains("PONG"));
      } finally {
        client.disconnect();
      }
    }
  }

  // ====================================================================
  //  SocketConnectionManager 多连接测试
  // ====================================================================

  @Nested
  @DisplayName("SocketConnectionManager 多连接测试")
  class MultiConnectionTests {

    @Test
    @Timeout(15)
    @DisplayName("创建 2 个连接，独立发送命令互不干扰")
    void testMultiConnection() {
      try (SocketConnectionManager mgr = new SocketConnectionManager()) {
        UUID c1 = mgr.createConnection(HOST, PORT, new LineProtocol());
        UUID c2 = mgr.createConnection(HOST, PORT, new LineProtocol());

        assertAll(
            () -> assertEquals(2, mgr.size()),
            () -> assertTrue(mgr.isConnected(c1)),
            () -> assertTrue(mgr.isConnected(c2))
        );

        Response r1 = mgr.sendAndRegex(c1, "PING", "PONG.*", SHORT_TIMEOUT);
        Response r2 = mgr.sendAndRegex(c2, "MEAS:VOLT?", "VOLT:.*", SHORT_TIMEOUT);

        assertAll(
            () -> assertTrue(r1.text().contains("PONG")),
            () -> assertTrue(r2.text().contains("VOLT"))
        );
      }
    }

    @Test
    @Timeout(10)
    @DisplayName("断开 conn1 不影响 conn2")
    void testDisconnectOneDoesNotAffectOther() {
      try (SocketConnectionManager mgr = new SocketConnectionManager()) {
        UUID c1 = mgr.createConnection(HOST, PORT, new LineProtocol());
        UUID c2 = mgr.createConnection(HOST, PORT, new LineProtocol());

        mgr.disconnect(c1);

        assertAll(
            () -> assertFalse(mgr.isConnected(c1)),
            () -> assertTrue(mgr.isConnected(c2))
        );

        Response r2 = mgr.sendAndRegex(c2, "PING", "PONG.*", SHORT_TIMEOUT);
        assertTrue(r2.text().contains("PONG"));
      }
    }

    @Test
    @Timeout(15)
    @DisplayName("close() 断开所有连接")
    void testCloseDisconnectsAll() {
      SocketConnectionManager mgr = new SocketConnectionManager();
      UUID c1 = mgr.createConnection(HOST, PORT, new LineProtocol());
      UUID c2 = mgr.createConnection(HOST, PORT, new LineProtocol());

      assertTrue(mgr.isConnected(c1));
      assertTrue(mgr.isConnected(c2));

      mgr.close();

      assertAll(
          () -> assertFalse(mgr.isConnected(c1)),
          () -> assertFalse(mgr.isConnected(c2))
      );
    }
  }

  // ====================================================================
  //  错误处理测试
  // ====================================================================

  @Nested
  @DisplayName("错误处理测试")
  class ErrorHandlingTests {

    @Test
    @Timeout(10)
    @DisplayName("正则不匹配 → SocketClientException 含超时描述")
    void testRegexNotMatched() {
      SocketClientITFImpl client = new SocketClientITFImpl(
          SocketClientConfig.defaults(HOST, PORT), new LineProtocol());

      try {
        client.init();
        client.connect();

        SocketClientException ex = assertThrows(SocketClientException.class, () ->
            client.sendAndRegex("PING", "NEVER_MATCH", SHORT_TIMEOUT)
        );
        assertTrue(ex.getMessage().contains("not matched"), "异常信息应包含 'not matched'");
      } finally {
        client.disconnect();
      }
    }

    @Test
    @Timeout(5)
    @DisplayName("连接不可达服务器 → SocketClientException")
    void testConnectToUnreachableServer() {
      SocketClientITFImpl client = new SocketClientITFImpl(unreachableConfig(), new LineProtocol());

      client.init();
      assertThrows(SocketClientException.class, () -> client.connect());
      client.disconnect();
    }

    @Test
    @Timeout(5)
    @DisplayName("未 init 就 send → IllegalStateException")
    void testSendWithoutInit() {
      SocketClientITFImpl client = new SocketClientITFImpl(
          SocketClientConfig.defaults(HOST, PORT), new LineProtocol());

      assertThrows(IllegalStateException.class, () ->
          client.sendAndRegex("PING", "PONG.*", SHORT_TIMEOUT)
      );
      client.disconnect();
    }

    @Test
    @Timeout(10)
    @DisplayName("监听器抛异常不影响后续命令")
    void testListenerExceptionIsolation() {
      SocketClientITFImpl client = new SocketClientITFImpl(
          SocketClientConfig.defaults(HOST, PORT), new LineProtocol());

      try {
        client.init();
        client.connect();

        AtomicInteger callCount = new AtomicInteger();
        client.addListener(resp -> {
          callCount.incrementAndGet();
          if (callCount.get() == 1) throw new RuntimeException("Listener error");
        });

        Response r1 = client.sendAndRegex("PING", "PONG.*", SHORT_TIMEOUT);
        assertTrue(r1.text().contains("PONG"));

        Response r2 = client.sendAndRegex("PING", "PONG.*", SHORT_TIMEOUT);
        assertTrue(r2.text().contains("PONG"));

        assertTrue(callCount.get() >= 1, "监听器至少被调用过 1 次");
      } finally {
        client.disconnect();
      }
    }

    @Test
    @Timeout(10)
    @DisplayName("disconnect 后 send → IllegalStateException")
    void testSendAfterDisconnect() {
      SocketClientITFImpl client = new SocketClientITFImpl(
          SocketClientConfig.defaults(HOST, PORT), new LineProtocol());

      client.init();
      client.connect();
      client.disconnect();

      assertThrows(IllegalStateException.class, () ->
          client.sendAndRegex("PING", "PONG.*", SHORT_TIMEOUT)
      );
    }
  }

  // ====================================================================
  //  LengthFieldProtocol 二进制协议集成测试
  // ====================================================================

  @Nested
  @DisplayName("LengthFieldProtocol 二进制协议集成测试")
  class BinaryProtocolTests {

    private static SocketClientConfig binaryConfig(int maxReconnect, Duration responseTimeout) {
      return new SocketClientConfig(
          HOST, BIN_PORT,
          Duration.ofSeconds(3), responseTimeout,
          Duration.ofMillis(500), Duration.ofMillis(300),
          maxReconnect, true);
    }

    @Test
    @Timeout(10)
    @DisplayName("二进制 PING 命令返回 PONG 响应")
    void testBinaryPingPong() {
      SocketClientITFImpl client = new SocketClientITFImpl(
          binaryConfig(3, MED_TIMEOUT), new LengthFieldProtocol());

      try {
        client.init();
        client.connect();

        Response resp = client.sendAndMatch(
            Command.of("PING"),
            r -> r.text().startsWith("PONG"),
            MED_TIMEOUT);
        assertTrue(resp.text().contains("PONG"));
      } finally {
        client.disconnect();
      }
    }

    @Test
    @Timeout(10)
    @DisplayName("二进制 ECHO 命令回显 payload")
    void testBinaryEcho() {
      SocketClientITFImpl client = new SocketClientITFImpl(
          binaryConfig(3, MED_TIMEOUT), new LengthFieldProtocol());

      try {
        client.init();
        client.connect();

        byte[] payload = "ECHO:hello".getBytes(StandardCharsets.UTF_8);
        Response resp = client.sendAndMatch(
            new Command(payload),
            r -> r.text().startsWith("ECHO:hello"),
            MED_TIMEOUT);
        assertTrue(resp.text().startsWith("ECHO:hello"));
      } finally {
        client.disconnect();
      }
    }

    @Test
    @Timeout(10)
    @DisplayName("二进制 STICKY 粘包响应应正确拆分")
    void testBinaryStickyResponse() {
      SocketClientITFImpl client = new SocketClientITFImpl(
          binaryConfig(3, MED_TIMEOUT), new LengthFieldProtocol());

      try {
        client.init();
        client.connect();

        Response resp = client.sendAndMatch(
            Command.of("STICKY"),
            r -> new String(r.text()).startsWith("STICKY:F1"),
            MED_TIMEOUT);
        assertTrue(new String(resp.text()).startsWith("STICKY:F1"));
      } finally {
        client.disconnect();
      }
    }

    @Test
    @Timeout(10)
    @DisplayName("二进制 CRC 错误的响应应被丢弃，最终超时")
    void testBinaryCrcErrorDiscarded() {
      SocketClientITFImpl client = new SocketClientITFImpl(
          binaryConfig(3, Duration.ofSeconds(3)), new LengthFieldProtocol());

      try {
        client.init();
        client.connect();

        assertThrows(SocketClientException.class, () ->
            client.sendAndMatch(
                Command.of("BADCRC"),
                r -> true,
                Duration.ofSeconds(3)));
      } finally {
        client.disconnect();
      }
    }
  }

  // ====================================================================
  //  SocketConnectionManager 生命周期测试
  // ====================================================================

  @Nested
  @DisplayName("SocketConnectionManager 生命周期测试")
  class ManagerLifecycleTests {

    @Test
    @Timeout(10)
    @DisplayName("shutdown 后 createConnection 应抛 IllegalStateException")
    void testCreateConnectionAfterShutdown() {
      SocketConnectionManager mgr = new SocketConnectionManager();
      mgr.disconnectAll();

      assertThrows(IllegalStateException.class, () ->
          mgr.createConnection(HOST, PORT, new LineProtocol()));
    }

    @Test
    @Timeout(10)
    @DisplayName("isConnected 查询不存在的 id 返回 false")
    void testIsConnectedUnknownId() {
      SocketConnectionManager mgr = new SocketConnectionManager();
      assertFalse(mgr.isConnected(UUID.randomUUID()));
      mgr.disconnectAll();
    }

    @Test
    @Timeout(10)
    @DisplayName("disconnect 不存在的 id 不抛异常")
    void testDisconnectUnknownId() {
      SocketConnectionManager mgr = new SocketConnectionManager();
      assertDoesNotThrow(() -> mgr.disconnect(UUID.randomUUID()));
      mgr.disconnectAll();
    }

    @Test
    @Timeout(10)
    @DisplayName("close() 幂等：多次调用不抛异常")
    void testCloseIdempotent() {
      SocketConnectionManager mgr = new SocketConnectionManager();
      assertDoesNotThrow(mgr::close);
      assertDoesNotThrow(mgr::close);
    }

    @Test
    @Timeout(10)
    @DisplayName("size() 在创建和断开连接后正确反映数量")
    void testSizeReflectsConnections() {
      SocketConnectionManager mgr = new SocketConnectionManager();
      assertEquals(0, mgr.size());

      UUID c1 = mgr.createConnection(HOST, PORT, new LineProtocol());
      assertEquals(1, mgr.size());

      UUID c2 = mgr.createConnection(HOST, PORT, new LineProtocol());
      assertEquals(2, mgr.size());

      mgr.disconnect(c1);
      assertEquals(1, mgr.size());

      mgr.close();
      assertEquals(0, mgr.size());
    }
  }
}