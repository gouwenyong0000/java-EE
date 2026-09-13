package com.example.instrument.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Command 和 Response 模型类的单元测试。
 */
class ModelTest {
    
    @Test
    @DisplayName("从字符串创建 Command")
    void commandFromText() {
        Command cmd = Command.text("HELLO");
        assertEquals(5, cmd.length());
        assertArrayEquals("HELLO".getBytes(StandardCharsets.UTF_8), cmd.bytes());
    }
    
    @Test
    @DisplayName("从字节数组创建 Command")
    void commandFromBytes() {
        byte[] data = new byte[]{0x01, 0x02, 0x03};
        Command cmd = Command.of(data);
        assertEquals(3, cmd.length());
        assertArrayEquals(data, cmd.bytes());
    }
    
    @Test
    @DisplayName("Command 不可变性 —— 外部修改不影响内部数据")
    void commandImmutability() {
        byte[] data = new byte[]{0x01, 0x02, 0x03};
        Command cmd = Command.of(data);
        data[0] = (byte) 0x99;
        
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, cmd.bytes());
    }
    
    @Test
    @DisplayName("Command.equals 和 hashCode")
    void commandEqualsAndHashCode() {
        Command a = Command.text("PING");
        Command b = Command.text("PING");
        Command c = Command.text("PONG");
        
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(a.hashCode(), b.hashCode());
    }
    
    @Test
    @DisplayName("Response 基本功能 —— frame / body / text")
    void responseBasics() {
        byte[] frame = new byte[]{0x41, 0x42, 0x43, 0x0D, 0x0A};
        byte[] body = new byte[]{0x41, 0x42, 0x43};
        
        Response resp = new Response(frame, body);
        
        assertArrayEquals(frame, resp.bytes());
        assertArrayEquals(body, resp.body());
        assertEquals("ABC", resp.text(StandardCharsets.UTF_8));
    }
    
    @Test
    @DisplayName("Response 时间戳在创建时间窗口内")
    void responseTimestamp() {
        long before = System.nanoTime();
        Response resp = new Response(new byte[]{1}, new byte[]{1});
        long after = System.nanoTime();
        
        assertTrue(resp.receivedAtNanos() >= before);
        assertTrue(resp.receivedAtNanos() <= after);
    }
    
    @Test
    @DisplayName("Response.equals 比较帧和负载")
    void responseEquals() {
        Response a = new Response(new byte[]{1, 2}, new byte[]{1});
        Response b = new Response(new byte[]{1, 2}, new byte[]{1});
        Response c = new Response(new byte[]{1, 3}, new byte[]{1});
        
        assertEquals(a, b);
        assertNotEquals(a, c);
    }
    
    @Test
    @DisplayName("CommandIdempotency 枚举值不为 null 且互不相同")
    void commandIdempotencyValues() {
        assertNotNull(CommandIdempotency.IDEMPOTENT);
        assertNotNull(CommandIdempotency.NON_IDEMPOTENT);
        assertNotEquals(CommandIdempotency.IDEMPOTENT, CommandIdempotency.NON_IDEMPOTENT);
    }
    
    @Test
    @DisplayName("Response.text() 默认使用 UTF-8 编码")
    void responseDefaultTextEncoding() {
        byte[] body = "HELLO".getBytes(StandardCharsets.UTF_8);
        Response resp = new Response(body, body);
        
        assertEquals("HELLO", resp.text());
    }
}