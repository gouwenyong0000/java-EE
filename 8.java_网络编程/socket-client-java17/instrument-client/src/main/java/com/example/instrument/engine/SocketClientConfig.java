package com.example.instrument.engine;

import java.time.Duration;

/**
 * 客户端连接配置 —— 集中管理"可变开关"。
 *
 * <p>所有参数都有语义明确的默认值（见 {@link #defaults}），大多数仪器场景直接用 defaults 即可。
 * 只有在遇到特殊场景（高延迟网络、服务器重启频繁、需要极短超时等）才需要手动调整。
 *
 * @param host                    服务端主机地址（必须非空，如 "192.168.1.10" 或 "127.0.0.1"）
 * @param port                    服务端端口（必须在 1~65535 之间，常用 5025 是 SCPI 默认端口）
 * @param connectTimeout          TCP 三次握手超时（默认 3s；太长会卡启动，太短在 NAT 后可能不够）
 * @param responseTimeout         sendAndMatch 等待响应的最长时间（默认 5s；为 null 时也用这个值）
 * @param socketReadTimeout       socket.setSoTimeout 的值（默认 500ms；同时也是 receiveLoop 内层循环的节流周期）
 * @param reconnectInterval       重连间隔（默认 1s；两次 connect 失败之间的休眠时间）
 * @param maxReconnectAttempts    最大额外重连次数（默认 3；实际总共尝试 {@code max+1} 次，Math.max(1,...) 兜底）
 * @param retrySendOnWriteFailure 写失败后是否自动重连重试（默认 true；解决 OutputStream 半关闭场景）
 */
public record SocketClientConfig(
    String host,
    int port,
    Duration connectTimeout,
    Duration responseTimeout,
    Duration socketReadTimeout,
    Duration reconnectInterval,
    int maxReconnectAttempts,
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