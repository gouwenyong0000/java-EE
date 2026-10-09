package com.example.instrument.testing;

import static org.junit.jupiter.api.Assertions.*;

import com.example.instrument.InstrumentationServer;
import com.example.instrument.api.ConnectionListener;
import com.example.instrument.api.InstrumentClient;
import com.example.instrument.api.InstrumentClients;
import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.core.config.ClientConfig;
import com.example.instrument.core.exception.ConnectionException;
import com.example.instrument.core.model.Command;
import com.example.instrument.core.model.CommandIdempotency;
import com.example.instrument.core.model.Response;
import com.example.instrument.protocol.LineProtocol;
import com.example.instrument.protocol.Protocol;
import com.example.instrument.transport.reconnect.ReconnectConfig;

import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端重试机制测试。
 *
 * <p>覆盖幂等重试、自动重连、非幂等不重连等场景：</p>
 * <ul>
 *   <li>幂等命令自动重连并完成</li>
 *   <li>非幂等命令不自动重连</li>
 *   <li>自动重连成功回调</li>
 *   <li>重连期间 close 能正确停止</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClientRetryTest {

    private static final int BASE_PORT = 19603;
    private static InstrumentationServer server;

    private InstrumentClient client;
    private Protocol protocol;

    @BeforeAll
    static void startServer() throws Exception {
        server = new InstrumentationServer(BASE_PORT);
        server.start();
        Thread.sleep(200);
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) server.stop();
    }

    @BeforeEach
    void setUp() {
        protocol = LineProtocol.crlf(StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(1)
    @DisplayName("幂等命令 —— 连接断开后自动重连并完成请求")
    void t01_idempotentRetryAfterDisconnect() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        client.connect();
        assertTrue(client.isConnected());

        assertThrows(ConnectionException.class, () -> {
            client.request(
                Command.text("RESET", StandardCharsets.UTF_8),
                ResponseMatcher.any(),
                Duration.ofSeconds(1),
                CommandIdempotency.NON_IDEMPOTENT
            );
        });

        assertFalse(client.isConnected());

        Response response = client.request(
            Command.text("IDN", StandardCharsets.UTF_8),
            ResponseMatcher.any(),
            Duration.ofSeconds(5),
            CommandIdempotency.IDEMPOTENT
        );

        assertTrue(client.isConnected(), "幂等重试后应该重新连接");
        assertNotNull(response);
        assertEquals("TestServer,Model-1000,SN00000001,1.0.0", response.text(StandardCharsets.UTF_8));
    }

    @Test
    @Order(2)
    @DisplayName("非幂等命令 —— 连接断开后不自动重连")
    void t02_nonIdempotentNoRetry() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(2))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        client.connect();
        assertTrue(client.isConnected());

        assertThrows(ConnectionException.class, () -> {
            client.request(
                Command.text("RESET", StandardCharsets.UTF_8),
                ResponseMatcher.any(),
                Duration.ofSeconds(1),
                CommandIdempotency.NON_IDEMPOTENT
            );
        });

        assertFalse(client.isConnected());

        assertThrows(ConnectionException.class, () -> {
            client.request(
                Command.text("IDN", StandardCharsets.UTF_8),
                ResponseMatcher.any(),
                Duration.ofSeconds(2),
                CommandIdempotency.NON_IDEMPOTENT
            );
        }, "非幂等命令不应自动重连");
    }

    @Test
    @Order(3)
    @DisplayName("自动重连配置 —— 断开后自动重连成功")
    void t03_autoReconnectSuccess() throws Exception {
        CountDownLatch reconnectedLatch = new CountDownLatch(1);
        AtomicInteger reconnectAttempts = new AtomicInteger();

        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .reconnect(new ReconnectConfig(true, 5, Duration.ofMillis(100), Duration.ofSeconds(2), 0))
                .build());

        client.addConnectionListener(new ConnectionListener() {
            @Override
            public void onReconnecting(int attempt, Duration delay, Throwable cause) {
                reconnectAttempts.set(attempt);
            }

            @Override
            public void onConnected() {
                if (reconnectAttempts.get() > 0) {
                    reconnectedLatch.countDown();
                }
            }
        });

        client.connect();
        assertTrue(client.isConnected());

        assertThrows(ConnectionException.class, () -> {
            client.request(
                Command.text("RESET", StandardCharsets.UTF_8),
                ResponseMatcher.any(),
                Duration.ofSeconds(1),
                CommandIdempotency.NON_IDEMPOTENT
            );
        });

        assertTrue(reconnectedLatch.await(10, TimeUnit.SECONDS), "应该自动重连成功");
        assertTrue(client.isConnected());

        Response response = client.request(
            Command.text("IDN", StandardCharsets.UTF_8),
            ResponseMatcher.any(),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );
        assertNotNull(response);
    }

    @Test
    @Order(4)
    @DisplayName("重连期间 close —— 能正确停止重连调度")
    void t04_closeDuringReconnect() throws Exception {
        CountDownLatch reconnectingLatch = new CountDownLatch(1);
        AtomicInteger reconnectCount = new AtomicInteger();

        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(2))
                .reconnect(new ReconnectConfig(true, 10, Duration.ofMillis(100), Duration.ofSeconds(5), 0))
                .build());

        client.addConnectionListener(new ConnectionListener() {
            @Override
            public void onReconnecting(int attempt, Duration delay, Throwable cause) {
                reconnectCount.incrementAndGet();
                reconnectingLatch.countDown();
            }
        });

        client.connect();

        assertThrows(ConnectionException.class, () -> {
            client.request(
                Command.text("RESET", StandardCharsets.UTF_8),
                ResponseMatcher.any(),
                Duration.ofSeconds(1),
                CommandIdempotency.NON_IDEMPOTENT
            );
        });

        assertTrue(reconnectingLatch.await(1, TimeUnit.SECONDS));
        assertTrue(reconnectCount.get() > 0);

        Thread.sleep(200);
        client.close();
        assertFalse(client.isConnected());

        int countAfterClose = reconnectCount.get();
        Thread.sleep(500);
        assertEquals(countAfterClose, reconnectCount.get(), "close 后不应再触发重连");
    }
}