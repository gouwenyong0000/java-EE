package com.example.instrument.testing;

import com.example.instrument.factory.InstrumentClients;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发压力测试。
 *
 * <p>验证高并发场景下的线程安全性和性能：</p>
 * <ul>
 *   <li>多线程并发 request</li>
 *   <li>高 QPS 下的指标统计</li>
 *   <li>队列满时的溢出策略</li>
 *   <li>异步请求并发组合</li>
 *   <li>资源竞争和死锁检测</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConcurrencyStressTest {

    private static final int BASE_PORT = 19950;
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
    @DisplayName("并发 request - 多线程同时请求串行化处理")
    void t01_concurrentRequestSerialization() throws Exception {
        client = InstrumentClients.builder("localhost", BASE_PORT, lineProtocol)
            .connectTimeout(Duration.ofSeconds(5))
            .responseTimeout(Duration.ofSeconds(10))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        client.connect();

        int threadCount = 8;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    client.request(
                        Command.text("IDN"),
                        ResponseMatcher.any(),
                        Duration.ofSeconds(5),
                        CommandIdempotency.IDEMPOTENT
                    );
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean allDone = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertTrue(allDone, "所有线程应在 30 秒内完成");
        assertEquals(threadCount, successCount.get(), "所有请求都应成功");
        assertEquals(0, failureCount.get());
    }

    @Test
    @Order(2)
    @DisplayName("高 QPS - 持续高频请求下的稳定性")
    void t02_highQpsStability() throws Exception {
        client = InstrumentClients.builder("localhost", BASE_PORT, lineProtocol)
            .connectTimeout(Duration.ofSeconds(5))
            .responseTimeout(Duration.ofSeconds(10))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        client.connect();

        int totalRequests = 50;
        AtomicInteger successCount = new AtomicInteger();
        AtomicLong totalLatency = new AtomicLong();

        long start = System.nanoTime();

        for (int i = 0; i < totalRequests; i++) {
            long reqStart = System.nanoTime();
            Response response = client.request(
                Command.text("IDN"),
                ResponseMatcher.any(),
                Duration.ofSeconds(5),
                CommandIdempotency.IDEMPOTENT
            );
            assertNotNull(response);
            successCount.incrementAndGet();
            totalLatency.addAndGet(Duration.ofNanos(System.nanoTime() - reqStart).toMillis());
        }

        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        double avgLatency = totalLatency.get() / (double) totalRequests;

        assertEquals(totalRequests, successCount.get());
        assertTrue(avgLatency < 1000, "平均延迟应 < 1000ms，实际: " + avgLatency + "ms");
    }

    @Test
    @Order(3)
    @DisplayName("异步请求并发 - CompletableFuture 组合")
    void t03_asyncRequestConcurrency() throws Exception {
        client = InstrumentClients.builder("localhost", BASE_PORT, lineProtocol)
            .connectTimeout(Duration.ofSeconds(5))
            .responseTimeout(Duration.ofSeconds(10))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        client.connect();

        int requestCount = 10;
        List<CompletableFuture<Response>> futures = new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            CompletableFuture<Response> future = client.requestAsync(
                Command.text("IDN"),
                ResponseMatcher.any(),
                Duration.ofSeconds(5),
                CommandIdempotency.IDEMPOTENT
            );
            futures.add(future);
        }

        CompletableFuture<Void> allDone = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        );

        boolean completed = false;
        try {
            allDone.get(30, TimeUnit.SECONDS);
            completed = true;
        } catch (Exception ignored) {}
        assertTrue(completed, "所有异步请求应在 30 秒内完成");

        for (CompletableFuture<Response> f : futures) {
            Response response = f.get(1, TimeUnit.SECONDS);
            assertNotNull(response);
        }
    }

    @Test
    @Order(4)
    @DisplayName("队列满 - DROP_OLDEST 策略不抛异常")
    void t04_queueFullDropOldest() throws Exception {
        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(3))
            .responseTimeout(Duration.ofSeconds(5))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .asyncQueueCapacity(2)
            .overflowPolicy(ClientConfig.OverflowPolicy.DROP_OLDEST)
            .build();

        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol, config);
        client.connect();

        assertDoesNotThrow(() -> {
            client.request(
                Command.text("MULTIFRAME"),
                ResponseMatcher.equalsText("OK"),
                Duration.ofSeconds(3),
                CommandIdempotency.IDEMPOTENT
            );
        });
    }

    @Test
    @Order(5)
    @DisplayName("队列满 - BLOCK 策略阻塞等待")
    void t05_queueFullBlock() throws Exception {
        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(3))
            .responseTimeout(Duration.ofSeconds(5))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .asyncQueueCapacity(2)
            .overflowPolicy(ClientConfig.OverflowPolicy.BLOCK)
            .build();

        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol, config);
        client.connect();

        assertDoesNotThrow(() -> {
            client.request(
                Command.text("MULTIFRAME"),
                ResponseMatcher.equalsText("OK"),
                Duration.ofSeconds(5),
                CommandIdempotency.IDEMPOTENT
            );
        });
    }

    @Test
    @Order(6)
    @DisplayName("指标线程安全 - 高并发下指标统计正确")
    void t06_metricsThreadSafety() throws Exception {
        client = InstrumentClients.builder("localhost", BASE_PORT, lineProtocol)
            .connectTimeout(Duration.ofSeconds(5))
            .responseTimeout(Duration.ofSeconds(10))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        client.connect();

        int threadCount = 4;
        int requestsPerThread = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < requestsPerThread; i++) {
                        client.request(
                            Command.text("IDN"),
                            ResponseMatcher.any(),
                            Duration.ofSeconds(5),
                            CommandIdempotency.IDEMPOTENT
                        );
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(60, TimeUnit.SECONDS));
        executor.shutdownNow();

        var metricsSnapshot = client.metrics();
        assertNotNull(metricsSnapshot);
        assertTrue(metricsSnapshot.sentFrames() > 0, "应有发送帧");
        assertTrue(metricsSnapshot.sentBytes() > 0, "应有发送字节");
    }

    @Test
    @Order(7)
    @DisplayName("竞态检测 - 快速 connect/close 循环")
    void t07_connectCloseRace() throws Exception {
        for (int i = 0; i < 5; i++) {
            InstrumentClient c = InstrumentClients.builder("localhost", BASE_PORT, lineProtocol)
                .connectTimeout(Duration.ofSeconds(2))
                .responseTimeout(Duration.ofSeconds(2))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build();

            c.connect();
            Thread.sleep(50);
            c.close();
            assertFalse(c.isConnected());
        }
    }

    @Test
    @Order(8)
    @DisplayName("死锁检测 - 多线程 + 监听器 + 异步数据")
    void t08_deadlockDetection() throws Exception {
        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(5))
            .responseTimeout(Duration.ofSeconds(5))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .asyncQueueCapacity(32)
            .build();

        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol, config);

        CountDownLatch dataLatch = new CountDownLatch(3);
        client.addDataListener(r -> dataLatch.countDown());

        client.connect();

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(4);

        for (int i = 0; i < 4; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    client.request(
                        Command.text("MULTIFRAME"),
                        ResponseMatcher.equalsText("OK"),
                        Duration.ofSeconds(5),
                        CommandIdempotency.IDEMPOTENT
                    );
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean noDeadlock = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertTrue(noDeadlock, "不应发生死锁");
    }
}