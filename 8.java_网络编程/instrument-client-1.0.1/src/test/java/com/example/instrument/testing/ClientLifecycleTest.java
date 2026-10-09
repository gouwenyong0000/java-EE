package com.example.instrument.testing;

import com.example.instrument.InstrumentationServer;
import com.example.instrument.api.ConnectionListener;
import com.example.instrument.api.InstrumentClient;
import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.config.ReconnectConfig;
import com.example.instrument.exception.ConnectionException;
import com.example.instrument.factory.InstrumentClients;
import com.example.instrument.model.Command;
import com.example.instrument.model.CommandIdempotency;
import com.example.instrument.model.Response;
import com.example.instrument.protocol.LineProtocol;
import com.example.instrument.protocol.Protocol;

import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 客户端生命周期测试。
 *
 * <p>覆盖客户端连接、断开、关闭的完整生命周期：</p>
 * <ul>
 *   <li>connect/disconnect 幂等性</li>
 *   <li>重复 close 不抛异常</li>
 *   <li>关闭后不可重连</li>
 *   <li>连接状态监听器回调</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClientLifecycleTest {

    private static final int BASE_PORT = 19600;
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
    @DisplayName("连接生命周期 —— connect/disconnect 幂等性")
    void t01_connectDisconnectIdempotent() {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(3))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

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
    @Order(2)
    @DisplayName("关闭客户端 —— 重复调用安全、关闭后不可重连")
    void t02_clientCloseIdempotent() {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        client.connect();
        assertTrue(client.isConnected());

        client.close();
        assertFalse(client.isConnected());

        assertDoesNotThrow(client::close, "重复 close 不应抛异常");

        assertThrows(ConnectionException.class, () -> {
            client.connect();
        }, "关闭后的客户端不应再 connect");
    }

    @Test
    @Order(3)
    @DisplayName("连接监听器 —— 正常连接和断开回调触发")
    void t03_connectionListenerCallbacks() throws Exception {
        AtomicInteger connectCount = new AtomicInteger();
        AtomicInteger disconnectCount = new AtomicInteger();
        CountDownLatch disconnectLatch = new CountDownLatch(1);

        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(3))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        client.addConnectionListener(new ConnectionListener() {
            @Override
            public void onConnected() {
                connectCount.incrementAndGet();
            }

            @Override
            public void onDisconnected(Throwable cause) {
                disconnectCount.incrementAndGet();
                disconnectLatch.countDown();
            }
        });

        client.connect();
        assertEquals(1, connectCount.get());

        client.disconnect();
        assertTrue(disconnectLatch.await(2, TimeUnit.SECONDS));
        assertEquals(1, disconnectCount.get());
    }

    @Test
    @Order(4)
    @DisplayName("服务器主动断开 —— 客户端收到 disconnect 回调")
    void t04_serverInitiatedDisconnect() throws Exception {
        AtomicInteger disconnectCount = new AtomicInteger();
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
                disconnectCount.incrementAndGet();
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

        assertTrue(disconnectLatch.await(3, TimeUnit.SECONDS));
        assertEquals(1, disconnectCount.get());
        assertFalse(client.isConnected());
    }
}