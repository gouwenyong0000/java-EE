package com.example.instrument.testing;

import com.example.instrument.InstrumentationServer;
import com.example.instrument.api.BlockingDataListener;
import com.example.instrument.api.DataListener;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 客户端异步数据测试。
 *
 * <p>覆盖异步数据推送、监听器、队列溢出等场景：</p>
 * <ul>
 *   <li>异步数据与 response 交错</li>
 *   <li>仅异步数据无 response</li>
 *   <li>多个 DataListener 独立接收</li>
 *   <li>监听器异常隔离</li>
 *   <li>BlockingDataListener poll 模式</li>
 *   <li>队列溢出策略</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClientAsyncDataTest {

    private static final int BASE_PORT = 19602;
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
    @DisplayName("异步数据与 response 交错 —— ResponseMatcher 跳过异步帧")
    void t01_asyncDataInterleavedWithResponse() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        CountDownLatch data1Latch = new CountDownLatch(1);
        CountDownLatch data2Latch = new CountDownLatch(1);
        List<String> receivedAsync = new ArrayList<>();

        client.addDataListener(response -> {
            String text = response.text(StandardCharsets.UTF_8);
            synchronized (receivedAsync) {
                receivedAsync.add(text);
            }
            if ("DATA1".equals(text)) data1Latch.countDown();
            if ("DATA2".equals(text)) data2Latch.countDown();
        });

        client.connect();

        Response response = client.request(
            Command.text("MULTIFRAME", StandardCharsets.UTF_8),
            ResponseMatcher.equalsText("OK", StandardCharsets.UTF_8),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        assertEquals("OK", response.text(StandardCharsets.UTF_8));

        assertTrue(data1Latch.await(2, TimeUnit.SECONDS), "应该收到 DATA1");
        assertTrue(data2Latch.await(2, TimeUnit.SECONDS), "应该收到 DATA2");

        synchronized (receivedAsync) {
            assertTrue(receivedAsync.contains("DATA1"));
            assertTrue(receivedAsync.contains("DATA2"));
        }
    }

    @Test
    @Order(2)
    @DisplayName("仅异步数据 —— BlockingDataListener 收到服务器推送")
    void t02_onlyAsyncData() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .asyncQueueCapacity(16)
                .build());

        BlockingDataListener listener = client.blockingDataListener();
        client.addDataListener(listener);

        client.connect();

        client.send(Command.text("IDN", StandardCharsets.UTF_8));

        Response response = listener.poll(Duration.ofSeconds(2));
        assertNotNull(response, "应该收到异步数据");
        assertTrue(response.text(StandardCharsets.UTF_8).contains("TestServer"));
    }

    @Test
    @Order(3)
    @DisplayName("多个 DataListener —— 同时收到异步数据")
    void t03_multipleDataListeners() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicInteger count1 = new AtomicInteger();
        AtomicInteger count2 = new AtomicInteger();

        client.addDataListener(response -> {
            count1.incrementAndGet();
            latch1.countDown();
        });
        client.addDataListener(response -> {
            count2.incrementAndGet();
            latch2.countDown();
        });

        client.connect();

        client.request(
            Command.text("MULTIFRAME", StandardCharsets.UTF_8),
            ResponseMatcher.equalsText("OK", StandardCharsets.UTF_8),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        assertTrue(latch1.await(2, TimeUnit.SECONDS), "listener1 应收到数据");
        assertTrue(latch2.await(2, TimeUnit.SECONDS), "listener2 应收到数据");
        assertTrue(count1.get() > 0);
        assertTrue(count2.get() > 0);
    }

    @Test
    @Order(4)
    @DisplayName("监听器异常隔离 —— 一个监听器抛异常不影响其他")
    void t04_listenerExceptionIsolation() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);

        client.addDataListener(response -> {
            latch1.countDown();
            throw new RuntimeException("listener 1 crashed");
        });
        client.addDataListener(response -> {
            latch2.countDown();
        });

        client.connect();

        client.request(
            Command.text("MULTIFRAME", StandardCharsets.UTF_8),
            ResponseMatcher.equalsText("OK", StandardCharsets.UTF_8),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        assertTrue(latch1.await(2, TimeUnit.SECONDS), "listener1 仍应被调用");
        assertTrue(latch2.await(2, TimeUnit.SECONDS), "listener2 不应受影响");
    }

    @Test
    @Order(5)
    @DisplayName("队列溢出 DROP_NEWEST —— 不抛异常丢弃最新数据")
    void t05_overflowPolicyDropNewest() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .asyncQueueCapacity(1)
                .overflowPolicy(ClientConfig.OverflowPolicy.DROP_NEWEST)
                .build());

        CountDownLatch countLatch = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();

        client.addDataListener(r -> {
            count.incrementAndGet();
            countLatch.countDown();
        });

        client.connect();

        assertDoesNotThrow(() -> {
            client.request(
                Command.text("MULTIFRAME", StandardCharsets.UTF_8),
                ResponseMatcher.equalsText("OK", StandardCharsets.UTF_8),
                Duration.ofSeconds(3),
                CommandIdempotency.IDEMPOTENT
            );
        });

        assertTrue(countLatch.await(2, TimeUnit.SECONDS));
    }

    @Test
    @Order(6)
    @DisplayName("异步数据捕获 —— 多帧异步数据正确接收")
    void t06_multipleAsyncFrames() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .asyncQueueCapacity(16)
                .build());

        CountDownLatch countLatch = new CountDownLatch(2);
        AtomicInteger asyncCount = new AtomicInteger();

        client.addDataListener(r -> {
            asyncCount.incrementAndGet();
            countLatch.countDown();
        });

        client.connect();

        Response first = client.request(
            Command.text("MULTIFRAME", StandardCharsets.UTF_8),
            ResponseMatcher.equalsText("OK", StandardCharsets.UTF_8),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        assertEquals("OK", first.text(StandardCharsets.UTF_8));

        assertTrue(countLatch.await(2, TimeUnit.SECONDS), "应该收到 2 个异步帧");
        assertEquals(2, asyncCount.get());
    }
}