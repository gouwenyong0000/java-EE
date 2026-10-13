package com.example.instrument.core.config;

import com.example.instrument.transport.reconnect.ReconnectConfig;

import java.time.Duration;
import java.util.Objects;

/**
 * 客户端配置类，定义 InstrumentClient 的所有可配置参数。
 *
 * <h2>设计模式</h2>
 * <p>该类使用 <b>Builder 模式</b>创建，支持链式调用设置配置项。
 * 通过 {@link #builder()} 获取 Builder 实例，配置完成后调用 {@link Builder#build()} 构建实例。</p>
 *
 * <h2>配置项总览</h2>
 * <table>
 *   <tr><th>分类</th><th>配置项</th><th>说明</th></tr>
 *   <tr><td rowspan="2">超时</td><td>{@link #connectTimeout}</td><td>TCP 连接建立超时</td></tr>
 *   <tr><td>{@link #responseTimeout}</td><td>默认请求响应超时</td></tr>
 *   <tr><td rowspan="3">Socket</td><td>{@link #socketReadTimeoutMillis}</td><td>Socket SO_TIMEOUT</td></tr>
 *   <tr><td>{@link #receiveBufferSize}</td><td>接收缓冲区大小 (SO_RCVBUF)</td></tr>
 *   <tr><td>{@link #sendBufferSize}</td><td>发送缓冲区大小 (SO_SNDBUF)</td></tr>
 *   <tr><td rowspan="2">异步队列</td><td>{@link #asyncQueueCapacity}</td><td>无法匹配的响应队列容量</td></tr>
 *   <tr><td>{@link #overflowPolicy}</td><td>队列溢出时的处理策略</td></tr>
 *   <tr><td rowspan="2">TCP 选项</td><td>{@link #tcpNoDelay}</td><td>禁用 Nagle 算法，降低延迟</td></tr>
 *   <tr><td>{@link #keepAlive}</td><td>启用 TCP keepalive 检测存活</td></tr>
 *   <tr><td>重连</td><td>{@link #reconnect}</td><td>自动重连策略配置</td></tr>
 * </table>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * ClientConfig config = ClientConfig.builder()
 *     .connectTimeout(Duration.ofSeconds(10))
 *     .responseTimeout(Duration.ofSeconds(30))
 *     .receiveBufferSize(16384)
 *     .overflowPolicy(OverflowPolicy.BLOCK)
 *     .reconnect(ReconnectConfig.defaults())
 *     .build();
 * }</pre>
 *
 * @see Builder
 * @see ReconnectConfig
 * @see OverflowPolicy
 */
public final class ClientConfig {

    private final Duration connectTimeout;
    private final Duration responseTimeout;
    private final int socketReadTimeoutMillis;
    private final int receiveBufferSize;
    private final int sendBufferSize;
    private final int soLingerSeconds;
    private final int asyncQueueCapacity;
    private final int asyncRequestQueueCapacity;
    private final OverflowPolicy overflowPolicy;
    private final boolean tcpNoDelay;
    private final boolean keepAlive;
    private final ReconnectConfig reconnect;

    private ClientConfig(Builder b) {
        connectTimeout = b.connectTimeout;
        responseTimeout = b.responseTimeout;
        socketReadTimeoutMillis = b.socketReadTimeoutMillis;
        receiveBufferSize = b.receiveBufferSize;
        sendBufferSize = b.sendBufferSize;
        soLingerSeconds = b.soLingerSeconds;
        asyncQueueCapacity = b.asyncQueueCapacity;
        asyncRequestQueueCapacity = b.asyncRequestQueueCapacity;
        overflowPolicy = b.overflowPolicy;
        tcpNoDelay = b.tcpNoDelay;
        keepAlive = b.keepAlive;
        reconnect = b.reconnect;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ClientConfig defaults() {
        return builder().build();
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration responseTimeout() {
        return responseTimeout;
    }

    public int socketReadTimeoutMillis() {
        return socketReadTimeoutMillis;
    }

    public int receiveBufferSize() {
        return receiveBufferSize;
    }

    public int sendBufferSize() {
        return sendBufferSize;
    }

    public int asyncRequestQueueCapacity() {
        return asyncRequestQueueCapacity;
    }

    public int soLingerSeconds() {
        return soLingerSeconds;
    }

    public int asyncQueueCapacity() {
        return asyncQueueCapacity;
    }

    public OverflowPolicy overflowPolicy() {
        return overflowPolicy;
    }

    public boolean tcpNoDelay() {
        return tcpNoDelay;
    }

    public boolean keepAlive() {
        return keepAlive;
    }

    public ReconnectConfig reconnect() {
        return reconnect;
    }

    public enum OverflowPolicy {
        DROP_OLDEST,
        DROP_NEWEST,
        BLOCK,
        FAIL
    }

    public static final class Builder {

        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration responseTimeout = Duration.ofSeconds(10);
        private int socketReadTimeoutMillis = 0;
        private int receiveBufferSize = 8192;
        private int sendBufferSize = 8192;
        private int soLingerSeconds = -1;
        private int asyncQueueCapacity = 256;
        private int asyncRequestQueueCapacity = 128;
        private OverflowPolicy overflowPolicy = OverflowPolicy.DROP_OLDEST;
        private boolean tcpNoDelay = true;
        private boolean keepAlive = true;
        private ReconnectConfig reconnect = ReconnectConfig.defaults();

        public Builder connectTimeout(Duration v) {
            Objects.requireNonNull(v, "connectTimeout");
            if (v.isZero() || v.isNegative()) {
                throw new IllegalArgumentException("connectTimeout must be > 0, got: " + v);
            }
            connectTimeout = v;
            return this;
        }

        public Builder responseTimeout(Duration v) {
            Objects.requireNonNull(v, "responseTimeout");
            if (v.isZero() || v.isNegative()) {
                throw new IllegalArgumentException("responseTimeout must be > 0, got: " + v);
            }
            responseTimeout = v;
            return this;
        }

        public Builder socketReadTimeoutMillis(int v) {
            if (v < 0) {
                throw new IllegalArgumentException("socketReadTimeoutMillis must be >= 0, got: " + v);
            }
            socketReadTimeoutMillis = v;
            return this;
        }

        public Builder receiveBufferSize(int v) {
            if (v <= 0) {
                throw new IllegalArgumentException("receiveBufferSize must be > 0, got: " + v);
            }
            receiveBufferSize = v;
            return this;
        }

        public Builder sendBufferSize(int v) {
            if (v <= 0) {
                throw new IllegalArgumentException("sendBufferSize must be > 0, got: " + v);
            }
            sendBufferSize = v;
            return this;
        }

        public Builder soLingerSeconds(int v) {
            if (v < -1 || v > 65535) {
                throw new IllegalArgumentException("soLingerSeconds must be -1 (disabled) or between 0 and 65535");
            }
            soLingerSeconds = v;
            return this;
        }

        public Builder asyncQueueCapacity(int v) {
            if (v <= 0) {
                throw new IllegalArgumentException("asyncQueueCapacity must be > 0, got: " + v);
            }
            asyncQueueCapacity = v;
            return this;
        }

        public Builder asyncRequestQueueCapacity(int v) {
            if (v <= 0) {
                throw new IllegalArgumentException("asyncRequestQueueCapacity must be > 0, got: " + v);
            }
            asyncRequestQueueCapacity = v;
            return this;
        }

        public Builder overflowPolicy(OverflowPolicy v) {
            overflowPolicy = Objects.requireNonNull(v);
            return this;
        }

        public Builder tcpNoDelay(boolean v) {
            tcpNoDelay = v;
            return this;
        }

        public Builder keepAlive(boolean v) {
            keepAlive = v;
            return this;
        }

        public Builder reconnect(ReconnectConfig v) {
            reconnect = Objects.requireNonNull(v);
            return this;
        }

        public ClientConfig build() {
            return new ClientConfig(this);
        }
    }
}