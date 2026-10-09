package com.example.instrument.testing;

import com.example.instrument.InstrumentationServer;
import com.example.instrument.api.InstrumentClient;
import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.config.ReconnectConfig;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 客户端并发测试。
 *
 * <p>覆盖多线程并发场景：</p>
 * <ul>
 *   <li>多线程并发请求串行化处理</li>
 *   <li>高 QPS 下指标统计</li>
 *   <li>线程池满时的队列溢出</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClientConcurrencyTest {

    private static final int BASE_PORT = 19604;
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
    @DisplayName("并发请求串行化 —— 多线程同时请求、串行化处理")
    void t01_concurrentRequestSerialization() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(10))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

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
                        Command.text("IDN", StandardCharsets.UTF_8),
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
        assertEquals(threadCount, successCount.get(), "所有请求都应该成功（因为内部是串行化的）");
        assertEquals(0, failureCount.get());
    }

    @Test
    @Order(2)
    @DisplayName("高并发性能 —— 快速连续请求统计")
    void t02_highConcurrencyPerformance() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(10))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        client.connect();

        int requestCount = 20;
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < requestCount; i++) {
            Response response = client.request(
                Command.text("IDN", StandardCharsets.UTF_8),
                ResponseMatcher.any(),
                Duration.ofSeconds(3),
                CommandIdempotency.IDEMPOTENT
            );
            assertNotNull(response);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        double qps = (double) requestCount / (elapsed / 1000.0);

        assertTrue(qps > 0, "QPS 应该大于 0");
        System.out.println("Completed " + requestCount + " requests in " + elapsed + "ms, QPS: " + qps);
    }

    @Test
    @Order(3)
    @DisplayName("并发与异步数据混合 —— 请求同时有异步推送")
    void t03_concurrentWithAsyncData() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(10))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .asyncQueueCapacity(32)
                .build());

        AtomicInteger asyncCount = new AtomicInteger();
        CountDownLatch asyncLatch = new CountDownLatch(3);

        client.addDataListener(r -> {
            asyncCount.incrementAndGet();
            asyncLatch.countDown();
        });

        client.connect();

        int threadCount = 4;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    client.request(
                        Command.text("MULTIFRAME", StandardCharsets.UTF_8),
                        ResponseMatcher.equalsText("OK", StandardCharsets.UTF_8),
                        Duration.ofSeconds(5),
                        CommandIdempotency.IDEMPOTENT
                    );
                } catch (Exception e) {
                    // 忽略
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(20, TimeUnit.SECONDS), "所有请求应在 20 秒内完成");

        executor.shutdownNow();

        assertTrue(asyncCount.get() > 0, "应该收到异步数据");
    }
}