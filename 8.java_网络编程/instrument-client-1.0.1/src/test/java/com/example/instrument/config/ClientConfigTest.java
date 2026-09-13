package com.example.instrument.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClientConfig 和 ReconnectConfig 单元测试。
 */
class ClientConfigTest {
    
    @Test
    @DisplayName("defaults() 返回合理的默认配置值")
    void testDefaultConfig() {
        ClientConfig config = ClientConfig.defaults();
        
        assertEquals(Duration.ofSeconds(5), config.connectTimeout());
        assertEquals(Duration.ofSeconds(10), config.responseTimeout());
        assertEquals(8192, config.receiveBufferSize());
        assertEquals(256, config.asyncQueueCapacity());
        assertNotNull(config.reconnect());
        assertEquals(ClientConfig.OverflowPolicy.DROP_OLDEST, config.overflowPolicy());
        assertTrue(config.tcpNoDelay());
        assertTrue(config.keepAlive());
    }
    
    @Test
    @DisplayName("Builder 自定义所有字段后 build 得到正确配置")
    void testBuilder() {
        ClientConfig config = ClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(1))
            .responseTimeout(Duration.ofSeconds(5))
            .receiveBufferSize(4096)
            .asyncQueueCapacity(32)
            .overflowPolicy(ClientConfig.OverflowPolicy.BLOCK)
            .tcpNoDelay(false)
            .keepAlive(false)
            .reconnect(ReconnectConfig.defaults())
            .build();
        
        assertEquals(Duration.ofSeconds(1), config.connectTimeout());
        assertEquals(Duration.ofSeconds(5), config.responseTimeout());
        assertEquals(4096, config.receiveBufferSize());
        assertEquals(32, config.asyncQueueCapacity());
        assertEquals(ClientConfig.OverflowPolicy.BLOCK, config.overflowPolicy());
        assertFalse(config.tcpNoDelay());
        assertFalse(config.keepAlive());
    }
    
    @Test
    @DisplayName("ReconnectConfig.defaults() 返回合理默认值")
    void testReconnectDefaults() {
        ReconnectConfig config = ReconnectConfig.defaults();
        
        assertTrue(config.enabled());
        assertEquals(3, config.maxAttempts());
        assertEquals(Duration.ofSeconds(1), config.initialDelay());
        assertEquals(Duration.ofSeconds(30), config.maxDelay());
        assertEquals(0.2, config.jitterRatio());
    }
    
    @Test
    @DisplayName("ReconnectConfig 全参构造器正确保存所有参数")
    void testReconnectCustom() {
        ReconnectConfig config = new ReconnectConfig(
            true,
            10,
            Duration.ofMillis(500),
            Duration.ofSeconds(30),
            0.3
        );
        
        assertTrue(config.enabled());
        assertEquals(10, config.maxAttempts());
        assertEquals(Duration.ofMillis(500), config.initialDelay());
        assertEquals(Duration.ofSeconds(30), config.maxDelay());
        assertEquals(0.3, config.jitterRatio());
    }
    
    @Test
    @DisplayName("ReconnectConfig 参数校验 —— 非法值抛异常")
    void testReconnectValidation() {
        assertThrows(IllegalArgumentException.class, () -> 
            new ReconnectConfig(true, -1, Duration.ofSeconds(1), Duration.ofSeconds(10), 0.5));
        
        assertThrows(IllegalArgumentException.class, () -> 
            new ReconnectConfig(true, 5, Duration.ofSeconds(10), Duration.ofSeconds(1), 0.5));
        
        assertThrows(IllegalArgumentException.class, () -> 
            new ReconnectConfig(true, 5, Duration.ofSeconds(1), Duration.ofSeconds(10), 1.5));
    }
    
    @Test
    @DisplayName("指数退避延迟无抖动: delay(n) = initialDelay * 2^(n-1)")
    void testReconnectDelay() {
        ReconnectConfig config = new ReconnectConfig(true, 5, Duration.ofMillis(100), Duration.ofSeconds(10), 0);
        
        assertEquals(Duration.ofMillis(100), config.delayForAttempt(1));
        assertEquals(Duration.ofMillis(200), config.delayForAttempt(2));
        assertEquals(Duration.ofMillis(400), config.delayForAttempt(3));
        assertEquals(Duration.ofMillis(800), config.delayForAttempt(4));
    }
    
    @Test
    @DisplayName("第 0 次或负数次尝试延迟为零")
    void testReconnectDelayZeroAttempt() {
        ReconnectConfig config = ReconnectConfig.defaults();
        assertEquals(Duration.ZERO, config.delayForAttempt(0));
        assertEquals(Duration.ZERO, config.delayForAttempt(-1));
    }
    
    @Test
    @DisplayName("Builder 输入校验 —— null 和非法值抛异常")
    void testBuilderValidation() {
        assertThrows(NullPointerException.class, () -> 
            ClientConfig.builder().connectTimeout(null));
        
        assertThrows(IllegalArgumentException.class, () -> 
            ClientConfig.builder().asyncQueueCapacity(0));
        
        assertThrows(IllegalArgumentException.class, () -> 
            ClientConfig.builder().asyncQueueCapacity(-1));
    }
}