package com.example.instrument;

import com.example.instrument.engine.SocketClientConfig;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SocketClientConfig 配置校验测试")
class SocketClientConfigTest {

  @Test
  @DisplayName("defaults() 生成合法配置且各字段符合预期默认值")
  void testDefaults() {
    SocketClientConfig cfg = SocketClientConfig.defaults("127.0.0.1", 5025);
    assertEquals("127.0.0.1", cfg.host());
    assertEquals(5025, cfg.port());
    assertEquals(Duration.ofSeconds(5), cfg.connectTimeout());
    assertEquals(Duration.ofSeconds(10), cfg.responseTimeout());
    assertEquals(Duration.ofMillis(500), cfg.socketReadTimeout());
    assertEquals(Duration.ofMillis(1000), cfg.reconnectInterval());
    assertEquals(3, cfg.maxReconnectAttempts());
    assertTrue(cfg.retrySendOnWriteFailure());
  }

  @Test
  @DisplayName("host 为 null 应抛 IllegalArgumentException")
  void testNullHostRejected() {
    assertThrows(IllegalArgumentException.class, () ->
        SocketClientConfig.defaults(null, 5025));
  }

  @Test
  @DisplayName("host 为空字符串应抛 IllegalArgumentException")
  void testBlankHostRejected() {
    assertThrows(IllegalArgumentException.class, () ->
        SocketClientConfig.defaults("  ", 5025));
  }

  @Test
  @DisplayName("port 为 0 应抛 IllegalArgumentException")
  void testPortZeroRejected() {
    assertThrows(IllegalArgumentException.class, () ->
        SocketClientConfig.defaults("127.0.0.1", 0));
  }

  @Test
  @DisplayName("port 超过 65535 应抛 IllegalArgumentException")
  void testPortTooLargeRejected() {
    assertThrows(IllegalArgumentException.class, () ->
        SocketClientConfig.defaults("127.0.0.1", 65536));
  }

  @Test
  @DisplayName("port 为负数应抛 IllegalArgumentException")
  void testNegativePortRejected() {
    assertThrows(IllegalArgumentException.class, () ->
        SocketClientConfig.defaults("127.0.0.1", -1));
  }

  @Test
  @DisplayName("port 边界值 1 和 65535 合法")
  void testPortBoundaryAccepted() {
    assertDoesNotThrow(() -> SocketClientConfig.defaults("127.0.0.1", 1));
    assertDoesNotThrow(() -> SocketClientConfig.defaults("127.0.0.1", 65535));
  }

  @Test
  @DisplayName("connectTimeout 为 null 应抛 IllegalArgumentException")
  void testNullConnectTimeoutRejected() {
    assertThrows(IllegalArgumentException.class, () -> new SocketClientConfig(
        "127.0.0.1", 5025, null, Duration.ofSeconds(10),
        Duration.ofMillis(500), Duration.ofSeconds(1), 3, true));
  }

  @Test
  @DisplayName("connectTimeout 为 0 应抛 IllegalArgumentException")
  void testZeroConnectTimeoutRejected() {
    assertThrows(IllegalArgumentException.class, () -> new SocketClientConfig(
        "127.0.0.1", 5025, Duration.ZERO, Duration.ofSeconds(10),
        Duration.ofMillis(500), Duration.ofSeconds(1), 3, true));
  }

  @Test
  @DisplayName("connectTimeout 为负数应抛 IllegalArgumentException")
  void testNegativeConnectTimeoutRejected() {
    assertThrows(IllegalArgumentException.class, () -> new SocketClientConfig(
        "127.0.0.1", 5025, Duration.ofSeconds(-1), Duration.ofSeconds(10),
        Duration.ofMillis(500), Duration.ofSeconds(1), 3, true));
  }

  @Test
  @DisplayName("responseTimeout 为 null 应抛 IllegalArgumentException")
  void testNullResponseTimeoutRejected() {
    assertThrows(IllegalArgumentException.class, () -> new SocketClientConfig(
        "127.0.0.1", 5025, Duration.ofSeconds(5), null,
        Duration.ofMillis(500), Duration.ofSeconds(1), 3, true));
  }

  @Test
  @DisplayName("responseTimeout 为 0 应抛 IllegalArgumentException")
  void testZeroResponseTimeoutRejected() {
    assertThrows(IllegalArgumentException.class, () -> new SocketClientConfig(
        "127.0.0.1", 5025, Duration.ofSeconds(5), Duration.ZERO,
        Duration.ofMillis(500), Duration.ofSeconds(1), 3, true));
  }

  @Test
  @DisplayName("socketReadTimeout 为 null 应抛 IllegalArgumentException")
  void testNullSocketReadTimeoutRejected() {
    assertThrows(IllegalArgumentException.class, () -> new SocketClientConfig(
        "127.0.0.1", 5025, Duration.ofSeconds(5), Duration.ofSeconds(10),
        null, Duration.ofSeconds(1), 3, true));
  }

  @Test
  @DisplayName("socketReadTimeout 为负数应抛 IllegalArgumentException")
  void testNegativeSocketReadTimeoutRejected() {
    assertThrows(IllegalArgumentException.class, () -> new SocketClientConfig(
        "127.0.0.1", 5025, Duration.ofSeconds(5), Duration.ofSeconds(10),
        Duration.ofMillis(-1), Duration.ofSeconds(1), 3, true));
  }

  @Test
  @DisplayName("reconnectInterval 为 null 应抛 IllegalArgumentException")
  void testNullReconnectIntervalRejected() {
    assertThrows(IllegalArgumentException.class, () -> new SocketClientConfig(
        "127.0.0.1", 5025, Duration.ofSeconds(5), Duration.ofSeconds(10),
        Duration.ofMillis(500), null, 3, true));
  }

  @Test
  @DisplayName("reconnectInterval 为负数应抛 IllegalArgumentException")
  void testNegativeReconnectIntervalRejected() {
    assertThrows(IllegalArgumentException.class, () -> new SocketClientConfig(
        "127.0.0.1", 5025, Duration.ofSeconds(5), Duration.ofSeconds(10),
        Duration.ofMillis(500), Duration.ofSeconds(-1), 3, true));
  }

  @Test
  @DisplayName("maxReconnectAttempts 为负数应抛 IllegalArgumentException")
  void testNegativeMaxReconnectRejected() {
    assertThrows(IllegalArgumentException.class, () -> new SocketClientConfig(
        "127.0.0.1", 5025, Duration.ofSeconds(5), Duration.ofSeconds(10),
        Duration.ofMillis(500), Duration.ofSeconds(1), -1, true));
  }

  @Test
  @DisplayName("maxReconnectAttempts 为 0 合法（禁止重连）")
  void testZeroMaxReconnectAccepted() {
    SocketClientConfig cfg = new SocketClientConfig(
        "127.0.0.1", 5025, Duration.ofSeconds(5), Duration.ofSeconds(10),
        Duration.ofMillis(500), Duration.ofSeconds(1), 0, true);
    assertEquals(0, cfg.maxReconnectAttempts());
  }
}