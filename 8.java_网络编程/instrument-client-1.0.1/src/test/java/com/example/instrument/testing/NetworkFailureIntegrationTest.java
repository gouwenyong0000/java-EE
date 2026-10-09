package com.example.instrument.testing;

import static org.junit.jupiter.api.Assertions.*;

import com.example.instrument.InstrumentationServer;
import com.example.instrument.api.ConnectionListener;
import com.example.instrument.api.InstrumentClient;
import com.example.instrument.api.InstrumentClients;
import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.core.config.ClientConfig;
import com.example.instrument.core.exception.ConnectionException;
import com.example.instrument.core.exception.RequestTimeoutException;
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
 * 网络故障集成测试。
 *
 * <p>覆盖各种网络故障场景：</p>
 * <ul>
 *   <li>连接重置（RESET 命令）</li>
 *   <li>写半包失败（HALFWRITE 命令）</li>
 *   <li>网络闪断后自动恢复</li>
 *   <li>重连 + close 竞态</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NetworkFailureIntegrationTest {

    private static final int BASE_PORT = 19606;
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
    @DisplayName("连接重置 —— 服务器 RESET 命令断开连接")
    void t01_connectionReset() throws Exception {
        CountDownLatch disconnectLatch = new CountDownLatch(1);

        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(2))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        client.addConnectionListener(new ConnectionListener() {
            @Override
            public void onDisconnected(Throwable cause) {
                disconnectLatch.countDown();
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

        assertTrue(disconnectLatch.await(3, TimeUnit.SECONDS), "应该收到断开通知");
        assertFalse(client.isConnected());
    }

    @Test
    @Order(2)
    @DisplayName("写半包失败 —— HALFWRITE 命令写一半后断开")
    void t02_writeHalfFailure() {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(2))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        client.connect();

        assertThrows(ConnectionException.class, () -> {
            client.request(
                Command.text("HALFWRITE", StandardCharsets.UTF_8),
                ResponseMatcher.any(),
                Duration.ofSeconds(1),
                CommandIdempotency.NON_IDEMPOTENT
            );
        });
    }

    @Test
    @Order(3)
    @DisplayName("网络闪断恢复 —— 短暂断连后自动恢复")
    void t03_networkFlashRecovery() throws Exception {
        AtomicInteger disconnectCount = new AtomicInteger();
        AtomicInteger reconnectCount = new AtomicInteger();
        CountDownLatch reconnectedLatch = new CountDownLatch(1);

        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .reconnect(new ReconnectConfig(true, 5, Duration.ofMillis(200), Duration.ofSeconds(2), 0))
                .build());

        client.addConnectionListener(new ConnectionListener() {
            @Override
            public void onDisconnected(Throwable cause) {
                disconnectCount.incrementAndGet();
            }

            @Override
            public void onReconnecting(int attempt, Duration delay, Throwable cause) {
                reconnectCount.set(attempt);
            }

            @Override
            public void onConnected() {
                if (disconnectCount.get() > 0) {
                    reconnectedLatch.countDown();
                }
            }
        });

        client.connect();

        Response r1 = client.request(
            Command.text("IDN", StandardCharsets.UTF_8),
            ResponseMatcher.any(),
            Duration.ofSeconds(2),
            CommandIdempotency.IDEMPOTENT
        );
        assertNotNull(r1);

        server.simulateDisconnect();
        Thread.sleep(500);

        assertTrue(disconnectCount.get() > 0, "应该触发断开回调");

        assertTrue(reconnectedLatch.await(10, TimeUnit.SECONDS), "应该自动重连");
        assertTrue(client.isConnected());

        Response r2 = client.request(
            Command.text("IDN", StandardCharsets.UTF_8),
            ResponseMatcher.any(),
            Duration.ofSeconds(2),
            CommandIdempotency.IDEMPOTENT
        );
        assertNotNull(r2);
    }

    @Test
    @Order(4)
    @DisplayName("重连期间 close 竞态 —— close 能正确停止调度")
    void t04_reconnectAndCloseRace() throws Exception {
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

    @Test
    @Order(5)
    @DisplayName("服务器无响应 —— 客户端等待超时")
    void t05_serverNoResponse() {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(2))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        client.connect();

        assertThrows(RequestTimeoutException.class, () -> {
            client.request(
                Command.text("DELAY:5000", StandardCharsets.UTF_8),
                ResponseMatcher.any(),
                Duration.ofMillis(100),
                CommandIdempotency.NON_IDEMPOTENT
            );
        });
    }
}