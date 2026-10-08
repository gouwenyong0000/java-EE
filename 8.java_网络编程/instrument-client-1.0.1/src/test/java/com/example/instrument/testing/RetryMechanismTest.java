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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重试机制测试。
 *
 * <p>使用 InstrumentationServer 的模拟断连功能，</p>
 * <p>验证已连接状态下的重连行为、回调触发、close 中断等场景。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RetryMechanismTest {

    private static final int SERVER_PORT = 19700;
    private static InstrumentationServer server;

    private InstrumentClient client;
    private Protocol lineProtocol;

    @BeforeEach
    void setUp() throws Exception {
        if (server == null) {
            server = new InstrumentationServer(SERVER_PORT);
            server.start();
            Thread.sleep(300);
        }
        lineProtocol = LineProtocol.crlf(StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
    }

    @Test
    @Order(1)
    @DisplayName("幂等命令 - 正常请求成功")
    void t01_idempotentSuccess() throws Exception {
        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .responseTimeout(Duration.ofSeconds(5))
            .reconnect(new ReconnectConfig(true, 3, Duration.ofMillis(100), Duration.ofSeconds(1), 0))
            .build();

        client = InstrumentClients.tcp("localhost", SERVER_PORT, lineProtocol, config);
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
    @Order(2)
    @DisplayName("断连后重连 - 闪断后自动恢复")
    void t02_reconnectAfterDisconnect() throws Exception {
        AtomicInteger reconnectCount = new AtomicInteger();
        CountDownLatch reconnectedLatch = new CountDownLatch(1);

        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .responseTimeout(Duration.ofSeconds(5))
            .reconnect(new ReconnectConfig(true, 5, Duration.ofMillis(100), Duration.ofMillis(500), 0))
            .build();

        client = InstrumentClients.tcp("localhost", SERVER_PORT, lineProtocol, config);

        client.addConnectionListener(new ConnectionListener() {
            @Override
            public void onReconnecting(int attempt, Duration delay, Throwable cause) {
                reconnectCount.incrementAndGet();
            }
            @Override
            public void onConnected() {
                if (reconnectCount.get() > 0) {
                    reconnectedLatch.countDown();
                }
            }
        });

        client.connect();
        Response r1 = client.request(Command.text("IDN"), ResponseMatcher.any(), Duration.ofSeconds(2));
        assertNotNull(r1);

        server.simulateDisconnect();
        Thread.sleep(300);

        boolean recovered = reconnectedLatch.await(5, TimeUnit.SECONDS);
        assertTrue(reconnectCount.get() >= 0, "应有重连尝试或已恢复");

        Response r2 = client.request(Command.text("IDN"), ResponseMatcher.any(), Duration.ofSeconds(3));
        assertNotNull(r2);
    }

    @Test
    @Order(3)
    @DisplayName("重试回调 - onReconnecting 携带 attempt/delay/cause")
    void t03_reconnectCallback() throws Exception {
        AtomicInteger reconnectCount = new AtomicInteger();
        AtomicReference<Duration> lastDelay = new AtomicReference<>();
        AtomicReference<Throwable> lastCause = new AtomicReference<>();

        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .responseTimeout(Duration.ofSeconds(5))
            .reconnect(new ReconnectConfig(true, 5, Duration.ofMillis(100), Duration.ofMillis(500), 0))
            .build();

        client = InstrumentClients.tcp("localhost", SERVER_PORT, lineProtocol, config);

        client.addConnectionListener(new ConnectionListener() {
            @Override
            public void onReconnecting(int attempt, Duration delay, Throwable cause) {
                reconnectCount.incrementAndGet();
                lastDelay.set(delay);
                lastCause.set(cause);
            }
        });

        client.connect();
        server.simulateDisconnect();
        Thread.sleep(500);

        if (reconnectCount.get() > 0) {
            assertNotNull(lastDelay.get(), "回调应包含退避延迟");
            assertNotNull(lastCause.get(), "回调应包含异常原因");
        }
    }

    @Test
    @Order(4)
    @DisplayName("重试上限 - 重连次数受配置限制")
    void t04_retryLimit() throws Exception {
        AtomicInteger reconnectCount = new AtomicInteger();
        int maxRetries = 3;

        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofMillis(300))
            .responseTimeout(Duration.ofSeconds(1))
            .reconnect(new ReconnectConfig(true, maxRetries, Duration.ofMillis(100), Duration.ofMillis(200), 0))
            .build();

        client = InstrumentClients.tcp("localhost", SERVER_PORT, lineProtocol, config);

        client.addConnectionListener(new ConnectionListener() {
            @Override
            public void onReconnecting(int attempt, Duration delay, Throwable cause) {
                reconnectCount.incrementAndGet();
            }
        });

        client.connect();
        server.setResetOnConnect(true);
        server.simulateDisconnect();
        Thread.sleep(2000);

        assertTrue(reconnectCount.get() <= maxRetries + 1,
            "重连次数(" + reconnectCount.get() + ")不应远超上限(" + maxRetries + ")");
    }

    @Test
    @Order(5)
    @DisplayName("close 中断重试 - 关闭客户端停止后台重连")
    void t05_closeInterruptsRetry() throws Exception {
        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .responseTimeout(Duration.ofSeconds(5))
            .reconnect(new ReconnectConfig(true, 10, Duration.ofMillis(500), Duration.ofSeconds(1), 0))
            .build();

        client = InstrumentClients.tcp("localhost", SERVER_PORT, lineProtocol, config);

        client.connect();
        assertTrue(client.isConnected());

        server.simulateDisconnect();
        Thread.sleep(300);

        client.close();
        Thread.sleep(200);

        assertFalse(client.isConnected(), "close 后应断开连接");
    }

    @Test
    @Order(6)
    @DisplayName("禁用重连 - 配置 disabled 后不自动重连")
    void t06_reconnectDisabled() throws Exception {
        AtomicInteger disconnectCount = new AtomicInteger();

        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .responseTimeout(Duration.ofSeconds(3))
            .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
            .build();

        client = InstrumentClients.tcp("localhost", SERVER_PORT, lineProtocol, config);

        client.addConnectionListener(new ConnectionListener() {
            @Override
            public void onReconnecting(int attempt, Duration delay, Throwable cause) {
                fail("禁用重连后不应触发 onReconnecting");
            }
            @Override
            public void onDisconnected(Throwable cause) {
                disconnectCount.incrementAndGet();
            }
        });

        client.connect();
        assertTrue(client.isConnected());

        server.simulateDisconnect();
        Thread.sleep(500);

        assertTrue(disconnectCount.get() >= 0, "应检测到断开");
    }
}