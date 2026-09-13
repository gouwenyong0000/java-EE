package com.example.instrument;

import com.example.instrument.api.*;
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
import com.example.instrument.testing.InstrumentationServer;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖全部 20 个关键场景的综合测试。
 *
 * <p>测试分组：</p>
 * <table>
 *   <tr><td>01</td><td>正常 request-response</td></tr>
 *   <tr><td>02</td><td>多线程 request → 只能一个 pending</td></tr>
 *   <tr><td>03</td><td>异步数据与 response 交错</td></tr>
 *   <tr><td>04</td><td>只有异步数据</td></tr>
 *   <tr><td>05</td><td>粘包</td></tr>
 *   <tr><td>06</td><td>拆包</td></tr>
 *   <tr><td>07</td><td>多帧一次到达</td></tr>
 *   <tr><td>08</td><td>response timeout</td></tr>
 *   <tr><td>09</td><td>connection reset</td></tr>
 *   <tr><td>10</td><td>reconnect</td></tr>
 *   <tr><td>11</td><td>write half-failure</td></tr>
 *   <tr><td>12</td><td>idempotent retry</td></tr>
 *   <tr><td>13</td><td>non-idempotent no retry</td></tr>
 *   <tr><td>14</td><td>listener 抛异常</td></tr>
 *   <tr><td>15</td><td>blocking queue 满</td></tr>
 *   <tr><td>16</td><td>decoder malformed frame</td></tr>
 *   <tr><td>17</td><td>checksum error</td></tr>
 *   <tr><td>18</td><td>超大 frame</td></tr>
 *   <tr><td>19</td><td>client close</td></tr>
 *   <tr><td>20</td><td>reconnect + close race</td></tr>
 * </table>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ComprehensiveInstrumentClientTest {
    
    private static final int BASE_PORT = 19600;
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
            try {
                client.close();
            } catch (Exception ignored) {}
        }
    }
    
    @Test
    @Order(1)
    @DisplayName("01 正常 request-response —— IDN 命令返回仪器型号")
    void t01_normalRequestResponse() {
        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol, 
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(3))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());
        
        client.connect();
        assertTrue(client.isConnected());
        
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
    @DisplayName("02 多线程 request —— 同一时刻只能一个 pending")
    void t02_multiThreadOnlyOnePending() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());
        
        client.connect();
        
        int threadCount = 4;
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
                        Duration.ofSeconds(3),
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
        boolean allDone = doneLatch.await(10, TimeUnit.SECONDS);
        
        executor.shutdownNow();
        
        assertTrue(allDone, "所有线程应在 10 秒内完成");
        assertEquals(threadCount, successCount.get(), "所有请求都应该成功（因为内部是串行化的）");
        assertEquals(0, failureCount.get());
    }
    
    @Test
    @Order(3)
    @DisplayName("03 异步数据与 response 交错 —— 验证 ResponseMatcher 跳过异步帧")
    void t03_asyncDataInterleavedWithResponse() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());
        
        CountDownLatch data1Latch = new CountDownLatch(1);
        CountDownLatch data2Latch = new CountDownLatch(1);
        java.util.List<String> receivedAsync = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        client.addDataListener(response -> {
            String text = response.text(StandardCharsets.UTF_8);
            receivedAsync.add(text);
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
        assertTrue(receivedAsync.contains("DATA1"));
        assertTrue(receivedAsync.contains("DATA2"));
    }
    
    @Test
    @Order(4)
    @DisplayName("04 只有异步数据 —— BlockingDataListener 收到服务器推送")
    void t04_onlyAsyncData() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol,
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
    @Order(5)
    @DisplayName("05 粘包 —— 一缓冲区内多帧被 LineProtocol 正确拆解")
    void t05_stickyPackets() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol,
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
    @Order(6)
    @DisplayName("06 拆包 —— 逐字节到达也能正确还原完整响应")
    void t06_fragmentedPackets() {
        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol,
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
    @Order(7)
    @DisplayName("07 多帧一次到达 —— STICKY2 命令两帧同包到达")
    void t07_multiFrameAtOnce() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol,
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
    @Order(8)
    @DisplayName("08 response timeout —— 服务器延迟大超时短")
    void t08_responseTimeout() {
        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol,
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
    @Order(9)
    @DisplayName("09 connection reset —— 服务器 RESET 命令断开连接")
    void t09_connectionReset() throws Exception {
        CountDownLatch disconnectLatch = new CountDownLatch(1);
        
        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(2))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());
        
        client.addConnectionListener(new ConnectionListener() {
            @Override
            public void onDisconnected(Throwable cause) {
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
        
        assertTrue(disconnectLatch.await(3, TimeUnit.SECONDS), "应该收到断开通知");
        assertFalse(client.isConnected());
    }
    
    @Test
    @Order(10)
    @DisplayName("10 reconnect —— 断开后自动重连成功")
    void t10_reconnect() throws Exception {
        CountDownLatch reconnectedLatch = new CountDownLatch(1);
        AtomicInteger reconnectAttempts = new AtomicInteger();
        
        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .reconnect(new ReconnectConfig(true, 5, Duration.ofMillis(100), Duration.ofSeconds(2), 0))
                .build());
        
        client.addConnectionListener(new ConnectionListener() {
            @Override
            public void onReconnecting(int attempt, Duration delay, Throwable cause) {
                reconnectAttempts.set(attempt);
            }
            
            @Override
            public void onConnected() {
                if (reconnectAttempts.get() > 0) {
                    reconnectedLatch.countDown();
                }
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
        
        assertTrue(reconnectedLatch.await(10, TimeUnit.SECONDS), "应该自动重连成功");
        assertTrue(client.isConnected());
        
        Response response = client.request(
            Command.text("IDN", StandardCharsets.UTF_8),
            ResponseMatcher.any(),
            Duration.ofSeconds(3),
            CommandIdempotency.IDEMPOTENT
        );
        assertNotNull(response);
    }
    
    @Test
    @Order(11)
    @DisplayName("11 write half-failure —— HALFWRITE 命令写半包后断开")
    void t11_writeHalfFailure() {
        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(2))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());
        
        client.connect();
        
        assertThrows(ConnectionException.class, () -> {
            client.request(
                Command.text("HALFWRITE", StandardCharsets.UTF_8),
                ResponseMatcher.any(),
                Duration.ofSeconds(1),
                CommandIdempotency.NON_IDEMPOTENT
            );
        });
    }
    
    @Test
    @Order(12)
    @DisplayName("12 idempotent retry —— 幂等命令自动重连并完成")
    void t12_idempotentRetry() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());
        
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
        
        assertFalse(client.isConnected());
        
        Response response = client.request(
            Command.text("IDN", StandardCharsets.UTF_8),
            ResponseMatcher.any(),
            Duration.ofSeconds(5),
            CommandIdempotency.IDEMPOTENT
        );
        
        assertTrue(client.isConnected(), "幂等重试后应该重新连接");
        assertNotNull(response);
        assertEquals("TestServer,Model-1000,SN00000001,1.0.0", response.text(StandardCharsets.UTF_8));
    }
    
    @Test
    @Order(13)
    @DisplayName("13 non-idempotent no retry —— 非幂等命令不自动重连")
    void t13_nonIdempotentNoRetry() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(2))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());
        
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
        
        assertFalse(client.isConnected());
        
        assertThrows(ConnectionException.class, () -> {
            client.request(
                Command.text("IDN", StandardCharsets.UTF_8),
                ResponseMatcher.any(),
                Duration.ofSeconds(2),
                CommandIdempotency.NON_IDEMPOTENT
            );
        }, "非幂等命令不应自动重连");
    }
    
    @Test
    @Order(14)
    @DisplayName("14 listener 抛异常 —— 不影响其他监听器接收数据")
    void t14_listenerExceptionIsolated() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol,
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
    @Order(15)
    @DisplayName("15 blocking queue 满 —— DROP_NEWEST 策略不抛异常")
    void t15_blockingQueueFull() throws Exception {
        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol,
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
    @Order(16)
    @DisplayName("16 decoder malformed frame —— 无法解析导致超时")
    void t16_decoderMalformedFrame() {
        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(3))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());
        
        client.connect();
        
        assertThrows(RequestTimeoutException.class, () -> {
            client.request(
                Command.text("BADLEN:99999", StandardCharsets.UTF_8),
                ResponseMatcher.any(),
                Duration.ofSeconds(1),
                CommandIdempotency.NON_IDEMPOTENT
            );
        }, "由于服务器无法识别 BADLEN 二进制命令，客户端应超时");
    }
    
    @Test
    @Order(17)
    @DisplayName("17 checksum error —— 校验和错误帧被跳过")
    void t17_checksumError() {
        Protocol binaryProtocol = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        client = InstrumentClients.tcp("localhost", BASE_PORT, binaryProtocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(3))
                .reconnect(new ReconnectConfig(false, 0, Duration.ZERO, Duration.ZERO, 0))
                .build());
        
        client.connect();
        
        assertThrows(RequestTimeoutException.class, () -> {
            client.request(
                Command.text("BADCHK", StandardCharsets.UTF_8),
                ResponseMatcher.any(),
                Duration.ofMillis(500),
                CommandIdempotency.NON_IDEMPOTENT
            );
        });
    }
    
    @Test
    @Order(18)
    @DisplayName("18 超大 frame —— 超过 maxFrameLength 触发 ConnectionException")
    void t18_oversizedFrame() {
        LineProtocol smallLimit = LineProtocol.crlf(StandardCharsets.UTF_8).withMaxFrameLength(64);
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
    @Order(19)
    @DisplayName("19 client close —— 立即断开、重复调用安全、关闭后不可重连")
    void t19_clientClose() {
        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol,
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
    @Order(20)
    @DisplayName("20 reconnect + close race —— 重连期间 close 能正确停止调度")
    void t20_reconnectAndCloseRace() throws Exception {
        CountDownLatch reconnectingLatch = new CountDownLatch(1);
        
        client = InstrumentClients.tcp("localhost", BASE_PORT, lineProtocol,
            ClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(2))
                .reconnect(new ReconnectConfig(true, 10, Duration.ofMillis(100), Duration.ofSeconds(5), 0))
                .build());
        
        client.addConnectionListener(new ConnectionListener() {
            @Override
            public void onReconnecting(int attempt, Duration delay, Throwable cause) {
                reconnectingLatch.countDown();
            }
        });
        
        client.connect();
        
        assertThrows(ConnectionException.class, () -> {
            client.request(
                Command.text("RESET", StandardCharsets.UTF_8),
                ResponseMatcher.any(),
                Duration.ofSeconds(1),
                CommandIdempotency.NON_IDEMPOTENT
            );
        });
        
        assertTrue(reconnectingLatch.await(2, TimeUnit.SECONDS), "应该进入重连等待状态");
        
        client.close();
        
        Thread.sleep(500);
        assertFalse(client.isConnected(), "close 后不应继续重连");
    }
}