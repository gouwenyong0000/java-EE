package com.example.instrument.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClientMetrics 单元测试。
 */
class ClientMetricsTest {
    
    @Test
    @DisplayName("初始状态所有指标均为零")
    void initialState() {
        ClientMetrics metrics = new ClientMetrics();
        var snapshot = metrics.snapshot();
        
        assertEquals(0, snapshot.sentFrames());
        assertEquals(0, snapshot.sentBytes());
        assertEquals(0, snapshot.receivedFrames());
        assertEquals(0, snapshot.matchedFrames());
        assertEquals(0, snapshot.asyncFrames());
        assertEquals(0, snapshot.droppedAsync());
        assertEquals(0, snapshot.listenerErrors());
        assertEquals(0, snapshot.reconnects());
        assertEquals(0, snapshot.timeouts());
    }
    
    @Test
    @DisplayName("发送帧数和字节数正确累加")
    void sentIncrement() {
        ClientMetrics metrics = new ClientMetrics();
        metrics.sent(100);
        metrics.sent(50);
        
        var snapshot = metrics.snapshot();
        assertEquals(2, snapshot.sentFrames());
        assertEquals(150, snapshot.sentBytes());
    }
    
    @Test
    @DisplayName("匹配响应接收统计")
    void receivedMatchedIncrement() {
        ClientMetrics metrics = new ClientMetrics();
        metrics.receivedMatched();
        metrics.receivedMatched();
        
        var snapshot = metrics.snapshot();
        assertEquals(2, snapshot.receivedFrames());
        assertEquals(2, snapshot.matchedFrames());
        assertEquals(0, snapshot.asyncFrames());
    }
    
    @Test
    @DisplayName("异步响应接收统计")
    void receivedAsyncIncrement() {
        ClientMetrics metrics = new ClientMetrics();
        metrics.receivedAsync();
        
        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.receivedFrames());
        assertEquals(0, snapshot.matchedFrames());
        assertEquals(1, snapshot.asyncFrames());
    }
    
    @Test
    @DisplayName("丢弃异步帧计数")
    void droppedAsyncIncrement() {
        ClientMetrics metrics = new ClientMetrics();
        metrics.droppedAsync();
        metrics.droppedAsync();
        metrics.droppedAsync();
        
        var snapshot = metrics.snapshot();
        assertEquals(3, snapshot.droppedAsync());
    }
    
    @Test
    @DisplayName("监听器错误计数")
    void listenerErrorIncrement() {
        ClientMetrics metrics = new ClientMetrics();
        metrics.listenerErrors();
        
        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.listenerErrors());
    }
    
    @Test
    @DisplayName("重连和超时计数")
    void reconnectAndTimeout() {
        ClientMetrics metrics = new ClientMetrics();
        metrics.reconnect();
        metrics.reconnect();
        metrics.timeout();
        
        var snapshot = metrics.snapshot();
        assertEquals(2, snapshot.reconnects());
        assertEquals(1, snapshot.timeouts());
    }
    
    @Test
    @DisplayName("快照创建后与原指标隔离")
    void snapshotIsolation() {
        ClientMetrics metrics = new ClientMetrics();
        metrics.sent(100);
        
        var snapshot1 = metrics.snapshot();
        metrics.sent(50);
        var snapshot2 = metrics.snapshot();
        
        assertEquals(100, snapshot1.sentBytes());
        assertEquals(150, snapshot2.sentBytes());
        assertEquals(1, snapshot1.sentFrames());
        assertEquals(2, snapshot2.sentFrames());
    }
}