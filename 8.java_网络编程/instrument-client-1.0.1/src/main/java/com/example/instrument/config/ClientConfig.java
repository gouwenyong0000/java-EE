package com.example.instrument.config;

import java.net.SocketOption;
import java.time.Duration;
import java.util.Objects;

/**
 * 客户端配置类，定义了 InstrumentClient 的所有可配置参数。
 * 
 * 该类使用 Builder 模式创建，支持链式调用来设置不同的配置项。
 * 提供了合理的默认值，可以通过 {@link Builder} 自定义配置。
 * 
 * <p>配置项包括：</p>
 * <ul>
 *   <li>连接超时时间 (connectTimeout)</li>
 *   <li>默认响应超时时间 (responseTimeout)</li>
 *   <li>Socket 读超时时间 (socketReadTimeoutMillis)</li>
 *   <li>接收缓冲区大小 (receiveBufferSize)</li>
 *   <li>异步队列容量 (asyncQueueCapacity)</li>
 *   <li>队列溢出策略 (overflowPolicy)</li>
 *   <li>TCP NODELAY 选项 (tcpNoDelay)</li>
 *   <li>TCP KEEPALIVE 选项 (keepAlive)</li>
 *   <li>重连配置 (reconnect)</li>
 * </ul>
 * 
 * <p>使用示例：</p>
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
    private final int asyncQueueCapacity;
    private final OverflowPolicy overflowPolicy;
    private final boolean tcpNoDelay;
    private final boolean keepAlive;
    private final ReconnectConfig reconnect;

    /**
     * 私有构造函数，通过 Builder 创建配置实例。
     *
     * @param b 包含配置参数的 Builder 实例
     */
    private ClientConfig(Builder b) {
        connectTimeout = b.connectTimeout; 
        responseTimeout = b.responseTimeout; 
        socketReadTimeoutMillis = b.socketReadTimeoutMillis;
        receiveBufferSize = b.receiveBufferSize; 
        asyncQueueCapacity = b.asyncQueueCapacity; 
        overflowPolicy = b.overflowPolicy;
        tcpNoDelay = b.tcpNoDelay; 
        keepAlive = b.keepAlive; 
        reconnect = b.reconnect;
    }

    /**
     * 创建一个新的 Builder 实例。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() { 
        return new Builder(); 
    }

    /**
     * 获取默认配置的 ClientConfig 实例。
     * 
     * 默认配置：
     * - connectTimeout: 5 秒
     * - responseTimeout: 10 秒
     * - socketReadTimeoutMillis: 0（无限）
     * - receiveBufferSize: 8192 字节
     * - asyncQueueCapacity: 256
     * - overflowPolicy: DROP_OLDEST
     * - tcpNoDelay: true
     * - keepAlive: true
     * - reconnect: 默认重连配置
     *
     * @return 默认配置的 ClientConfig 实例
     */
    public static ClientConfig defaults() { 
        return builder().build(); 
    }

    /**
     * 获取连接超时时间。
     * 
     * 连接超时时间指建立 TCP 连接所需的最大时间。
     *
     * @return 连接超时时间
     */
    public Duration connectTimeout() { return connectTimeout; }

    /**
     * 获取默认响应超时时间。
     * 
     * 这是 request() 方法的默认超时时间。
     *
     * @return 响应超时时间
     */
    public Duration responseTimeout() { return responseTimeout; }

    /**
     * 获取 Socket 读超时时间（毫秒）。
     * 
     * 设置 Socket 的 SO_TIMEOUT，如果为 0 表示无限超时。
     *
     * @return Socket 读超时时间（毫秒）
     */
    public int socketReadTimeoutMillis() { return socketReadTimeoutMillis; }

    /**
     * 获取接收缓冲区大小（字节）。
     * 
     * 这个值会设置为 Socket 的 SO_RCVBUF 选项。
     *
     * @return 接收缓冲区大小
     */
    public int receiveBufferSize() { return receiveBufferSize; }

    /**
     * 获取异步响应队列的容量。
     * 
     * 当收到无法匹配的响应时，会先放入异步队列。如果队列已满，
     * 则根据 overflowPolicy 决定如何处理。
     *
     * @return 异步队列容量
     */
    public int asyncQueueCapacity() { return asyncQueueCapacity; }

    /**
     * 获取队列溢出策略。
     * 
     * 当异步队列已满时的处理策略。
     *
     * @return 溢出策略
     * @see OverflowPolicy
     */
    public OverflowPolicy overflowPolicy() { return overflowPolicy; }

    /**
     * 获取 TCP NODELAY 选项状态。
     * 
     * 如果为 true，则禁用 Nagle 算法，减少延迟但可能增加网络流量。
     *
     * @return TCP NODELAY 选项状态
     */
    public boolean tcpNoDelay() { return tcpNoDelay; }

    /**
     * 获取 TCP KEEPALIVE 选项状态。
     * 
     * 如果为 true，则启用 TCP keepalive，检测连接是否仍然存活。
     *
     * @return TCP KEEPALIVE 选项状态
     */
    public boolean keepAlive() { return keepAlive; }

    /**
     * 获取重连配置。
     *
     * @return 重连配置
     * @see ReconnectConfig
     */
    public ReconnectConfig reconnect() { return reconnect; }

    /**
     * 队列溢出策略枚举。
     * 
     * 定义当异步响应队列已满时的处理策略。
     */
    public enum OverflowPolicy { 
        /**
         * 丢弃队列中最老的响应，添加新响应。
         */
        DROP_OLDEST, 
        
        /**
         * 丢弃新收到的响应，保留队列中的旧响应。
         */
        DROP_NEWEST, 
        
        /**
         * 阻塞直到队列有空间可以添加新响应。
         */
        BLOCK, 
        
        /**
         * 直接抛出异常。
         */
        FAIL 
    }

    /**
     * ClientConfig 的 Builder 类，使用链式调用构建配置。
     * 
     * 所有配置项都有默认值，可以在构建时选择性覆盖。
     */
    public static final class Builder {
        
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration responseTimeout = Duration.ofSeconds(10);
        private int socketReadTimeoutMillis = 0;
        private int receiveBufferSize = 8192;
        private int asyncQueueCapacity = 256;
        private OverflowPolicy overflowPolicy = OverflowPolicy.DROP_OLDEST;
        private boolean tcpNoDelay = true;
        private boolean keepAlive = true;
        private ReconnectConfig reconnect = ReconnectConfig.defaults();

        /**
         * 设置连接超时时间。
         *
         * @param v 连接超时时间，不能为空，必须为正数
         * @return this，用于链式调用
         * @throws NullPointerException 如果 v 为空
         */
        public Builder connectTimeout(Duration v) {
            connectTimeout = Objects.requireNonNull(v);
            return this;
        }

        /**
         * 设置默认响应超时时间。
         *
         * @param v 响应超时时间，不能为空，必须为正数
         * @return this，用于链式调用
         * @throws NullPointerException 如果 v 为空
         */
        public Builder responseTimeout(Duration v) {
            responseTimeout = Objects.requireNonNull(v);
            return this;
        }

        /**
         * 设置 Socket 读超时时间（毫秒）。
         *
         * @param v Socket 读超时时间，不能为负数
         * @return this，用于链式调用
         * @throws IllegalArgumentException 如果 v 为负数
         */
        public Builder socketReadTimeoutMillis(int v) {
            if (v < 0) throw new IllegalArgumentException();
            socketReadTimeoutMillis = v;
            return this;
        }

        /**
         * 设置接收缓冲区大小。
         *
         * @param v 接收缓冲区大小，必须为正数
         * @return this，用于链式调用
         * @throws IllegalArgumentException 如果 v <= 0
         */
        public Builder receiveBufferSize(int v) {
            if (v <= 0) throw new IllegalArgumentException();
            receiveBufferSize = v;
            return this;
        }

        /**
         * 设置异步队列容量。
         *
         * @param v 异步队列容量，必须为正数
         * @return this，用于链式调用
         * @throws IllegalArgumentException 如果 v <= 0
         */
        public Builder asyncQueueCapacity(int v) {
            if (v <= 0) throw new IllegalArgumentException();
            asyncQueueCapacity = v;
            return this;
        }

        /**
         * 设置队列溢出策略。
         *
         * @param v 溢出策略，不能为空
         * @return this，用于链式调用
         * @throws NullPointerException 如果 v 为空
         */
        public Builder overflowPolicy(OverflowPolicy v) {
            overflowPolicy = Objects.requireNonNull(v);
            return this;
        }

        /**
         * 设置 TCP NODELAY 选项。
         *
         * @param v 是否启用 TCP NODELAY
         * @return this，用于链式调用
         */
        public Builder tcpNoDelay(boolean v) {
            tcpNoDelay = v;
            return this;
        }

        /**
         * 设置 TCP KEEPALIVE 选项。
         *
         * @param v 是否启用 TCP KEEPALIVE
         * @return this，用于链式调用
         */
        public Builder keepAlive(boolean v) {
            keepAlive = v;
            return this;
        }

        /**
         * 设置重连配置。
         *
         * @param v 重连配置，不能为空
         * @return this，用于链式调用
         * @throws NullPointerException 如果 v 为空
         */
        public Builder reconnect(ReconnectConfig v) {
            reconnect = Objects.requireNonNull(v);
            return this;
        }

        /**
         * 根据当前配置构建 ClientConfig 实例。
         *
         * @return 配置好的 ClientConfig 实例
         */
        public ClientConfig build() {
            return new ClientConfig(this);
        }
    }
}