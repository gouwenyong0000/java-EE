package com.example.instrument.testing;

import com.example.instrument.InstrumentationServer;
import com.example.instrument.api.InstrumentClient;
import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.config.ReconnectConfig;
import com.example.instrument.exception.RequestTimeoutException;
import com.example.instrument.factory.InstrumentClients;
import com.example.instrument.model.Command;
import com.example.instrument.model.CommandIdempotency;
import com.example.instrument.model.Response;
import com.example.instrument.protocol.LineProtocol;
import com.example.instrument.protocol.Protocol;

import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 客户端请求响应测试。
 *
 * <p>覆盖正常请求响应、超时、ResponseMatcher 匹配等场景：</p>
 * <ul>
 *   <li>正常 request-response</li>
 *   <li>response timeout</li>
 *   <li>ResponseMatcher 精确匹配</li>
 *   <li>ResponseMatcher 任意匹配</li>
 *   <li>多命令连续请求</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClientRequestTest {

    private static final int BASE_PORT = 19601;
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
    @DisplayName("正常请求响应 —— IDN 命令返回仪器型号")
    void t01_normalRequestResponse() {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(3))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        client.connect();

        Response response = client.request(
            Command.text("IDN", StandardCharsets.UTF_8),
            ResponseMatcher.any(),
            Duration.ofSeconds(2),
            CommandIdempotency.IDEMPOTENT
        );

        assertNotNull(response);
        assertEquals("TestServer,Model-1000,SN00000001,1.0.0", response.text(StandardCharsets.UTF_8));
    }

    @Test
    @Order(2)
    @DisplayName("ResponseMatcher 精确匹配 —— equalsText 匹配成功")
    void t02_responseMatcherEqualsText() {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(3))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        client.connect();

        Response response = client.request(
            Command.text("MULTIFRAME", StandardCharsets.UTF_8),
            ResponseMatcher.equalsText("OK", StandardCharsets.UTF_8),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );

        assertEquals("OK", response.text(StandardCharsets.UTF_8));
    }

    @Test
    @Order(3)
    @DisplayName("ResponseMatcher 任意匹配 —— any 总是返回第一个响应")
    void t03_responseMatcherAny() {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(3))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        client.connect();

        Response response = client.request(
            Command.text("IDN", StandardCharsets.UTF_8),
            ResponseMatcher.any(),
            Duration.ofSeconds(2),
            CommandIdempotency.IDEMPOTENT
        );

        assertNotNull(response);
        assertTrue(response.text(StandardCharsets.UTF_8).contains("TestServer"));
    }

    @Test
    @Order(4)
    @DisplayName("ResponseMatcher 包含匹配 —— containsText 部分匹配")
    void t04_responseMatcherContainsText() {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(3))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        client.connect();

        Response response =
                client.request(
                        Command.text("IDN", StandardCharsets.UTF_8),
                        ResponseMatcher.contains("TestServer", StandardCharsets.UTF_8),
                        Duration.ofSeconds(2),
                        CommandIdempotency.IDEMPOTENT);

        assertNotNull(response);
        assertTrue(response.text(StandardCharsets.UTF_8).contains("TestServer"));
    }

    @Test
    @Order(5)
    @DisplayName("请求超时 —— 服务器延迟大、超时时间短")
    void t05_requestTimeout() {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(2))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        client.connect();

        assertThrows(RequestTimeoutException.class, () -> {
            client.request(
                Command.text("DELAY:500", StandardCharsets.UTF_8),
                ResponseMatcher.any(),
                Duration.ofMillis(100),
                CommandIdempotency.NON_IDEMPOTENT
            );
        });
    }

    @Test
    @Order(6)
    @DisplayName("连续多次请求 —— 每次请求独立响应")
    void t06_multipleSequentialRequests() {
        client = InstrumentClients.tcp("localhost", BASE_PORT, protocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(3))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        client.connect();

        for (int i = 0; i < 3; i++) {
            Response response = client.request(
                Command.text("IDN", StandardCharsets.UTF_8),
                ResponseMatcher.any(),
                Duration.ofSeconds(2),
                CommandIdempotency.IDEMPOTENT
            );

            assertNotNull(response);
            assertTrue(response.text(StandardCharsets.UTF_8).contains("TestServer"));
        }
    }
}