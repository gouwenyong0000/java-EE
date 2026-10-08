package com.example.instrument;

import com.example.instrument.api.BlockingDataListener;
import com.example.instrument.api.ConnectionListener;
import com.example.instrument.api.DataListener;
import com.example.instrument.api.InstrumentClient;
import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.config.ReconnectConfig;
import com.example.instrument.exception.ConnectionException;
import com.example.instrument.exception.RequestTimeoutException;
import com.example.instrument.factory.InstrumentClients;
import com.example.instrument.model.Command;
import com.example.instrument.model.CommandIdempotency;
import com.example.instrument.model.Response;
import com.example.instrument.protocol.LineProtocol;
import com.example.instrument.protocol.Protocol;
import com.example.instrument.testing.InstrumentationServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InstrumentClient API 表面测试。
 *
 * <p>覆盖客户端公共 API 的基本可用性（不与其他测试类重复）：</p>
 * <ul>
 *   <li>连接生命周期幂等性</li>
 *   <li>多种 ResponseMatcher 验证</li>
 *   <li>监听器体系（DataListener / ConnectionListener / BlockingDataListener）</li>
 *   <li>异步请求 API</li>
 *   <li>Command / Response 模型基本验证</li>
 * </ul>
 *
 * <p>注意：粘包/半包/超时/重连/并发等场景由 ComprehensiveInstrumentClientTest、
 * ProtocolBoundaryTest、RetryMechanismTest、ConcurrencyStressTest 覆盖。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstrumentClientIntegrationTest {

    private static final int PORT = 19500;
    private static InstrumentationServer testServer;

    private InstrumentClient client;
    private Protocol protocol;

    @BeforeAll
    static void startServer() throws Exception {
        testServer = new InstrumentationServer(PORT);
        testServer.start();
        Thread.sleep(500);
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (testServer != null) testServer.stop();
    }

    @BeforeEach
    void setUp() {
        protocol = LineProtocol.crlf(StandardCharsets.UTF_8);
        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(5))
            .responseTimeout(Duration.ofSeconds(3))
            .reconnect(ReconnectConfig.defaults())
            .build();
        client = InstrumentClients.tcp("localhost", PORT, protocol, config);
    }

    void tearDown() {
        if (client != null) client.close();
    }

    @Test
    @DisplayName("连接生命周期 —— connect/disconnect 幂等性")
    void testConnectDisconnectIdempotent() throws Exception {
        assertFalse(client.isConnected());
        client.connect();
        assertTrue(client.isConnected());
        client.connect();
        assertTrue(client.isConnected());
        client.disconnect();
        assertFalse(client.isConnected());
        client.disconnect();
        assertFalse(client.isConnected());
    }

    @Test
    @DisplayName("ResponseMatcher - equalsText 精确匹配")
    void testEqualsTextMatcher() throws Exception {
        client.connect();
        Response response = client.request(
            Command.text("ECHO:OK"),
            ResponseMatcher.equalsText("OK"),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );
        assertEquals("OK", response.text());
    }

    @Test
    @DisplayName("ResponseMatcher - contains 子串匹配")
    void testContainsMatcher() throws Exception {
        client.connect();
        Response response = client.request(
            Command.text("IDN"),
            ResponseMatcher.contains("TestServer"),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );
        assertTrue(response.text().contains("TestServer"));
    }

    @Test
    @DisplayName("ResponseMatcher - regex 正则匹配")
    void testRegexMatcher() throws Exception {
        client.connect();
        Response response = client.request(
            Command.text("IDN"),
            ResponseMatcher.regex(".*Model-1000.*"),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );
        assertTrue(response.text().contains("Model-1000"));
    }

    @Test
    @DisplayName("fire-and-forget send —— 只发不等响应")
    void testSendWithoutResponse() throws Exception {
        client.connect();
        assertDoesNotThrow(() -> client.send(Command.text("PING")));
    }

    @Test
    @DisplayName("DataListener —— 接收服务器主动推送的数据")
    void testDataListener() throws Exception {
        client.connect();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Response> received = new AtomicReference<>();
        client.addDataListener(response -> {
            received.set(response);
            latch.countDown();
        });
        client.send(Command.text("IDN"));
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertNotNull(received.get());
    }

    @Test
    @DisplayName("移除 DataListener —— remove 后不再接收响应")
    void testRemoveDataListener() throws Exception {
        client.connect();
        CountDownLatch latch = new CountDownLatch(1);
        DataListener listener = response -> latch.countDown();
        client.addDataListener(listener);
        client.removeDataListener(listener);
        client.send(Command.text("IDN"));
        assertEquals(1, latch.getCount());
    }

    @Test
    @DisplayName("ConnectionListener —— onConnected / onDisconnected 回调")
    void testConnectionListener() throws Exception {
        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch disconnectedLatch = new CountDownLatch(1);
        client.addConnectionListener(new ConnectionListener() {
            @Override public void onConnected() { connectedLatch.countDown(); }
            @Override public void onDisconnected(Throwable cause) { disconnectedLatch.countDown(); }
        });
        client.connect();
        assertTrue(connectedLatch.await(2, TimeUnit.SECONDS));
        client.disconnect();
        assertTrue(disconnectedLatch.await(2, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("BlockingDataListener.take —— 阻塞直到有数据到达")
    void testBlockingDataListenerTake() throws Exception {
        client.connect();
        BlockingDataListener blockingListener = client.blockingDataListener();
        client.addDataListener(blockingListener);
        client.send(Command.text("IDN"));
        Response response = blockingListener.take();
        assertNotNull(response);
        assertTrue(response.text().contains("TestServer"));
    }

    @Test
    @DisplayName("BlockingDataListener.poll 超时 —— 队列空返回 null")
    void testBlockingDataListenerPollTimeout() throws Exception {
        client.connect();
        BlockingDataListener blockingListener = client.blockingDataListener();
        Response response = blockingListener.poll(Duration.ofMillis(100));
        assertNull(response);
    }

    @Test
    @DisplayName("requestAsync —— 返回 CompletableFuture 可异步组合")
    void testRequestAsync() throws Exception {
        client.connect();
        CompletableFuture<Response> future = client.requestAsync(
            Command.text("IDN"),
            ResponseMatcher.any(),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );
        Response response = future.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertTrue(response.text().contains("TestServer"));
    }

    @Test
    @DisplayName("close —— 关闭后 isConnected 返回 false")
    void testCloseClient() throws Exception {
        client.connect();
        assertTrue(client.isConnected());
        client.close();
        assertFalse(client.isConnected());
    }

    @Test
    @DisplayName("Command 三种构造方式 —— text / text+charset / bytes")
    void testCommandConstruction() throws Exception {
        client.connect();
        Command cmd1 = Command.text("PING");
        Command cmd2 = Command.text("PING", StandardCharsets.UTF_8);
        Command cmd3 = Command.of("PING".getBytes(StandardCharsets.UTF_8));
        assertEquals(4, cmd1.length());
        assertEquals(4, cmd2.length());
        assertEquals(4, cmd3.length());
    }

    @Test
    @DisplayName("Response.body() —— 返回去除协议边界的纯负载字节")
    void testResponseBody() throws Exception {
        client.connect();
        Response response = client.request(
            Command.text("IDN"),
            ResponseMatcher.any(),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );
        assertNotNull(response.body());
        assertTrue(response.body().length > 0);
        assertEquals(response.text(), new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Response.bytes() —— 返回包含协议边界的完整帧")
    void testResponseFrame() throws Exception {
        client.connect();
        Response response = client.request(
            Command.text("IDN"),
            ResponseMatcher.any(),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );
        assertNotNull(response.bytes());
        assertTrue(response.bytes().length > response.body().length);
    }
}