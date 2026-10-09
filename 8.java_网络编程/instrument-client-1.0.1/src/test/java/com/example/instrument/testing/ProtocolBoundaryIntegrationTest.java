package com.example.instrument.testing;

import static org.junit.jupiter.api.Assertions.*;

import com.example.instrument.InstrumentationServer;
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
 * 协议边界集成测试。
 *
 * <p>覆盖粘包、拆包、多帧同时到达等网络边界场景：</p>
 * <ul>
 *   <li>粘包：一缓冲区内多帧被正确拆解</li>
 *   <li>拆包：逐字节到达也能正确还原</li>
 *   <li>多帧一次到达：STICKY2 命令两帧同包</li>
 *   <li>超大帧：超过 maxFrameLength 触发异常</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProtocolBoundaryIntegrationTest {

    private static final int BASE_PORT = 19605;
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
    @DisplayName("粘包 —— 一缓冲区内多帧被 LineProtocol 正确拆解")
    void t01_stickyPackets() throws Exception {
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

    @Test
    @Order(2)
    @DisplayName("拆包 —— 逐字节到达也能正确还原完整响应")
    void t02_fragmentedPackets() {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        client.connect();

        Response response = client.request(
            Command.text("FRAGMENTED", StandardCharsets.UTF_8),
            ResponseMatcher.any(),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        assertNotNull(response);
        assertEquals("FRAGMENTED_RESPONSE", response.text(StandardCharsets.UTF_8));
    }

    @Test
    @Order(3)
    @DisplayName("多帧一次到达 —— STICKY2 命令两帧同包到达")
    void t03_multiFrameAtOnce() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .asyncQueueCapacity(16)
                .build());

        CountDownLatch frame2Latch = new CountDownLatch(1);

        client.addDataListener(r -> {
            String text = r.text(StandardCharsets.UTF_8);
            if ("FRAME2".equals(text)) frame2Latch.countDown();
        });

        client.connect();

        Response response = client.request(
            Command.text("STICKY2", StandardCharsets.UTF_8),
            ResponseMatcher.any(),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        assertEquals("FRAME1", response.text(StandardCharsets.UTF_8));

        assertTrue(frame2Latch.await(2, TimeUnit.SECONDS), "应该收到 FRAME2");
    }

    @Test
    @Order(4)
    @DisplayName("超大帧 —— 超过 maxFrameLength 触发 ConnectionException")
    void t04_oversizedFrame() {
        Protocol smallLimit = LineProtocol.crlf(StandardCharsets.UTF_8).withMaxFrameLength(64);
        client = InstrumentClients.tcp("localhost", BASE_PORT, smallLimit,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(2))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        client.connect();

        assertThrows(ConnectionException.class, () -> {
            client.request(
                Command.text("HUGE:2000", StandardCharsets.UTF_8),
                ResponseMatcher.any(),
                Duration.ofSeconds(1),
                CommandIdempotency.NON_IDEMPOTENT
            );
        });
    }

    @Test
    @Order(5)
    @DisplayName("半包场景 —— 服务器分批发送数据")
    void t05_halfPacketScenario() {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        client.connect();

        Response response = client.request(
            Command.text("FRAGMENTED", StandardCharsets.UTF_8),
            ResponseMatcher.any(),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        assertNotNull(response);
        assertEquals("FRAGMENTED_RESPONSE", response.text(StandardCharsets.UTF_8));
    }
}