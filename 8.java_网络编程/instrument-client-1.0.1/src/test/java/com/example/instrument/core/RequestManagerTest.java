package com.example.instrument.core;

import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.model.Response;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RequestManager 单元测试。
 */
class RequestManagerTest {
    
    @Test
    @DisplayName("匹配响应能完成等待中的请求")
    void onlyMatchingResponseCompletesRequest() {
        RequestManager m = new RequestManager();
        
        PendingRequest p = m.register(ResponseMatcher.equalsText("OK", StandardCharsets.UTF_8));
        
        Response noMatch = new Response(
            "NO\r\n".getBytes(StandardCharsets.UTF_8),
            "NO".getBytes(StandardCharsets.UTF_8)
        );
        assertFalse(m.dispatch(noMatch));
        assertFalse(p.future().isDone());
        
        Response ok = new Response(
            "OK\r\n".getBytes(StandardCharsets.UTF_8),
            "OK".getBytes(StandardCharsets.UTF_8)
        );
        assertTrue(m.dispatch(ok));
        assertTrue(p.future().isDone());
        assertEquals("OK", p.future().join().text(StandardCharsets.UTF_8));
    }
    
    @Test
    @DisplayName("同时只能有一个待处理请求")
    void onlyOnePendingRequestAllowed() {
        RequestManager m = new RequestManager();
        
        m.register(ResponseMatcher.any());
        
        assertThrows(IllegalStateException.class, () -> {
            m.register(ResponseMatcher.any());
        });
    }
    
    @Test
    @DisplayName("无待处理请求时 dispatch 返回 false")
    void dispatchToNoPendingRequest() {
        RequestManager m = new RequestManager();
        
        Response response = new Response(
            "DATA\r\n".getBytes(StandardCharsets.UTF_8),
            "DATA".getBytes(StandardCharsets.UTF_8)
        );
        
        assertFalse(m.dispatch(response));
    }
    
    @Test
    @DisplayName("fail 使待处理请求异常完成")
    void failPendingRequest() {
        RequestManager m = new RequestManager();
        PendingRequest p = m.register(ResponseMatcher.any());
        
        m.fail(new RuntimeException("Connection lost"));
        
        assertTrue(p.future().isDone());
        assertTrue(p.future().isCompletedExceptionally());
    }
    
    @Test
    @DisplayName("手动移除待处理请求后不再匹配")
    void removePendingRequest() {
        RequestManager m = new RequestManager();
        PendingRequest p = m.register(ResponseMatcher.any());
        
        m.remove(p);
        
        assertFalse(m.hasPending());
        
        Response response = new Response(
            "DATA\r\n".getBytes(StandardCharsets.UTF_8),
            "DATA".getBytes(StandardCharsets.UTF_8)
        );
        assertFalse(m.dispatch(response));
    }
    
    @Test
    @DisplayName("hasPending 返回正确状态")
    void hasPending() {
        RequestManager m = new RequestManager();
        
        assertFalse(m.hasPending());
        
        PendingRequest p = m.register(ResponseMatcher.any());
        assertTrue(m.hasPending());
        
        m.remove(p);
        assertFalse(m.hasPending());
    }
    
    @Test
    @DisplayName("contains 匹配器跳过非包含数据")
    void containsMatcher() {
        RequestManager m = new RequestManager();
        
        PendingRequest p = m.register(ResponseMatcher.contains("ello", StandardCharsets.UTF_8));
        
        Response hello = new Response(
            "hello\r\n".getBytes(StandardCharsets.UTF_8),
            "hello".getBytes(StandardCharsets.UTF_8)
        );
        Response hi = new Response(
            "hi\r\n".getBytes(StandardCharsets.UTF_8),
            "hi".getBytes(StandardCharsets.UTF_8)
        );
        
        assertFalse(m.dispatch(hi));
        assertTrue(m.dispatch(hello));
    }
    
    @Test
    @DisplayName("regex 匹配器按正则过滤响应")
    void regexMatcher() {
        RequestManager m = new RequestManager();
        
        PendingRequest p = m.register(ResponseMatcher.regex("^\\d+$", StandardCharsets.UTF_8));
        
        Response valid = new Response(
            "12345\r\n".getBytes(StandardCharsets.UTF_8),
            "12345".getBytes(StandardCharsets.UTF_8)
        );
        Response invalid = new Response(
            "abc\r\n".getBytes(StandardCharsets.UTF_8),
            "abc".getBytes(StandardCharsets.UTF_8)
        );
        
        assertFalse(m.dispatch(invalid));
        assertTrue(m.dispatch(valid));
    }
    
    @Test
    @DisplayName("请求完成后状态被清除，可以继续注册下一个")
    void clearsStateAfterCompletion() {
        RequestManager m = new RequestManager();
        
        PendingRequest p1 = m.register(ResponseMatcher.equalsText("A", StandardCharsets.UTF_8));
        Response a = new Response(
            "A\r\n".getBytes(StandardCharsets.UTF_8),
            "A".getBytes(StandardCharsets.UTF_8)
        );
        m.dispatch(a);
        
        assertFalse(m.hasPending());
        
        PendingRequest p2 = m.register(ResponseMatcher.equalsText("B", StandardCharsets.UTF_8));
        Response b = new Response(
            "B\r\n".getBytes(StandardCharsets.UTF_8),
            "B".getBytes(StandardCharsets.UTF_8)
        );
        assertTrue(m.dispatch(b));
    }
}