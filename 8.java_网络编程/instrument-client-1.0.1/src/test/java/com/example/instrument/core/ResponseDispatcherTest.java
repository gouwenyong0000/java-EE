package com.example.instrument.core;

import com.example.instrument.api.DataListener;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.metrics.ClientMetrics;
import com.example.instrument.model.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ResponseDispatcher 单元测试。
 */
class ResponseDispatcherTest {
    
    @Test
    @DisplayName("响应分发给注册的监听器")
    void dispatchesToListeners() {
        RequestManager rm = new RequestManager();
        ClientMetrics m = new ClientMetrics();
        ResponseDispatcher d = new ResponseDispatcher(rm, ClientConfig.defaults(), m);
        
        AtomicReference<Response> received = new AtomicReference<>();
        d.addListener(received::set);
        
        Response r = new Response(
            "TEST\r\n".getBytes(StandardCharsets.UTF_8),
            "TEST".getBytes(StandardCharsets.UTF_8)
        );
        d.dispatch(r);
        
        assertNotNull(received.get());
        assertEquals("TEST", received.get().text(StandardCharsets.UTF_8));
    }
    
    @Test
    @DisplayName("多个监听器都能收到同一份响应")
    void multipleListeners() {
        RequestManager rm = new RequestManager();
        ClientMetrics m = new ClientMetrics();
        ResponseDispatcher d = new ResponseDispatcher(rm, ClientConfig.defaults(), m);
        
        AtomicReference<Response> first = new AtomicReference<>();
        AtomicReference<Response> second = new AtomicReference<>();
        
        d.addListener(first::set);
        d.addListener(second::set);
        
        Response r = new Response(
            "DATA\r\n".getBytes(StandardCharsets.UTF_8),
            "DATA".getBytes(StandardCharsets.UTF_8)
        );
        d.dispatch(r);
        
        assertNotNull(first.get());
        assertNotNull(second.get());
        assertEquals("DATA", first.get().text(StandardCharsets.UTF_8));
        assertEquals("DATA", second.get().text(StandardCharsets.UTF_8));
    }
    
    @Test
    @DisplayName("移除监听器后不再接收响应")
    void removeListener() {
        RequestManager rm = new RequestManager();
        ClientMetrics m = new ClientMetrics();
        ResponseDispatcher d = new ResponseDispatcher(rm, ClientConfig.defaults(), m);
        
        AtomicReference<Response> received = new AtomicReference<>();
        DataListener listener = received::set;
        
        d.addListener(listener);
        d.removeListener(listener);
        
        Response r = new Response(
            "TEST\r\n".getBytes(StandardCharsets.UTF_8),
            "TEST".getBytes(StandardCharsets.UTF_8)
        );
        d.dispatch(r);
        
        assertNull(received.get());
    }
    
    @Test
    @DisplayName("一个监听器抛异常不影响其他监听器")
    void listenerExceptionDoesNotAffectOthers() {
        RequestManager rm = new RequestManager();
        ClientMetrics m = new ClientMetrics();
        ResponseDispatcher d = new ResponseDispatcher(rm, ClientConfig.defaults(), m);
        
        AtomicReference<Response> working = new AtomicReference<>();
        
        d.addListener(r -> { throw new RuntimeException("Listener error"); });
        d.addListener(working::set);
        
        Response r = new Response(
            "OK\r\n".getBytes(StandardCharsets.UTF_8),
            "OK".getBytes(StandardCharsets.UTF_8)
        );
        d.dispatch(r);
        
        assertNotNull(working.get());
        assertEquals("OK", working.get().text(StandardCharsets.UTF_8));
    }
    
    @Test
    @DisplayName("dispatch 同时写入阻塞队列供 BlockingDataListener 消费")
    void blockingQueue() {
        RequestManager rm = new RequestManager();
        ClientMetrics m = new ClientMetrics();
        ResponseDispatcher d = new ResponseDispatcher(rm, ClientConfig.defaults(), m);
        
        BlockingQueue<Response> queue = d.queue();
        
        Response r = new Response(
            "BLOCKING\r\n".getBytes(StandardCharsets.UTF_8),
            "BLOCKING".getBytes(StandardCharsets.UTF_8)
        );
        d.dispatch(r);
        
        assertEquals(r, queue.poll());
    }
    
    @Test
    @DisplayName("队列为空时 poll 返回 null")
    void emptyQueuePoll() {
        RequestManager rm = new RequestManager();
        ClientMetrics m = new ClientMetrics();
        ResponseDispatcher d = new ResponseDispatcher(rm, ClientConfig.defaults(), m);
        
        BlockingQueue<Response> queue = d.queue();
        
        assertNull(queue.poll());
    }
    
    @Test
    @DisplayName("队列容量由 ClientConfig.asyncQueueCapacity 决定")
    void queueHasCapacity() {
        ClientConfig config = ClientConfig.builder()
            .asyncQueueCapacity(5)
            .build();
        
        RequestManager rm = new RequestManager();
        ClientMetrics m = new ClientMetrics();
        ResponseDispatcher d = new ResponseDispatcher(rm, config, m);
        
        BlockingQueue<Response> queue = d.queue();
        
        assertTrue(queue.isEmpty());
        assertEquals(5, queue.remainingCapacity());
    }
}