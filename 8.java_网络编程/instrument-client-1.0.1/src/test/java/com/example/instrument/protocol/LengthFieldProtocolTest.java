package com.example.instrument.protocol;

import com.example.instrument.model.Command;
import com.example.instrument.model.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LengthFieldProtocol 单元测试。
 *
 * <p>协议格式：STX(0xAA) | 长度高字节 | 长度低字节 | 数据 | XOR 校验和</p>
 */
class LengthFieldProtocolTest {

    @Test
    @DisplayName("编码帧结构: 0xAA | LEN_H | LEN_L | 数据 | 校验和 —— 总长度 = payload + 4")
    void encodeFrameStructure() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        byte[] frame = p.newEncoder().encode(Command.text("ABC"));

        assertEquals(7, frame.length);
        assertEquals((byte) 0xAA, frame[0]);
        assertEquals(0, frame[1]);
        assertEquals(3, frame[2]);
        assertEquals('A', frame[3]);
        assertEquals('B', frame[4]);
        assertEquals('C', frame[5]);
    }

    @Test
    @DisplayName("解码完整帧提取有效载荷 —— body 不包含 STX/长度/校验和")
    void decodeFullFrame() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        byte[] frame = p.newEncoder().encode(Command.text("TEST"));

        List<Response> results = p.newDecoder().decode(frame, 0, frame.length);

        assertEquals(1, results.size());
        assertEquals("TEST", results.get(0).text(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("空命令编码 —— payload 长度 0，帧总长 4 字节")
    void emptyCommand() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        byte[] frame = p.newEncoder().encode(Command.text(""));
        assertEquals(4, frame.length);

        List<Response> results = p.newDecoder().decode(frame, 0, frame.length);
        assertEquals(1, results.size());
        assertEquals("", results.get(0).text(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("粘包解码 —— 两帧一起到达被正确拆分")
    void stickyFrames() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        byte[] frame1 = p.newEncoder().encode(Command.text("A"));
        byte[] frame2 = p.newEncoder().encode(Command.text("BB"));

        byte[] combined = new byte[frame1.length + frame2.length];
        System.arraycopy(frame1, 0, combined, 0, frame1.length);
        System.arraycopy(frame2, 0, combined, frame1.length, frame2.length);

        List<Response> results = p.newDecoder().decode(combined, 0, combined.length);

        assertEquals(2, results.size());
        assertEquals("A", results.get(0).text(StandardCharsets.UTF_8));
        assertEquals("BB", results.get(1).text(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("拆包解码 —— 一帧分多次到达，解码器缓存后正确还原")
    void fragmentation() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        ProtocolDecoder d = p.newDecoder();

        byte[] frame = p.newEncoder().encode(Command.text("TEST"));

        assertTrue(d.decode(frame, 0, 1).isEmpty());
        assertTrue(d.decode(frame, 1, 2).isEmpty());
        assertTrue(d.decode(frame, 3, 3).isEmpty());

        var result = d.decode(frame, 6, 2);
        assertEquals(1, result.size());
        assertEquals("TEST", result.get(0).text(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("自定义 maxPayloadLength —— 编码超过限制的 payload 抛 ProtocolException")
    void customMaxPayloadLength() {
        LengthFieldProtocol p = new LengthFieldProtocol(StandardCharsets.UTF_8, 4);

        assertDoesNotThrow(() -> p.newEncoder().encode(Command.text("OK")));

        assertThrows(com.example.instrument.exception.ProtocolException.class, () ->
            p.newEncoder().encode(Command.text("TOOLONG"))
        );
    }

    @Test
    @DisplayName("默认配置 defaults() —— 最大载荷长度 65535")
    void defaultsMaxPayload() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        byte[] big = new byte[1000];
        for (int i = 0; i < big.length; i++) big[i] = 'X';

        assertDoesNotThrow(() -> p.newEncoder().encode(Command.of(big)));
    }

    @Test
    @DisplayName("无效校验和 —— 解码器跳过损坏帧继续同步")
    void invalidChecksumSkipped() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        byte[] frame = p.newEncoder().encode(Command.text("GOOD"));

        frame[frame.length - 1] ^= 0xFF;

        List<Response> results = p.newDecoder().decode(frame, 0, frame.length);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("STX 前有杂数据 —— 解码器自动同步到下一帧起始")
    void stxSynchronization() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        byte[] good = p.newEncoder().encode(Command.text("HI"));

        byte[] withGarbage = new byte[good.length + 3];
        withGarbage[0] = 0x00;
        withGarbage[1] = 0x01;
        withGarbage[2] = 0x02;
        System.arraycopy(good, 0, withGarbage, 3, good.length);

        List<Response> results = p.newDecoder().decode(withGarbage, 0, withGarbage.length);
        assertEquals(1, results.size());
        assertEquals("HI", results.get(0).text(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("checksum 是 XOR 运算 —— 从 STX 到数据末尾逐字节异或")
    void checksumAlgorithm() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        byte[] frame = p.newEncoder().encode(Command.text("DATA"));

        byte expected = 0;
        for (int i = 0; i < frame.length - 1; i++) {
            expected ^= frame[i];
        }
        assertEquals(expected, frame[frame.length - 1]);
    }

    @Test
    @DisplayName("charset 返回构造时传入的字符集")
    void charsetReturnsConfiguredValue() {
        LengthFieldProtocol p = new LengthFieldProtocol(StandardCharsets.UTF_8, 256);
        assertEquals(StandardCharsets.UTF_8, p.charset());
    }

    @Test
    @DisplayName("构造参数校验 —— maxPayloadLength 超过 65535 抛异常")
    void maxPayloadOutOfRange() {
        assertThrows(IllegalArgumentException.class, () ->
            new LengthFieldProtocol(StandardCharsets.UTF_8, 70000)
        );
        assertThrows(IllegalArgumentException.class, () ->
            new LengthFieldProtocol(StandardCharsets.UTF_8, -1)
        );
    }
}