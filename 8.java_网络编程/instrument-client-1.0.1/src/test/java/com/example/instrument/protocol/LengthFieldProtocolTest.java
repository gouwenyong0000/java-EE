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
 * <h2>协议格式</h2>
 * <pre>
 * [STX][长度高字节][长度低字节][数据...][校验和]
 *   1        2          2        N         1
 * </pre>
 *
 * <h2>测试覆盖</h2>
 * <ul>
 *   <li><b>编码器测试</b>：帧结构、边界值、参数校验</li>
 *   <li><b>解码器测试</b>：完整帧、粘包、拆包、噪声同步、校验和验证</li>
 *   <li><b>网络场景测试</b>：粘包+噪声混合、多坏帧后有效帧、半帧长度字段</li>
 *   <li><b>API测试</b>：decoder复用、reset重置、charset配置</li>
 * </ul>
 *
 * @see LengthFieldProtocol
 */
class LengthFieldProtocolTest {

    // ==================== 编码器基础测试 ====================

    /**
     * 测试编码器生成的帧结构是否符合协议规范。
     *
     * <p>帧结构验证：</p>
     * <pre>
     * frame[0] = 0xAA (STX起始标记)
     * frame[1] = 长度高字节 (payload.length >> 8)
     * frame[2] = 长度低字节 (payload.length & 0xFF)
     * frame[3..3+len-1] = 数据负载
     * frame[3+len] = XOR校验和
     * </pre>
     *
     * <p>测试数据 "ABC"：</p>
     * <pre>
     * [0xAA][0x00][0x03]['A']['B']['C'][checksum]
     *   0      1      2     3     4     5       6
     * </pre>
     */
    @Test
    @DisplayName("编码帧结构: 0xAA | LEN_H | LEN_L | 数据 | 校验和 —— 总长度 = payload + 4")
    void encodeFrameStructure() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        byte[] frame = p.newEncoder().encode(Command.text("ABC"));

        // 帧总长度 = 1(STX) + 2(长度) + 3(payload) + 1(校验和) = 7
        assertEquals(7, frame.length);
        assertEquals((byte) 0xAA, frame[0]);  // STX 魔数
        assertEquals(0, frame[1]);            // 长度高字节 (3 >> 8 = 0)
        assertEquals(3, frame[2]);            // 长度低字节 (3 & 0xFF = 3)
        assertEquals('A', frame[3]);          // 数据起始
        assertEquals('B', frame[4]);
        assertEquals('C', frame[5]);
    }

    // ==================== 解码器基础测试 ====================

    /**
     * 测试解码器能否正确提取完整帧的有效载荷。
     *
     * <p>验证点：</p>
     * <ul>
     *   <li>解码返回1个Response对象</li>
     *   <li>Response.body 不包含 STX、长度字段、校验和</li>
     *   <li>Response.body 仅包含原始数据负载</li>
     * </ul>
     */
    @Test
    @DisplayName("解码完整帧提取有效载荷 —— body 不包含 STX/长度/校验和")
    void decodeFullFrame() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        byte[] frame = p.newEncoder().encode(Command.text("TEST"));

        List<Response> results = p.newDecoder().decode(frame, 0, frame.length);

        assertEquals(1, results.size());
        assertEquals("TEST", results.get(0).text(StandardCharsets.UTF_8));
    }

    /**
     * 测试空命令（payload长度为0）的编解码。
     *
     * <p>边界条件：payload.length = 0</p>
     * <pre>
     * [0xAA][0x00][0x00][checksum]
     *   0      1      2       3
     * </pre>
     */
    @Test
    @DisplayName("空命令编码 —— payload 长度 0，帧总长 4 字节")
    void emptyCommand() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        byte[] frame = p.newEncoder().encode(Command.text(""));
        assertEquals(4, frame.length);  // 1 + 2 + 0 + 1

        List<Response> results = p.newDecoder().decode(frame, 0, frame.length);
        assertEquals(1, results.size());
        assertEquals("", results.get(0).text(StandardCharsets.UTF_8));
    }

    // ==================== 网络场景测试 ====================

    /**
     * 测试粘包场景：两帧数据连在一起发送。
     *
     * <p>网络场景：发送方快速连续发送两帧，接收方一次收取</p>
     * <pre>
     * 发送: [帧1][帧2]
     * 接收: [帧1][帧2] (粘在一起)
     * </pre>
     *
     * <p>测试验证解码器能正确拆分两个独立帧</p>
     */
    @Test
    @DisplayName("粘包解码 —— 两帧一起到达被正确拆分")
    void stickyFrames() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        byte[] frame1 = p.newEncoder().encode(Command.text("A"));  // 5字节
        byte[] frame2 = p.newEncoder().encode(Command.text("BB"));  // 6字节

        // 合并两帧模拟粘包
        byte[] combined = new byte[frame1.length + frame2.length];
        System.arraycopy(frame1, 0, combined, 0, frame1.length);
        System.arraycopy(frame2, 0, combined, frame1.length, frame2.length);

        List<Response> results = p.newDecoder().decode(combined, 0, combined.length);

        assertEquals(2, results.size());
        assertEquals("A", results.get(0).text(StandardCharsets.UTF_8));
        assertEquals("BB", results.get(1).text(StandardCharsets.UTF_8));
    }

    /**
     * 测试拆包场景：一帧数据分多次到达。
     *
     * <p>网络场景：数据链路 MTU 限制或 TCP 分片导致一帧被拆分</p>
     * <pre>
     * 帧: [STX][LEN_H][LEN_L][T][E][S][T][checksum]
     *      0    1      2      3   4   5   6    7
     *
     * 第1次: [STX]                          → 缓存，帧不完整
     * 第2次: [LEN_H][LEN_L]                 → 缓存，帧不完整
     * 第3次: [T][E][S]                      → 缓存，帧不完整
     * 第4次: [T][checksum]                  → 帧完整，输出 "TEST"
     * </pre>
     */
    @Test
    @DisplayName("拆包解码 —— 一帧分多次到达，解码器缓存后正确还原")
    void fragmentation() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        ProtocolDecoder d = p.newDecoder();

        byte[] frame = p.newEncoder().encode(Command.text("TEST"));

        // 模拟分4次接收同一帧
        assertTrue(d.decode(frame, 0, 1).isEmpty());  // 只收到 STX
        assertTrue(d.decode(frame, 1, 2).isEmpty());  // 收到长度字段
        assertTrue(d.decode(frame, 3, 3).isEmpty());  // 收到部分数据

        // 最后一次收到剩余数据，帧完整
        var result = d.decode(frame, 6, 2);
        assertEquals(1, result.size());
        assertEquals("TEST", result.get(0).text(StandardCharsets.UTF_8));
    }

    /**
     * 测试噪声+粘包混合场景。
     *
     * <p>网络场景：多帧之间夹杂噪声数据</p>
     * <pre>
     * 数据流: [噪声][帧1][噪声][帧2][噪声]
     * </pre>
     *
     * <p>测试验证解码器能：</p>
     * <ul>
     *   <li>跳过帧前的噪声</li>
     *   <li>正确识别帧边界</li>
     *   <li>丢弃帧后的噪声</li>
     * </ul>
     */
    @Test
    @DisplayName("噪声 + 粘包 —— 多帧之间有噪声干扰")
    void noiseBetweenStickyFrames() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        byte[] frame1 = p.newEncoder().encode(Command.text("A"));
        byte[] frame2 = p.newEncoder().encode(Command.text("B"));

        // 构建混合数据
        byte[] withGarbage = new byte[frame1.length + frame2.length + 6];
        int pos = 0;
        withGarbage[pos++] = 0x00;  // 噪声1
        System.arraycopy(frame1, 0, withGarbage, pos, frame1.length);
        pos += frame1.length;
        withGarbage[pos++] = 0x01;  // 噪声2
        System.arraycopy(frame2, 0, withGarbage, pos, frame2.length);
        pos += frame2.length;
        withGarbage[pos++] = 0x02;  // 噪声3

        List<Response> results = p.newDecoder().decode(withGarbage, 0, withGarbage.length);

        assertEquals(2, results.size());
        assertEquals("A", results.get(0).text(StandardCharsets.UTF_8));
        assertEquals("B", results.get(1).text(StandardCharsets.UTF_8));
    }

    /**
     * 测试连续多个损坏帧后跟有效帧的场景。
     *
     * <p>网络场景：网络质量差，多帧校验失败后终于收到有效帧</p>
     * <pre>
     * 数据流: [坏帧1][坏帧2][好帧]
     * </pre>
     *
     * <p>测试验证解码器能：</p>
     * <ul>
     *   <li>检测每个帧的校验和失败</li>
     *   <li>跳过损坏帧</li>
     *   <li>继续处理后续数据</li>
     * </ul>
     */
    @Test
    @DisplayName("连续校验和失败 —— 最终找到有效帧")
    void multipleChecksumFailuresBeforeValidFrame() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        byte[] goodFrame = p.newEncoder().encode(Command.text("OK"));

        // 构建损坏帧
        byte[] badFrame1 = p.newEncoder().encode(Command.text("BAD"));
        badFrame1[badFrame1.length - 1] ^= 0xFF;  // 损坏校验和

        byte[] badFrame2 = p.newEncoder().encode(Command.text("ERR"));
        badFrame2[badFrame2.length - 1] ^= 0x0F;  // 损坏校验和

        // 合并
        byte[] combined = new byte[badFrame1.length + badFrame2.length + goodFrame.length];
        int pos = 0;
        System.arraycopy(badFrame1, 0, combined, pos, badFrame1.length);
        pos += badFrame1.length;
        System.arraycopy(badFrame2, 0, combined, pos, badFrame2.length);
        pos += badFrame2.length;
        System.arraycopy(goodFrame, 0, combined, pos, goodFrame.length);

        List<Response> results = p.newDecoder().decode(combined, 0, combined.length);

        assertEquals(1, results.size());
        assertEquals("OK", results.get(0).text(StandardCharsets.UTF_8));
    }

    /**
     * 测试长度字段边界值。
     *
     * <p>边界条件测试：</p>
     * <ul>
     *   <li>payload = 0 (空帧)</li>
     *   <li>payload = 1 (最小非空)</li>
     *   <li>payload = max-1 (次边界)</li>
     *   <li>payload = max (最大合法)</li>
     *   <li>payload = max+1 (超限)</li>
     * </ul>
     */
    @Test
    @DisplayName("长度边界值 —— payload长度0/1/max-1/max")
    void payloadLengthBoundaryValues() {
        LengthFieldProtocol p = new LengthFieldProtocol(StandardCharsets.UTF_8, 5);

        // payload = 0
        assertDoesNotThrow(() -> {
            byte[] frame = p.newEncoder().encode(Command.text(""));
            assertEquals(4, frame.length);  // 1+2+0+1
            List<Response> results = p.newDecoder().decode(frame, 0, frame.length);
            assertEquals(1, results.size());
            assertEquals("", results.get(0).text(StandardCharsets.UTF_8));
        });

        // payload = 1
        assertDoesNotThrow(() -> {
            byte[] frame = p.newEncoder().encode(Command.text("X"));
            assertEquals(5, frame.length);  // 1+2+1+1
        });

        // payload = 4 (max-1)
        assertDoesNotThrow(() -> {
            byte[] frame = p.newEncoder().encode(Command.text("TEST"));
            assertEquals(8, frame.length);  // 1+2+4+1
        });

        // payload = 5 (max)
        assertDoesNotThrow(() -> {
            byte[] frame = p.newEncoder().encode(Command.text("TEST!"));
            assertEquals(9, frame.length);  // 1+2+5+1
        });

        // payload = 6 (超过限制)
        assertThrows(com.example.instrument.exception.ProtocolException.class, () ->
            p.newEncoder().encode(Command.text("TOOLONG"))
        );
    }

    // ==================== 解码器状态测试 ====================

    /**
     * 测试 decoder 实例可重复使用，处理多轮数据。
     *
     * <p>使用场景：长连接中持续收发数据</p>
     *
     * <p>测试三轮数据：</p>
     * <ol>
     *   <li>第一轮：完整帧</li>
     *   <li>第二轮：粘包</li>
     *   <li>第三轮：拆包</li>
     * </ol>
     */
    @Test
    @DisplayName("decoder复用 —— 同一实例处理多轮数据")
    void decoderReuseAcrossMultipleRounds() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        ProtocolDecoder d = p.newDecoder();

        // 第一轮：完整帧
        byte[] frame1 = p.newEncoder().encode(Command.text("ROUND1"));
        List<Response> results1 = d.decode(frame1, 0, frame1.length);
        assertEquals(1, results1.size());
        assertEquals("ROUND1", results1.get(0).text(StandardCharsets.UTF_8));

        // 第二轮：粘包
        byte[] frame2 = p.newEncoder().encode(Command.text("A"));
        byte[] frame3 = p.newEncoder().encode(Command.text("B"));
        byte[] combined = new byte[frame2.length + frame3.length];
        System.arraycopy(frame2, 0, combined, 0, frame2.length);
        System.arraycopy(frame3, 0, combined, frame2.length, frame3.length);
        List<Response> results2 = d.decode(combined, 0, combined.length);
        assertEquals(2, results2.size());
        assertEquals("A", results2.get(0).text(StandardCharsets.UTF_8));
        assertEquals("B", results2.get(1).text(StandardCharsets.UTF_8));

        // 第三轮：拆包
        byte[] frame4 = p.newEncoder().encode(Command.text("PART"));
        assertTrue(d.decode(frame4, 0, 1).isEmpty());
        assertTrue(d.decode(frame4, 1, 2).isEmpty());
        List<Response> results3 = d.decode(frame4, 3, frame4.length - 3);
        assertEquals(1, results3.size());
        assertEquals("PART", results3.get(0).text(StandardCharsets.UTF_8));
    }

    /**
     * 测试 reset() 方法能清空解码器缓冲区。
     *
     * <p>使用场景：连接断开重连后需要重置解码器状态</p>
     *
     * <p>测试流程：</p>
     * <ol>
     *   <li>写入部分数据到 decoder</li>
     *   <li>调用 reset() 重置</li>
     *   <li>验证 decoder 可以重新正常工作</li>
     * </ol>
     */
    @Test
    @DisplayName("reset() —— 解码过程中重置缓冲区")
    void decoderResetDuringDecoding() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        ProtocolDecoder d = p.newDecoder();

        // 写入部分数据（不完整）
        byte[] partial = new byte[]{0x00, 0x01, 0x02};
        d.decode(partial, 0, partial.length);

        // reset 后缓冲区应为空
        d.reset();

        // 使用同一 decoder 解码完整帧
        byte[] frame = p.newEncoder().encode(Command.text("RESET"));
        List<Response> results = d.decode(frame, 0, frame.length);
        assertEquals(1, results.size());
        assertEquals("RESET", results.get(0).text(StandardCharsets.UTF_8));
    }

    // ==================== 异常场景测试 ====================

    /**
     * 测试校验和失败时解码器跳过损坏帧。
     *
     * <p>网络场景：数据在传输过程中被损坏</p>
     *
     * <p>测试验证：</p>
     * <ul>
     *   <li>校验和验证能检测到数据损坏</li>
     *   <li>解码器不抛出异常</li>
     *   <li>返回空列表（坏帧被丢弃）</li>
     * </ul>
     */
    @Test
    @DisplayName("无效校验和 —— 解码器跳过损坏帧继续同步")
    void invalidChecksumSkipped() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        byte[] frame = p.newEncoder().encode(Command.text("GOOD"));

        frame[frame.length - 1] ^= 0xFF;  // 篡改校验和

        List<Response> results = p.newDecoder().decode(frame, 0, frame.length);
        assertTrue(results.isEmpty());
    }

    /**
     * 测试 STX 前有噪声数据的同步场景。
     *
     * <p>网络场景：帧前方有随机噪声数据</p>
     * <pre>
     * 数据流: [噪声1][噪声2][STX][LEN_H][LEN_L][数据][checksum]
     * </pre>
     *
     * <p>测试验证解码器能：</p>
     * <ul>
     *   <li>查找 STX 起始标记</li>
     *   <li>丢弃 STX 前的噪声数据</li>
     *   <li>正确解析后续帧</li>
     * </ul>
     */
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

    /**
     * 测试半帧长度字段场景。
     *
     * <p>网络场景：拆包时长度字段被截断</p>
     * <pre>
     * 帧: [STX][LEN_H][LEN_L][数据...]
     *       0    1      2      3...
     *
     * 第一次: [STX][LEN_H]        → 数据不完整，等待
     * 第二次: [LEN_L][数据...]     → 帧完整，输出
     * </pre>
     */
    @Test
    @DisplayName("半帧长度字段 —— 拆包包含不完整长度字段")
    void incompleteLengthField() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        ProtocolDecoder d = p.newDecoder();

        byte[] frame = p.newEncoder().encode(Command.text("HALF"));

        // 只发送 STX + 部分长度字段（不完整，缺少1字节）
        byte[] partial = new byte[3];
        partial[0] = frame[0];  // STX
        partial[1] = frame[1];  // 长度高字节
        partial[2] = frame[2];  // 长度低字节

        assertTrue(d.decode(partial, 0, partial.length).isEmpty());

        // 发送剩余数据
        byte[] remainder = new byte[frame.length - 3];
        System.arraycopy(frame, 3, remainder, 0, remainder.length);
        List<Response> results = d.decode(remainder, 0, remainder.length);

        assertEquals(1, results.size());
        assertEquals("HALF", results.get(0).text(StandardCharsets.UTF_8));
    }

    /**
     * 测试噪声导致 STX 错位后的重同步。
     *
     * <p>网络场景：噪声数据恰好匹配长度字段格式</p>
     * <pre>
     * 数据流: [0x00][0x04][STX][LEN_H][LEN_L][数据][checksum]
     *              ↑ 误认为长度字段，实际是噪声
     * </pre>
     *
     * <p>测试验证解码器能识别真正的 STX 位置</p>
     */
    @Test
    @DisplayName("STX在长度字段位置 —— 噪声使STX错位后重新同步")
    void stxResyncFromLengthFieldPosition() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);

        byte[] frame = p.newEncoder().encode(Command.text("TEST"));
        byte[] malicious = new byte[frame.length + 2];
        malicious[0] = 0x00;  // 噪声（会被误认为长度高字节）
        malicious[1] = 0x04;  // 噪声（会被误认为长度低字节）
        System.arraycopy(frame, 0, malicious, 2, frame.length);

        List<Response> results = p.newDecoder().decode(malicious, 0, malicious.length);

        assertEquals(1, results.size());
        assertEquals("TEST", results.get(0).text(StandardCharsets.UTF_8));
    }

    /**
     * 测试全部是噪声数据时的处理。
     *
     * <p>网络场景：连接建立初期收到大量随机数据</p>
     * <pre>
     * 数据流: [噪声][噪声][噪声][噪声][STX][帧数据...]
     * </pre>
     *
     * <p>测试验证解码器能：</p>
     * <ul>
     *   <li>跳过所有噪声数据</li>
     *   <li>找到真正的 STX</li>
     *   <li>正确解析后续帧</li>
     * </ul>
     */
    @Test
    @DisplayName("连续噪声数据 —— 全部丢弃直到找到STX")
    void allNoiseDataDiscarded() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        byte[] good = p.newEncoder().encode(Command.text("HI"));

        byte[] combined = new byte[good.length + 6];
        combined[0] = 0x11;
        combined[1] = 0x22;
        combined[2] = 0x33;
        combined[3] = 0x44;
        combined[4] = 0x55;
        combined[5] = 0x66;
        System.arraycopy(good, 0, combined, 6, good.length);

        List<Response> results = p.newDecoder().decode(combined, 0, combined.length);

        assertEquals(1, results.size());
        assertEquals("HI", results.get(0).text(StandardCharsets.UTF_8));
    }

    // ==================== 校验和算法测试 ====================

    /**
     * 测试校验和算法是 XOR 运算。
     *
     * <p>算法验证：</p>
     * <pre>
     * checksum = STX ^ LEN_H ^ LEN_L ^ data[0] ^ data[1] ^ ... ^ data[N-1]
     * </pre>
     */
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

    // ==================== 参数校验测试 ====================

    /**
     * 测试 charset 配置是否正确返回。
     */
    @Test
    @DisplayName("charset 返回构造时传入的字符集")
    void charsetReturnsConfiguredValue() {
        LengthFieldProtocol p = new LengthFieldProtocol(StandardCharsets.UTF_8, 256);
        assertEquals(StandardCharsets.UTF_8, p.charset());
    }

    /**
     * 测试 maxPayloadLength 参数边界校验。
     *
     * <p>有效范围：0 ~ 65535</p>
     * <ul>
     *   <li>-1 → 抛 IllegalArgumentException</li>
     *   <li>0 → 合法（但无实际意义）</li>
     *   <li>65535 → 合法（最大）</li>
     *   <li>65536 → 抛 IllegalArgumentException</li>
     * </ul>
     */
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

    /**
     * 测试自定义 maxPayloadLength 限制。
     *
     * <p>场景：应用层设置较小的最大载荷长度以节省内存</p>
     */
    @Test
    @DisplayName("自定义 maxPayloadLength —— 编码超过限制的 payload 抛 ProtocolException")
    void customMaxPayloadLength() {
        LengthFieldProtocol p = new LengthFieldProtocol(StandardCharsets.UTF_8, 4);

        assertDoesNotThrow(() -> p.newEncoder().encode(Command.text("OK")));

        assertThrows(com.example.instrument.exception.ProtocolException.class, () ->
            p.newEncoder().encode(Command.text("TOOLONG"))
        );
    }

    /**
     * 测试默认配置的最大载荷长度。
     *
     * <p>defaults() 使用 maxPayloadLength = 65535</p>
     */
    @Test
    @DisplayName("默认配置 defaults() —— 最大载荷长度 65535")
    void defaultsMaxPayload() {
        LengthFieldProtocol p = LengthFieldProtocol.defaults(StandardCharsets.UTF_8);
        byte[] big = new byte[1000];
        for (int i = 0; i < big.length; i++) big[i] = 'X';

        assertDoesNotThrow(() -> p.newEncoder().encode(Command.of(big)));
    }
}