package com.example.instrument.config;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 重连配置类，定义了自动重连策略的各项参数。
 * 
 * 该配置采用指数退避算法，随着重试次数增加，重连延迟会逐渐增长。
 * 支持配置最大重试次数、初始延迟、最大延迟以及随机抖动。
 * 
 * <p>延迟计算公式：</p>
 * <ul>
 *   <li>基础延迟按指数增长：initialDelay * 2^(attempt-1)，不超过 maxDelay</li>
 *   <li>可选添加随机抖动来避免多客户端同时重连造成的雷鸣群效应</li>
 * </ul>
 * 
 * <p>使用示例：</p>
 * <pre>{@code
 * ReconnectConfig config = new ReconnectConfig(
 *     true,                           // enabled
 *     5,                              // maxAttempts
 *     Duration.ofSeconds(1),         // initialDelay
 *     Duration.ofSeconds(60),        // maxDelay
 *     0.3                             // jitterRatio (30%)
 * );
 * }</pre>
 * 
 * @see ClientConfig.Builder#reconnect
 */
public record ReconnectConfig(
    boolean enabled, 
    int maxAttempts, 
    Duration initialDelay,
    Duration maxDelay, 
    double jitterRatio
) {
    
    /**
     * 创建默认重连配置。
     * 
     * 默认配置：
     * - enabled: true
     * - maxAttempts: 3
     * - initialDelay: 1 秒
     * - maxDelay: 30 秒
     * - jitterRatio: 0.20 (20%)
     *
     * @return 默认重连配置
     */
    public static ReconnectConfig defaults() {
        return new ReconnectConfig(true, 3, Duration.ofSeconds(1), Duration.ofSeconds(30), 0.20);
    }

    /**
     * 构造函数，校验参数有效性。
     *
     * @param enabled      是否启用重连
     * @param maxAttempts  最大重试次数，不能为负数
     * @param initialDelay 初始延迟，不能为空，不能为负数
     * @param maxDelay     最大延迟，不能为空，不能为负数，且必须 >= initialDelay
     * @param jitterRatio  抖动比例，必须在 0..1 范围内
     * @throws IllegalArgumentException 如果参数无效
     */
    public ReconnectConfig {
        Objects.requireNonNull(initialDelay); 
        Objects.requireNonNull(maxDelay);
        if (maxAttempts < 0) throw new IllegalArgumentException("maxAttempts < 0");
        if (initialDelay.isNegative() || maxDelay.isNegative() || maxDelay.compareTo(initialDelay) < 0)
            throw new IllegalArgumentException("invalid reconnect delay");
        if (jitterRatio < 0 || jitterRatio > 1) throw new IllegalArgumentException("jitterRatio must be 0..1");
    }

    /**
     * 计算指定重试次数对应的延迟时间。
     * 
     * 延迟时间按指数增长：initialDelay * 2^(attempt-1)，不超过 maxDelay。
     * 如果配置了 jitterRatio，会在基础延迟上添加随机抖动。
     *
     * <p>示例（initialDelay=1s, maxDelay=30s, jitterRatio=0.2）：</p>
     * <ul>
     *   <li>attempt=1: 约 0.8-1.2 秒</li>
     *   <li>attempt=2: 约 1.6-2.4 秒</li>
     *   <li>attempt=3: 约 3.2-4.8 秒</li>
     * </ul>
     *
     * @param attempt 重试次数（从 1 开始），如果 <= 0 返回 ZERO
     * @return 计算得到的延迟时间
     */
    public Duration delayForAttempt(int attempt) {
        if (attempt <= 0) return Duration.ZERO;
        
        long base = initialDelay.toMillis();
        long max = maxDelay.toMillis();
        long value = base;
        
        for (int i = 1; i < attempt && value < max; i++) {
            value = Math.min(max, Math.max(1, value * 2));
        }
        
        if (jitterRatio == 0 || value == 0) {
            return Duration.ofMillis(value);
        }
        
        long spread = Math.max(1, (long) (value * jitterRatio));
        long jittered = value - spread + ThreadLocalRandom.current().nextLong(spread * 2 + 1);
        return Duration.ofMillis(Math.max(0, Math.min(max, jittered)));
    }
}