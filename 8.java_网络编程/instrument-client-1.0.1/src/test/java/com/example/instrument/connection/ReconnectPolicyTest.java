package com.example.instrument.connection;

import com.example.instrument.config.ReconnectConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReconnectPolicy 单元测试。
 */
class ReconnectPolicyTest {
    
    @Test
    @DisplayName("配置启用且有重试次数时策略启用")
    void enabledWhenConfigEnabledAndHasAttempts() {
        ReconnectPolicy policy = new ReconnectPolicy(ReconnectConfig.defaults());
        assertTrue(policy.enabled());
    }
    
    @Test
    @DisplayName("maxAttempts=0 时策略禁用")
    void disabledWhenMaxAttemptsZero() {
        ReconnectPolicy policy = new ReconnectPolicy(
            new ReconnectConfig(true, 0, Duration.ofMillis(100), Duration.ofSeconds(10), 0)
        );
        assertFalse(policy.enabled());
        assertFalse(policy.canAttempt(1));
    }
    
    @Test
    @DisplayName("配置 disabled 时策略禁用")
    void disabledWhenConfigDisabled() {
        ReconnectPolicy policy = new ReconnectPolicy(
            new ReconnectConfig(false, 10, Duration.ofMillis(100), Duration.ofSeconds(10), 0)
        );
        assertFalse(policy.enabled());
        assertFalse(policy.canAttempt(1));
    }
    
    @Test
    @DisplayName("canAttempt 遵守最大重试次数限制")
    void canAttemptRespectsMax() {
        ReconnectPolicy policy = new ReconnectPolicy(
            new ReconnectConfig(true, 3, Duration.ofMillis(100), Duration.ofSeconds(10), 0)
        );
        
        assertTrue(policy.canAttempt(1));
        assertTrue(policy.canAttempt(2));
        assertTrue(policy.canAttempt(3));
        assertFalse(policy.canAttempt(4));
    }
    
    @Test
    @DisplayName("maxAttempts 返回配置值")
    void maxAttemptsReturnsConfig() {
        ReconnectPolicy policy = new ReconnectPolicy(
            new ReconnectConfig(true, 5, Duration.ofMillis(100), Duration.ofSeconds(10), 0)
        );
        assertEquals(5, policy.maxAttempts());
    }
    
    @Test
    @DisplayName("delay 委托给 ReconnectConfig 计算指数退避")
    void delayDelegatesToConfig() {
        ReconnectPolicy policy = new ReconnectPolicy(
            new ReconnectConfig(true, 10, Duration.ofMillis(100), Duration.ofSeconds(10), 0)
        );
        
        assertEquals(Duration.ofMillis(100), policy.delay(1));
        assertEquals(Duration.ofMillis(200), policy.delay(2));
        assertEquals(Duration.ofMillis(400), policy.delay(3));
    }
    
    @Test
    @DisplayName("指数退避延迟被 maxDelay 上限截断")
    void delayCapsAtMaxDelay() {
        ReconnectPolicy policy = new ReconnectPolicy(
            new ReconnectConfig(true, 10, Duration.ofMillis(100), Duration.ofMillis(500), 0)
        );
        
        assertEquals(Duration.ofMillis(100), policy.delay(1));
        assertEquals(Duration.ofMillis(200), policy.delay(2));
        assertEquals(Duration.ofMillis(400), policy.delay(3));
        assertEquals(Duration.ofMillis(500), policy.delay(4));
        assertEquals(Duration.ofMillis(500), policy.delay(10));
    }
}