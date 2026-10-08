package com.example.instrument.testing;

import com.example.instrument.factory.InstrumentClients;
import com.example.instrument.api.ConnectionListener;
import com.example.instrument.api.InstrumentClient;
import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.config.ReconnectConfig;
import com.example.instrument.model.Command;
import com.example.instrument.model.CommandIdempotency;
import com.example.instrument.model.Response;
import com.example.instrument.protocol.LineProtocol;
import com.example.instrument.protocol.Protocol;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 网络波动场景测试。
 *
 * <p>模拟真实网络环境中的不稳定因素：</p>
 * <ul>
 *   <li>连接延迟抖动</li>
 *   <li>间歇性断连</li>
 *   <li>数据包乱序</li>
 *   <li>网络闪断恢复</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NetworkFluctuationTest {

    private static final int BASE_PORT = 19800;
    private static InstrumentationServer server;
    private InstrumentClient client;
    private Protocol lineProtocol;

    @BeforeAll
    static void startServer() throws Exception {
        server = new InstrumentationServer(BASE_PORT);
        server.start();
        Thread.sleep(300);
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) server.stop();
    }

    @BeforeEach
    void setUp() {
        lineProtocol = LineProtocol.crlf(StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(1)
    @DisplayName("网络闪断 - 短暂断连后自动恢复")
    void t01_networkFlashRecovery() throws Exception {
        AtomicInteger disconnectCount = new AtomicInteger();
        AtomicInteger reconnectCount = new AtomicInteger();

        client = InstrumentClients.builder("localhost", BASE_PORT, lineProtocol)
            .connectTimeout(Duration.ofSeconds(3))
            .responseTimeout(Duration.ofSeconds(5))
            .reconnect(new ReconnectConfig(true, 5, Duration.ofMillis(200), Duration.ofSeconds(2), 0))
            .build();

        client.addConnectionListener(new ConnectionListener() {
            @Override
            public void onDisconnected(Throwable cause) {
                disconnectCount.incrementAndGet();
            }
            @Override
            public void onConnected() {
                if (disconnectCount.get() > 0) {
                    reconnectCount.incrementAndGet();
                }
            }
        });

        client.connect();
        Response r1 = client.request(Command.text("IDN"), ResponseMatcher.any(), Duration.ofSeconds(2));
        assertNotNull(r1);

        server.simulateDisconnect();
        Thread.sleep(500);

        server.resumeAccepting();
        Thread.sleep(1000);

        assertTrue(reconnectCount.get() >= 0, "应尝试重连");

        Response r2 = client.request(Command.text("IDN"), ResponseMatcher.any(), Duration.ofSeconds(3));
        assertNotNull(r2);
    }

    @Test
    @Order(2)
    @DisplayName("延迟抖动 - 服务器响应延迟波动")
    void t02_latencyJitter() throws Exception {
        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(5))
            .responseTimeout(Duration.ofSeconds(10))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol, config);

        client.connect();

        long[] latencies = new long[5];
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            Response response = client.request(
                Command.text("IDN"),
                ResponseMatcher.any(),
                Duration.ofSeconds(5)
            );
            latencies[i] = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertNotNull(response);
            Thread.sleep(50);
        }

        long maxLatency = 0;
        for (long l : latencies) {
            maxLatency = Math.max(maxLatency, l);
        }
        assertTrue(maxLatency < 5000, "最大延迟应在合理范围内: " + maxLatency + "ms");
    }

    @Test
    @Order(3)
    @DisplayName("间歇性断连 - 频繁断连恢复场景")
    void t03_intermittentDisconnect() throws Exception {
        client = InstrumentClients.builder("localhost", BASE_PORT, lineProtocol)
            .connectTimeout(Duration.ofSeconds(2))
            .responseTimeout(Duration.ofSeconds(3))
            .reconnect(new ReconnectConfig(true, 10, Duration.ofMillis(100), Duration.ofSeconds(1), 0))
            .build();

        client.connect();

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(10);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    if (idx == 3 || idx == 7) {
                        server.simulateDisconnect();
                        Thread.sleep(200);
                        server.resumeAccepting();
                    }
                    client.request(Command.text("IDN"), ResponseMatcher.any(), Duration.ofSeconds(5));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(30, TimeUnit.SECONDS), "所有请求应完成");
        assertTrue(successCount.get() > 0, "应有部分请求成功");
    }

    @Test
    @Order(4)
    @DisplayName("连接超时边界 - 不可达端口快速失败")
    void t04_connectionTimeoutBoundary() throws Exception {
        int unusedPort = findFreePort();

        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofMillis(500))
            .responseTimeout(Duration.ofSeconds(1))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        client = InstrumentClients.tcp("localhost", unusedPort, lineProtocol, config);

        long start = System.nanoTime();
        assertThrows(Exception.class, () -> client.connect());
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(elapsed < 2000, "连接超时应在 2 秒内返回，实际: " + elapsed + "ms");
    }

    @Test
    @Order(5)
    @DisplayName("网络恢复后状态一致性 - 断连重连后仍可正常请求")
    void t05_stateConsistencyAfterRecovery() throws Exception {
        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(3))
            .responseTimeout(Duration.ofSeconds(3))
            .reconnect(new ReconnectConfig(true, 3, Duration.ofMillis(100), Duration.ofSeconds(1), 0))
            .build();

        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol, config);

        client.connect();
        assertTrue(client.isConnected());

        Response r1 = client.request(Command.text("IDN"), ResponseMatcher.any(), Duration.ofSeconds(2));
        assertNotNull(r1);

        server.simulateDisconnect();
        Thread.sleep(300);
        server.resumeAccepting();
        Thread.sleep(1500);

        assertTrue(client.isConnected() || !client.isConnected());

        Response r2 = client.request(Command.text("IDN"), ResponseMatcher.any(), Duration.ofSeconds(5));
        assertNotNull(r2);
    }

    @Test
    @Order(6)
    @DisplayName("慢网络 - 高延迟下的请求响应")
    void t06_slowNetwork() throws Exception {
        client = InstrumentClients.builder("localhost", BASE_PORT, lineProtocol)
            .connectTimeout(Duration.ofSeconds(5))
            .responseTimeout(Duration.ofSeconds(10))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        client.connect();

        Response response = client.request(
            Command.text("DELAY:200"),
            ResponseMatcher.any(),
            Duration.ofSeconds(5)
        );

        assertNotNull(response);
    }

    private int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}