package com.example.instrument.protocol;

import com.example.instrument.exception.ProtocolException;
import com.example.instrument.model.Command;
import com.example.instrument.model.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LineProtocol 单元测试。
 *
 * <p>使用指定分隔符（CRLF 或 LF）作为帧边界的文本协议。</p>
 */
class LineProtocolTest {

    @Test
    @DisplayName("CRLF 协议编码在命令末尾附加 \\r\\n")
    void crlfEncoding() {
        LineProtocol p = LineProtocol.crlf(StandardCharsets.UTF_8);
        byte[] result = p.newEncoder().encode(Command.text("TEST"));

        assertArrayEquals("TEST\r\n".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    @DisplayName("LF 协议编码在命令末尾附加 \\n")
    void lfEncoding() {
        LineProtocol p = LineProtocol.lf(StandardCharsets.UTF_8);
        byte[] result = p.newEncoder().encode(Command.text("TEST"));

        assertArrayEquals("TEST\n".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    @DisplayName("CRLF 协议解码正确剥离末尾 \\r\\n 分隔符 —— body 不含分隔符")
    void crlfDecoding() {
        LineProtocol p = LineProtocol.crlf(StandardCharsets.UTF_8);
        ProtocolDecoder d = p.newDecoder();

        byte[] frame = "HELLO\r\n".getBytes(StandardCharsets.UTF_8);
        List<Response> results = d.decode(frame, 0, frame.length);

        assertEquals(1, results.size());
        assertEquals("HELLO", results.get(0).text(StandardCharsets.UTF_8));
        assertArrayEquals("HELLO\r\n".getBytes(StandardCharsets.UTF_8), results.get(0).bytes());
        assertArrayEquals("HELLO".getBytes(StandardCharsets.UTF_8), results.get(0).body());
    }

    @Test
    @DisplayName("不完整帧暂存缓冲区等分隔符到达 —— 分片拼接正确还原")
    void incompleteFrameBuffered() {
        LineProtocol p = LineProtocol.crlf(StandardCharsets.UTF_8);
        ProtocolDecoder d = p.newDecoder();

        byte[] partial = "HEL".getBytes(StandardCharsets.UTF_8);
        List<Response> results = d.decode(partial, 0, partial.length);
        assertTrue(results.isEmpty());

        byte[] rest = "LO\r\n".getBytes(StandardCharsets.UTF_8);
        results = d.decode(rest, 0, rest.length);
        assertEquals(1, results.size());
        assertEquals("HELLO", results.get(0).text(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("粘包 —— 一缓冲区内两帧被正确拆分")
    void stickyPacketDecoding() {
        LineProtocol p = LineProtocol.crlf(StandardCharsets.UTF_8);
        ProtocolDecoder d = p.newDecoder();

        byte[] frame = "A\r\nB\r\n".getBytes(StandardCharsets.UTF_8);
        List<Response> results = d.decode(frame, 0, frame.length);

        assertEquals(2, results.size());
        assertEquals("A", results.get(0).text(StandardCharsets.UTF_8));
        assertEquals("B", results.get(1).text(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("拆包 —— 一帧分多次到达仍能正确还原，且后续帧也不丢失")
    void fragmentedPacketDecoding() {
        LineProtocol p = LineProtocol.crlf(StandardCharsets.UTF_8);
        ProtocolDecoder d = p.newDecoder();

        byte[] chunk1 = "PING".getBytes(StandardCharsets.UTF_8);
        assertTrue(d.decode(chunk1, 0, chunk1.length).isEmpty());

        byte[] chunk2 = "\r\nPONG\r\n".getBytes(StandardCharsets.UTF_8);
        List<Response> results = d.decode(chunk2, 0, chunk2.length);

        assertEquals(2, results.size());
        assertEquals("PING", results.get(0).text(StandardCharsets.UTF_8));
        assertEquals("PONG", results.get(1).text(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("自定义 maxFrameLength —— 超过限制抛 ProtocolException")
    void maxFrameLengthExceeded() {
        LineProtocol p = LineProtocol.crlf(StandardCharsets.UTF_8).withMaxFrameLength(8);
        ProtocolDecoder d = p.newDecoder();

        byte[] data = "THIS_IS_A_VERY_LONG_LINE\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(ProtocolException.class, () -> d.decode(data, 0, data.length));
    }

    @Test
    @DisplayName("withMaxFrameLength 返回新实例 —— 原实例不受影响")
    void withMaxFrameLengthCreatesCopy() {
        LineProtocol original = LineProtocol.crlf(StandardCharsets.UTF_8);
        LineProtocol modified = original.withMaxFrameLength(100);

        assertNotNull(modified);
        assertNotSame(original, modified);
        assertEquals(StandardCharsets.UTF_8, modified.charset());
    }

    @Test
    @DisplayName("charset 返回构造时传入的字符集")
    void charsetReturnsConfiguredValue() {
        LineProtocol p = LineProtocol.lf(StandardCharsets.UTF_8);
        assertEquals(StandardCharsets.UTF_8, p.charset());
    }

    @Test
    @DisplayName("空行帧 —— 仅分隔符也能解码出空 body 响应")
    void emptyLineDecoding() {
        LineProtocol p = LineProtocol.crlf(StandardCharsets.UTF_8);
        ProtocolDecoder d = p.newDecoder();

        byte[] data = "\r\n".getBytes(StandardCharsets.UTF_8);
        List<Response> results = d.decode(data, 0, data.length);

        assertEquals(1, results.size());
        assertEquals("", results.get(0).text(StandardCharsets.UTF_8));
    }
}