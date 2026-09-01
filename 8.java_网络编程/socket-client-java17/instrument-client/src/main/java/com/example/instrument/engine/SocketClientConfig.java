package com.example.instrument.engine;

import java.time.Duration;

/**
 * 客户端连接配置。
 *
 * <p>该配置集中描述与远程仪器交互时所需的网络参数、超时、重连策略以及写入失败后的恢复行为。
 */
public record SocketClientConfig(
    /** 主机名 */
    String host,
    /** 端口号 */
    int port,
    /** 连接超时时间 */
    Duration connectTimeout,
    /** 响应超时时间 */
    Duration responseTimeout,
    /** 套接字读取超时时间 */
    Duration socketReadTimeout,
    /** 重连间隔时间 */
    Duration reconnectInterval,
    /** 最大重连次数 */
    int maxReconnectAttempts,

    /** 写失败时是否重试 */
    boolean retrySendOnWriteFailure) {

  public SocketClientConfig {
    // 主机和端口是连接的最基础参数，缺失或越界都必须直接拒绝。
    if (host == null || host.isBlank()) throw new IllegalArgumentException("host is blank");
    if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid port");
    if (maxReconnectAttempts < 0) throw new IllegalArgumentException("maxReconnectAttempts < 0");
  }

  /** 生成一组常用默认值，适合大多数本地或内网仪器连接场景。 */
  public static SocketClientConfig defaults(String host, int port) {
    return new SocketClientConfig(
        host,
        port,
        Duration.ofSeconds(3),
        Duration.ofSeconds(5),
        Duration.ofMillis(500),
        Duration.ofSeconds(1),
        3,
        true);
  }
}
