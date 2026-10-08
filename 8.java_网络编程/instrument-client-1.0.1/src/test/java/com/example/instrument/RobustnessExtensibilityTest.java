package com.example.instrument;

import com.example.instrument.api.ClientInterceptor;
import com.example.instrument.api.InstrumentClient;
import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.config.ReconnectConfig;
import com.example.instrument.core.InstrumentClientImpl;
import com.example.instrument.diagnostics.ClientDiagnostics;
import com.example.instrument.exception.ConnectionException;
import com.example.instrument.model.Command;
import com.example.instrument.model.CommandIdempotency;
import com.example.instrument.model.Response;
import com.example.instrument.protocol.LineProtocol;
import com.example.instrument.protocol.Protocol;
import com.example.instrument.testing.InstrumentationServer;
import com.example.instrument.testing.MockConnection;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RobustnessExtensibilityTest {

    private static final int BASE_PORT = 19700;
    private static InstrumentationServer server;

    private InstrumentClient client;
    private Protocol lineProtocol;

    @BeforeAll
    static void startServer() throws Exception {
        server = new InstrumentationServer(BASE_PORT);
        server.start();
        Thread.sleep(200);
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
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
    @DisplayName("Builder 参数校验 - 负数超时抛出异常")
    void t01_builderValidation_negativeTimeout() {
        assertThrows(IllegalArgumentException.class, () ->
            ClientConfig.builder().connectTimeout(Duration.ofSeconds(-1)).build());
    }

    @Test
    @Order(2)
    @DisplayName("Builder 参数校验 - 零超时抛出异常")
    void t02_builderValidation_zeroTimeout() {
        assertThrows(IllegalArgumentException.class, () ->
            ClientConfig.builder().connectTimeout(Duration.ZERO).build());
    }

    @Test
    @Order(3)
    @DisplayName("Builder 参数校验 - 负数缓冲区抛出异常")
    void t03_builderValidation_negativeBuffer() {
        assertThrows(IllegalArgumentException.class, () ->
            ClientConfig.builder().receiveBufferSize(-1).build());
    }

    @Test
    @Order(4)
    @DisplayName("Builder 参数校验 - 零缓冲区抛出异常")
    void t04_builderValidation_zeroBuffer() {
        assertThrows(IllegalArgumentException.class, () ->
            ClientConfig.builder().receiveBufferSize(0).build());
    }

    @Test
    @Order(5)
    @DisplayName("Builder 参数校验 - 负数 socket 超时抛出异常")
    void t05_builderValidation_negativeSocketTimeout() {
        assertThrows(IllegalArgumentException.class, () ->
            ClientConfig.builder().socketReadTimeoutMillis(-1).build());
    }

    @Test
    @Order(6)
    @DisplayName("Builder 参数校验 - 零 socket 超时允许")
    void t06_builderValidation_zeroSocketTimeout() {
        assertDoesNotThrow(() ->
            ClientConfig.builder().socketReadTimeoutMillis(0).build());
    }

    @Test
    @Order(7)
    @DisplayName("拦截器 - 正常请求触发 beforeRequest 和 afterResponse")
    void t07_interceptor_normalFlow() throws Exception {
        List<String> events = new ArrayList<>();
        AtomicReference<Duration> latencyRef = new AtomicReference<>();

        ClientInterceptor interceptor = new ClientInterceptor() {
            @Override
            public void beforeRequest(Command command, CommandIdempotency idempotency) {
                events.add("before:" + new String(command.bytes(), StandardCharsets.UTF_8));
            }
            @Override
            public void afterResponse(Command command, Response response, Duration latency) {
                events.add("after:" + response.text(StandardCharsets.UTF_8));
                latencyRef.set(latency);
            }
            @Override
            public void afterFailure(Command command, Throwable throwable) {
                events.add("failure:" + throwable.getClass().getSimpleName());
            }
        };

        client = InstrumentClients.builder("localhost", BASE_PORT, lineProtocol)
            .connectTimeout(Duration.ofSeconds(5))
            .responseTimeout(Duration.ofSeconds(3))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        ((InstrumentClientImpl) client).addInterceptor(interceptor);
        client.connect();

        Response response = client.request(
            Command.text("IDN", StandardCharsets.UTF_8),
            ResponseMatcher.any(),
            Duration.ofSeconds(2),
            CommandIdempotency.IDEMPOTENT);

        assertNotNull(response);
        assertEquals(2, events.size());
        assertTrue(events.get(0).startsWith("before:"));
        assertTrue(events.get(1).startsWith("after:"));
        assertNotNull(latencyRef.get());
    }

    @Test
    @Order(8)
    @DisplayName("拦截器 - 失败请求触发 afterFailure")
    void t08_interceptor_failureFlow() {
        List<String> events = new ArrayList<>();

        ClientInterceptor interceptor = new ClientInterceptor() {
            @Override
            public void beforeRequest(Command command, CommandIdempotency idempotency) {
                events.add("before");
            }
            @Override
            public void afterFailure(Command command, Throwable throwable) {
                events.add("failure:" + throwable.getClass().getSimpleName());
            }
        };

        client = new InstrumentClientImpl(
            new InetSocketAddress("localhost", 1),
            lineProtocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofMillis(500))
                .responseTimeout(Duration.ofMillis(500))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());

        ((InstrumentClientImpl) client).addInterceptor(interceptor);

        assertThrows(ConnectionException.class, () ->
            client.request(
                Command.text("TEST"),
                ResponseMatcher.any(),
                Duration.ofMillis(500),
                CommandIdempotency.NON_IDEMPOTENT));

        assertTrue(events.contains("before"));
        assertTrue(events.stream().anyMatch(e -> e.startsWith("failure:")));
    }

    @Test
    @Order(9)
    @DisplayName("拦截器 - 拦截器抛异常不影响主流程")
    void t09_interceptor_exceptionDoesNotAffectFlow() throws Exception {
        client = InstrumentClients.builder("localhost", BASE_PORT, lineProtocol)
            .connectTimeout(Duration.ofSeconds(5))
            .responseTimeout(Duration.ofSeconds(3))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        ((InstrumentClientImpl) client).addInterceptor(new ClientInterceptor() {
            @Override
            public void beforeRequest(Command command, CommandIdempotency idempotency) {
                throw new RuntimeException("interceptor error");
            }
        });

        client.connect();

        assertDoesNotThrow(() ->
            client.request(Command.text("IDN"), ResponseMatcher.any(), Duration.ofSeconds(2)));
    }

    @Test
    @Order(10)
    @DisplayName("优雅关闭 - close 等待线程池终止")
    void t10_gracefulShutdown() throws Exception {
        client = InstrumentClients.builder("localhost", BASE_PORT, lineProtocol)
            .connectTimeout(Duration.ofSeconds(5))
            .responseTimeout(Duration.ofSeconds(3))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        client.connect();
        client.close();
        Thread.sleep(500);
        client = null;
    }

    @Test
    @Order(11)
    @DisplayName("MockConnection - 模拟正常读写")
    void t11_mockConnection_normalReadWrite() throws Exception {
        MockConnection conn = new MockConnection();
        conn.enqueueResponse(new byte[]{'O', 'K', '\r', '\n'});
        conn.connect();

        byte[] buffer = new byte[100];
        int n = conn.read(buffer);
        assertEquals(4, n);
        assertEquals('O', buffer[0]);

        conn.write(new byte[]{'T', 'E', 'S', 'T'});
        assertEquals(1, conn.sentCommandCount());
        assertArrayEquals(new byte[]{'T', 'E', 'S', 'T'}, conn.pollSentCommand());
    }

    @Test
    @Order(12)
    @DisplayName("MockConnection - 模拟连接失败")
    void t12_mockConnection_connectFailure() {
        MockConnection conn = new MockConnection();
        conn.setConnectFailure(true);
        assertThrows(com.example.instrument.exception.ConnectionException.class, conn::connect);
        assertFalse(conn.isConnected());
    }

    @Test
    @Order(13)
    @DisplayName("MockConnection - 模拟读取失败")
    void t13_mockConnection_readFailure() throws Exception {
        MockConnection conn = new MockConnection();
        conn.connect();
        conn.setReadFailure(new IOException("simulated read error"));
        byte[] buffer = new byte[100];
        assertThrows(IOException.class, () -> conn.read(buffer));
    }

    @Test
    @Order(14)
    @DisplayName("MockConnection - 模拟写入失败")
    void t14_mockConnection_writeFailure() throws Exception {
        MockConnection conn = new MockConnection();
        conn.connect();
        conn.setWriteFailure(new IOException("simulated write error"));
        assertThrows(IOException.class, () -> conn.write(new byte[]{0x01}));
    }

    @Test
    @Order(15)
    @DisplayName("诊断 - 记录状态变化")
    void t15_diagnostics_stateChanges() {
        InetSocketAddress addr = new InetSocketAddress("localhost", 5025);
        ClientDiagnostics diag = new ClientDiagnostics(addr);

        diag.recordConnectionStateChange(com.example.instrument.connection.ConnectionState.CONNECTING);
        diag.recordConnectionStateChange(com.example.instrument.connection.ConnectionState.CONNECTED);
        diag.recordConnectionStateChange(com.example.instrument.connection.ConnectionState.DISCONNECTED);

        ClientDiagnostics.DiagnosticReport report = diag.getReport();
        assertEquals(com.example.instrument.connection.ConnectionState.DISCONNECTED, report.currentState());
    }

    @Test
    @Order(16)
    @DisplayName("诊断 - 记录请求")
    void t16_diagnostics_requests() {
        InetSocketAddress addr = new InetSocketAddress("localhost", 5025);
        ClientDiagnostics diag = new ClientDiagnostics(addr);

        diag.recordRequestSent("IDN", System.nanoTime());
        diag.recordRequestCompleted("IDN", true, Duration.ofMillis(50));

        ClientDiagnostics.DiagnosticReport report = diag.getReport();
        assertEquals(1, report.totalRequests());
        assertEquals(0, report.totalErrors());
        assertEquals(1, report.recentRequests().size());
        assertTrue(report.recentRequests().get(0).isCompleted());
    }

    @Test
    @Order(17)
    @DisplayName("诊断 - 记录错误")
    void t17_diagnostics_errors() {
        InetSocketAddress addr = new InetSocketAddress("localhost", 5025);
        ClientDiagnostics diag = new ClientDiagnostics(addr);

        diag.recordError("connection refused");
        diag.recordError("timeout");

        ClientDiagnostics.DiagnosticReport report = diag.getReport();
        assertEquals(2, report.totalErrors());
        assertEquals("timeout", report.lastError());
    }

    @Test
    @Order(18)
    @DisplayName("诊断 - 清除数据")
    void t18_diagnostics_clear() {
        InetSocketAddress addr = new InetSocketAddress("localhost", 5025);
        ClientDiagnostics diag = new ClientDiagnostics(addr);

        diag.recordRequestSent("IDN", System.nanoTime());
        diag.recordError("test error");
        diag.clear();

        ClientDiagnostics.DiagnosticReport report = diag.getReport();
        assertEquals(0, report.totalRequests());
        assertEquals(0, report.totalErrors());
        assertNull(report.lastError());
    }

    @Test
    @Order(19)
    @DisplayName("InstrumentClients.builder - 流式构建")
    void t19_builderFluentApi() {
        InstrumentClient c = InstrumentClients.builder("localhost", 5025, lineProtocol)
            .connectTimeout(Duration.ofSeconds(10))
            .responseTimeout(Duration.ofSeconds(5))
            .reconnect(new ReconnectConfig(true, 5, Duration.ofSeconds(1), Duration.ofSeconds(30), 0.2))
            .build();

        assertNotNull(c);
        assertFalse(c.isConnected());
        c.close();
    }

    @Test
    @Order(20)
    @DisplayName("多次 close 不抛异常")
    void t20_multipleClose() throws Exception {
        client = InstrumentClients.builder("localhost", BASE_PORT, lineProtocol)
            .connectTimeout(Duration.ofSeconds(5))
            .responseTimeout(Duration.ofSeconds(3))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        client.connect();
        client.close();
        client.close();
        client.close();
        client = null;
    }

    @Test
    @Order(21)
    @DisplayName("拦截器 - 多个拦截器按顺序执行")
    void t21_multipleInterceptorsOrder() throws Exception {
        List<String> order = new ArrayList<>();

        ClientInterceptor i1 = new ClientInterceptor() {
            @Override public void beforeRequest(Command c, CommandIdempotency id) { order.add("1-before"); }
            @Override public void afterResponse(Command c, Response r, Duration l) { order.add("1-after"); }
        };

        ClientInterceptor i2 = new ClientInterceptor() {
            @Override public void beforeRequest(Command c, CommandIdempotency id) { order.add("2-before"); }
            @Override public void afterResponse(Command c, Response r, Duration l) { order.add("2-after"); }
        };

        client = InstrumentClients.builder("localhost", BASE_PORT, lineProtocol)
            .connectTimeout(Duration.ofSeconds(5))
            .responseTimeout(Duration.ofSeconds(3))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        InstrumentClientImpl impl = (InstrumentClientImpl) client;
        impl.addInterceptor(i1);
        impl.addInterceptor(i2);
        client.connect();
        client.request(Command.text("IDN"), ResponseMatcher.any(), Duration.ofSeconds(2));

        assertEquals(4, order.size());
        assertEquals("1-before", order.get(0));
        assertEquals("2-before", order.get(1));
        assertEquals("1-after", order.get(2));
        assertEquals("2-after", order.get(3));
    }

    @Test
    @Order(22)
    @DisplayName("拦截器 - 移除拦截器后不再触发")
    void t22_removeInterceptor() throws Exception {
        AtomicInteger callCount = new AtomicInteger();

        ClientInterceptor interceptor = new ClientInterceptor() {
            @Override public void beforeRequest(Command c, CommandIdempotency id) { callCount.incrementAndGet(); }
        };

        client = InstrumentClients.builder("localhost", BASE_PORT, lineProtocol)
            .connectTimeout(Duration.ofSeconds(5))
            .responseTimeout(Duration.ofSeconds(3))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        InstrumentClientImpl impl = (InstrumentClientImpl) client;
        impl.addInterceptor(interceptor);
        client.connect();

        client.request(Command.text("IDN"), ResponseMatcher.any(), Duration.ofSeconds(2));
        assertEquals(1, callCount.get());

        impl.removeInterceptor(interceptor);
        client.request(Command.text("IDN"), ResponseMatcher.any(), Duration.ofSeconds(2));
        assertEquals(1, callCount.get());
    }
}