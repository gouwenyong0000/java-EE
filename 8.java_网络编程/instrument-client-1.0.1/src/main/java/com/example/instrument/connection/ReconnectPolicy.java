package com.example.instrument.connection;

import com.example.instrument.config.ReconnectConfig;
import java.time.Duration;
import java.util.Objects;

/**
 * 重连策略类，封装了重连逻辑的判断条件。
 * 
 * 该类是 ReconnectConfig 的包装类，提供更友好的判断方法。
 * 用于 InstrumentClientImpl 在连接断开时判断是否应该重连以及如何重连。
 * 
 * @see ReconnectConfig
 * @see com.example.instrument.core.InstrumentClientImpl
 */
public final class ReconnectPolicy {
    
    private final ReconnectConfig config;

    /**
     * 构造函数，创建重连策略。
     *
     * @param config 重连配置，不能为空
     * @throws NullPointerException 如果 config 为空
     */
    public ReconnectPolicy(ReconnectConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    /**
     * 判断是否启用重连。
     *
     * @return 如果启用重连且最大重试次数大于 0 返回 true
     */
    public boolean enabled() {
        return config.enabled() && config.maxAttempts() > 0;
    }

    /**
     * 判断是否可以进行指定次数的重连尝试。
     *
     * @param attempt 重连尝试次数（从 1 开始）
     * @return 如果可以进行此次尝试返回 true
     */
    public boolean canAttempt(int attempt) {
        return enabled() && attempt <= config.maxAttempts();
    }

    /**
     * 获取指定重连次数对应的延迟时间。
     *
     * @param attempt 重连尝试次数（从 1 开始）
     * @return 延迟时间
     * @see ReconnectConfig#delayForAttempt
     */
    public Duration delay(int attempt) {
        return config.delayForAttempt(attempt);
    }

    /**
     * 获取配置的最大重试次数。
     *
     * @return 最大重试次数
     */
    public int maxAttempts() {
        return config.maxAttempts();
    }
}