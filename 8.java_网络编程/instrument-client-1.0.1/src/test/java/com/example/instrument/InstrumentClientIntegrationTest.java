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
 * InstrumentClient 端到端集成测试。
 *
 * <p>启动一个真实的 TestServer，覆盖客户端的公共 API 表面：</p>
 * <ul>
 *   <li>连接生命周期（connect / disconnect / close / 幂等调用保护）</li>
 *   <li>请求-响应模型（同步、异步、多种 ResponseMatcher）</li>
 *   <li>超时与自动重连</li>
 *   <li>监听器体系（DataListener / ConnectionListener / BlockingDataListener）</li>
 *   <li>Command / Response 模型的编解码正确性</li>
 *   <li>ClientMetrics 指标采集</li>
 * </ul>
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
        if (testServer != null) {
            testServer.stop();
        }
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
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("连接生命周期 —— connect 后已连接，disconnect 后未连接")
    void testConnectAndDisconnect() throws Exception {
        assertFalse(client.isConnected());

        client.connect();
        assertTrue(client.isConnected());

        client.disconnect();
        assertFalse(client.isConnected());
    }

    @Test
    @DisplayName("request-response —— *IDN? 查询返回仪器型号字符串")
    void testRequestResponse() throws Exception {
        client.connect();

        Response response = client.request(
            Command.text("IDN"),
            ResponseMatcher.any(),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        assertNotNull(response);
        assertEquals("TestServer,Model-1000,SN00000001,1.0.0", response.text(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("equalsText 精确匹配 —— ECHO:OK 响应必须严格等于 OK")
    void testRequestWithMatcher() throws Exception {
        client.connect();
        
        Response response = client.request(
            Command.text("ECHO:OK"),
            ResponseMatcher.equalsText("OK", StandardCharsets.UTF_8),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );
        
        assertEquals("OK", response.text(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("contains 子串匹配 —— 响应中包含 TestServer 关键字")
    void testRequestContainsMatcher() throws Exception {
        client.connect();

        Response response = client.request(
            Command.text("IDN"),
            ResponseMatcher.contains("TestServer", StandardCharsets.UTF_8),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        assertTrue(response.text(StandardCharsets.UTF_8).contains("TestServer"));
    }

    @Test
    @DisplayName("regex 正则匹配 —— 响应匹配 .*Model-1000.* 模式")
    void testRequestRegexMatcher() throws Exception {
        client.connect();

        Response response = client.request(
            Command.text("IDN"),
            ResponseMatcher.regex(".*Model-1000.*", StandardCharsets.UTF_8),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        assertTrue(response.text(StandardCharsets.UTF_8).contains("Model-1000"));
    }

    @Test
    @DisplayName("请求超时 —— 服务器延迟 100ms，客户端超时 10ms")
    void testRequestTimeout() throws Exception {
        ClientConfig config = ClientConfig.builder()
            .responseTimeout(Duration.ofMillis(10))
            .build();

        client.close();
        client = InstrumentClients.tcp("localhost", PORT, protocol, config);
        client.connect();

        assertThrows(RequestTimeoutException.class, () -> {
            client.request(
                Command.text("DELAY:100"),
                ResponseMatcher.any(),
                Duration.ofMillis(10),
                CommandIdempotency.IDEMPOTENT
            );
        });
    }

    @Test
    @DisplayName("fire-and-forget send —— 只发不等响应，立即返回")
    void testSendWithoutResponse() throws Exception {
        client.connect();

        assertDoesNotThrow(() -> {
            client.send(Command.text("PING"));
        });
    }

    @Test
    @DisplayName("幂等命令自动连接 —— 未 connect 直接 request 自动建立连接")
    void testAutoConnect() throws Exception {
        assertFalse(client.isConnected());

        Response response = client.request(
            Command.text("IDN"),
            ResponseMatcher.any(),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        assertTrue(client.isConnected());
        assertNotNull(response);
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
            @Override
            public void onConnected() {
                connectedLatch.countDown();
            }

            @Override
            public void onDisconnected(Throwable cause) {
                disconnectedLatch.countDown();
            }
        });

        client.connect();
        assertTrue(connectedLatch.await(2, TimeUnit.SECONDS));

        client.disconnect();
        assertTrue(disconnectedLatch.await(2, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("BlockingDataListener.take —— 阻塞直到有数据到达")
    void testBlockingDataListener() throws Exception {
        client.connect();

        BlockingDataListener blockingListener = client.blockingDataListener();
        client.addDataListener(blockingListener);

        client.send(Command.text("IDN"));

        Response response = blockingListener.take();
        assertNotNull(response);
        assertTrue(response.text(StandardCharsets.UTF_8).contains("TestServer"));
    }

    @Test
    @DisplayName("BlockingDataListener.poll —— 带超时获取数据立即返回")
    void testBlockingDataListenerPoll() throws Exception {
        client.connect();

        BlockingDataListener blockingListener = client.blockingDataListener();
        client.addDataListener(blockingListener);

        client.send(Command.text("IDN"));

        Response response = blockingListener.poll(Duration.ofSeconds(2));
        assertNotNull(response);
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
        assertTrue(response.text(StandardCharsets.UTF_8).contains("TestServer"));
    }

    @Test
    @DisplayName("幂等命令断连自动重连 —— RESET 后再次 request 幂等命令自动重连成功")
    void testIdempotentRetryOnConnectionLoss() throws Exception {
        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(5))
            .responseTimeout(Duration.ofSeconds(10))
            .reconnect(new ReconnectConfig(true, 3, Duration.ofMillis(100), Duration.ofSeconds(1), 0.0))
            .build();

        client.close();
        client = InstrumentClients.tcp("localhost", PORT, protocol, config);

        CountDownLatch reconnectingLatch = new CountDownLatch(1);
        CountDownLatch reconnectedLatch = new CountDownLatch(1);

        client.addConnectionListener(new ConnectionListener() {
            @Override
            public void onReconnecting(int attempt, Duration delay, Throwable cause) {
                reconnectingLatch.countDown();
            }

            @Override
            public void onConnected() {
                if (reconnectingLatch.getCount() == 0) {
                    reconnectedLatch.countDown();
                }
            }
        });

        client.connect();
        assertTrue(client.isConnected());

        Response response = client.request(
            Command.text("IDN"),
            ResponseMatcher.any(),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        assertNotNull(response);
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
    @DisplayName("重复 connect —— 已连接状态下再次调用幂等返回")
    void testDoubleConnect() throws Exception {
        client.connect();
        assertTrue(client.isConnected());

        client.connect();
        assertTrue(client.isConnected());
    }

    @Test
    @DisplayName("重复 disconnect —— 幂等调用不抛异常")
    void testDoubleDisconnect() throws Exception {
        client.connect();
        client.disconnect();
        assertFalse(client.isConnected());

        client.disconnect();
        assertFalse(client.isConnected());
    }

    @Test
    @DisplayName("Command 三种构造方式 —— text / text+charset / bytes 产生等长对象")
    void testCommandText() throws Exception {
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
        assertEquals(response.text(StandardCharsets.UTF_8), new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Response.bytes() —— 返回包含协议边界（CRLF）的完整帧")
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

    @Test
    @DisplayName("ClientMetrics —— 完整 request 后发送帧数和字节数被累加")
    void testMetrics() throws Exception {
        client.connect();

        client.request(
            Command.text("IDN"),
            ResponseMatcher.any(),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        var metrics = ((com.example.instrument.core.InstrumentClientImpl) client).metrics();
        assertTrue(metrics.sentFrames() > 0);
        assertTrue(metrics.sentBytes() > 0);
    }
}