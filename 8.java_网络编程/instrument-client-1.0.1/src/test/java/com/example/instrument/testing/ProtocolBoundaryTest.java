package com.example.instrument.testing;

import com.example.instrument.factory.InstrumentClients;
import com.example.instrument.api.InstrumentClient;
import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.config.ReconnectConfig;
import com.example.instrument.exception.ConnectionException;
import com.example.instrument.exception.RequestTimeoutException;
import com.example.instrument.model.Command;
import com.example.instrument.model.CommandIdempotency;
import com.example.instrument.model.Response;
import com.example.instrument.protocol.LengthFieldProtocol;
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
 * 协议边界条件测试。
 *
 * <p>覆盖半包、粘包、空帧、超大帧、噪声数据等边界场景：</p>
 * <ul>
 *   <li>半包：单字节到达、逐字节到达</li>
 *   <li>粘包：多帧同时到达、超大粘包</li>
 *   <li>空帧：空数据、纯分隔符</li>
 *   <li>噪声：非协议格式数据</li>
 *   <li>超大帧：超过 maxFrameLength</li>
 *   <li>二进制协议：STX/ETX 边界、校验和</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProtocolBoundaryTest {

    private static final int BASE_PORT = 19900;
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
    @DisplayName("半包 - 逐字节到达的 FRAGMENTED 命令")
    void t01_fragmentedByteByByte() throws Exception {
        client = InstrumentClients.builder("localhost", BASE_PORT, lineProtocol)
            .connectTimeout(Duration.ofSeconds(3))
            .responseTimeout(Duration.ofSeconds(5))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        client.connect();

        Response response = client.request(
            Command.text("FRAGMENTED"),
            ResponseMatcher.any(),
            Duration.ofSeconds(5),
            CommandIdempotency.IDEMPOTENT
        );

        assertNotNull(response);
        assertEquals("FRAGMENTED_RESPONSE", response.text());
    }

    @Test
    @Order(2)
    @DisplayName("粘包 - MULTIFRAME 命令返回多帧")
    void t02_stickyPackets() throws Exception {
        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(3))
            .responseTimeout(Duration.ofSeconds(5))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .asyncQueueCapacity(16)
            .build();

        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol, config);

        CountDownLatch asyncLatch = new CountDownLatch(2);
        AtomicInteger asyncCount = new AtomicInteger();

        client.addDataListener(r -> {
            asyncCount.incrementAndGet();
            asyncLatch.countDown();
        });

        client.connect();

        Response response = client.request(
            Command.text("MULTIFRAME"),
            ResponseMatcher.equalsText("OK"),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        assertEquals("OK", response.text());
        assertTrue(asyncLatch.await(3, TimeUnit.SECONDS), "应收到 2 个异步帧");
        assertEquals(2, asyncCount.get());
    }

    @Test
    @Order(3)
    @DisplayName("多帧一次到达 - STICKY2 命令两帧同包")
    void t03_multiFrameAtOnce() throws Exception {
        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(3))
            .responseTimeout(Duration.ofSeconds(5))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .asyncQueueCapacity(16)
            .build();

        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol, config);

        CountDownLatch frame2Latch = new CountDownLatch(1);

        client.addDataListener(r -> {
            if ("FRAME2".equals(r.text())) {
                frame2Latch.countDown();
            }
        });

        client.connect();

        Response response = client.request(
            Command.text("STICKY2"),
            ResponseMatcher.any(),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        assertEquals("FRAME1", response.text());
        assertTrue(frame2Latch.await(3, TimeUnit.SECONDS), "应收到 FRAME2");
    }

    @Test
    @Order(4)
    @DisplayName("超大帧 - 超过 maxFrameLength 触发异常")
    void t04_oversizedFrame() throws Exception {
        LineProtocol smallLimit = LineProtocol.crlf(StandardCharsets.UTF_8).withMaxFrameLength(64);

        client = InstrumentClients.builder("localhost", BASE_PORT, smallLimit)
            .connectTimeout(Duration.ofSeconds(3))
            .responseTimeout(Duration.ofSeconds(2))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        client.connect();

        assertThrows(ConnectionException.class, () ->
            client.request(
                Command.text("HUGE:2000"),
                ResponseMatcher.any(),
                Duration.ofSeconds(2),
                CommandIdempotency.NON_IDEMPOTENT
            )
        );
    }

    @Test
    @Order(5)
    @DisplayName("二进制协议 - 校验和错误帧被跳过")
    void t05_binaryChecksumError() throws Exception {
        Protocol binaryProtocol = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);

        client = InstrumentClients.builder("localhost", BASE_PORT, binaryProtocol)
            .connectTimeout(Duration.ofSeconds(3))
            .responseTimeout(Duration.ofSeconds(2))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        client.connect();

        assertThrows(RequestTimeoutException.class, () ->
            client.request(
                Command.text("BADCHK"),
                ResponseMatcher.any(),
                Duration.ofMillis(500),
                CommandIdempotency.NON_IDEMPOTENT
            )
        );
    }

    @Test
    @Order(7)
    @DisplayName("空响应 - 服务器返回空行")
    void t07_emptyResponse() throws Exception {
        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(3))
            .responseTimeout(Duration.ofSeconds(3))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol, config);

        client.connect();

        Response response = client.request(
            Command.text("EMPTY"),
            ResponseMatcher.any(),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        assertNotNull(response);
    }

    @Test
    @Order(8)
    @DisplayName("噪声数据 - 非协议格式数据被忽略")
    void t08_noiseDataIgnored() throws Exception {
        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(3))
            .responseTimeout(Duration.ofSeconds(5))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol, config);

        client.connect();

        Response response = client.request(
            Command.text("IDN"),
            ResponseMatcher.any(),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        assertNotNull(response);
        assertTrue(response.text().contains("TestServer"));
    }

    @Test
    @Order(9)
    @DisplayName("半包边界 - 分隔符跨缓冲区")
    void t09_delimiterAcrossBuffer() throws Exception {
        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(3))
            .responseTimeout(Duration.ofSeconds(5))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol, config);

        client.connect();

        Response response = client.request(
            Command.text("FRAGMENTED"),
            ResponseMatcher.any(),
            Duration.ofSeconds(5),
            CommandIdempotency.IDEMPOTENT
        );

        assertNotNull(response);
    }

    @Test
    @Order(10)
    @DisplayName("ResponseMatcher 组合器 - and/or/negate 链式验证")
    void t10_responseMatcherCombinators() throws Exception {
        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(3))
            .responseTimeout(Duration.ofSeconds(3))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol, config);

        client.connect();

        Response response = client.request(
            Command.text("IDN"),
            ResponseMatcher.contains("TestServer")
                .and(ResponseMatcher.contains("Model-1000"))
                .and(ResponseMatcher.contains("ERROR").negate()),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        assertNotNull(response);
    }
}