package com.example.cli;

import com.example.cli.model.Command;
import com.example.cli.model.Response;
import com.example.cli.model.StringResponse;
import com.example.cli.protocol.LengthFieldProtocol;
import com.example.cli.protocol.LineProtocol;
import com.example.cli.protocol.Protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Protocol 单元测试")
class ProtocolTest {

  @Nested
  @DisplayName("LineProtocol 文本协议")
  class LineProtocolTests {

    @Test
    @DisplayName("encode：命令应自动追加 CRLF 终止符")
    void testEncodeAppendsTerminator() {
      LineProtocol p = new LineProtocol();
      byte[] frame = p.encode(Command.of("PING"));
      assertEquals("PING\r\n", new String(frame, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("encode：内容为空字符串时只输出终止符")
    void testEncodeEmptyCommand() {
      LineProtocol p = new LineProtocol();
      byte[] frame = p.encode(Command.of(""));
      assertArrayEquals("\r\n".getBytes(StandardCharsets.UTF_8), frame);
    }

    @Test
    @DisplayName("decode：单帧完整数据返回一条 Response")
    void testDecodeSingleFrame() {
      LineProtocol p = new LineProtocol();
      List<Response> frames = p.decode("PONG\r\n".getBytes(StandardCharsets.UTF_8), 0, 6);
      assertEquals(1, frames.size());
      assertEquals("PONG\r\n", frames.get(0).text());
    }

    @Test
    @DisplayName("decode：粘包（多帧一次到达）应拆分为多条 Response")
    void testDecodeStickyPackets() {
      LineProtocol p = new LineProtocol();
      byte[] data = "F1\r\nF2\r\nF3\r\n".getBytes(StandardCharsets.UTF_8);
      List<Response> frames = p.decode(data, 0, data.length);
      assertEquals(3, frames.size());
      assertEquals("F1\r\n", frames.get(0).text());
      assertEquals("F2\r\n", frames.get(1).text());
      assertEquals("F3\r\n", frames.get(2).text());
    }

    @Test
    @DisplayName("decode：拆包（不完整帧）应缓存等待后续数据")
    void testDecodeFragmentedPackets() {
      LineProtocol p = new LineProtocol();
      byte[] part1 = "FRAG:COMP".getBytes(StandardCharsets.UTF_8);
      byte[] part2 = "LETE_RESPONSE\r\n".getBytes(StandardCharsets.UTF_8);

      List<Response> first = p.decode(part1, 0, part1.length);
      assertTrue(first.isEmpty(), "不完整帧不应产出 Response");

      List<Response> second = p.decode(part2, 0, part2.length);
      assertEquals(1, second.size());
      assertEquals("FRAG:COMPLETE_RESPONSE\r\n", second.get(0).text());
    }

    @Test
    @DisplayName("decode：拆包分多次到达，中间夹杂完整帧")
    void testDecodeMixedFragmentation() {
      LineProtocol p = new LineProtocol();
      // 第一段：A\r\n（完整帧）+ B（半截帧）
      List<Response> first = p.decode("A\r\nB".getBytes(StandardCharsets.UTF_8), 0, 4);
      assertEquals(1, first.size(), "第一段应解析出完整的 A 帧");
      assertEquals("A\r\n", first.get(0).text());
      assertArrayEquals("A".getBytes(StandardCharsets.UTF_8), first.get(0).body());

      byte[] temp = p.getBufferData();
      assertEquals(1, temp.length, "缓存应包含 B 帧");
      assertArrayEquals("B".getBytes(StandardCharsets.UTF_8), temp, "缓存应包含 B 帧");


      // 第二段：\r\n（补齐 B 帧）+ C\r\n（完整帧）
      List<Response> second = p.decode("\r\nC\r\n".getBytes(StandardCharsets.UTF_8), 0, 5);
      assertEquals(2, second.size());
      assertEquals("B\r\n", second.get(0).text());
      assertEquals("C\r\n", second.get(1).text());
      assertArrayEquals("B".getBytes(StandardCharsets.UTF_8), second.get(0).body());
      assertArrayEquals("C".getBytes(StandardCharsets.UTF_8), second.get(1).body());
    }

    @Test
    @DisplayName("构造器：终止符为空字符串应抛 IllegalArgumentException")
    void testEmptyTerminatorRejected() {
      assertThrows(IllegalArgumentException.class, () -> new LineProtocol(StandardCharsets.UTF_8, ""));
    }

    @Test
    @DisplayName("构造器：charset 为 null 应抛 NullPointerException")
    void testNullCharsetRejected() {
      assertThrows(NullPointerException.class, () -> new LineProtocol(null, "\r\n"));
    }

    @Test
    @DisplayName("构造器：terminator 为 null 应抛 NullPointerException")
    void testNullTerminatorRejected() {
      assertThrows(NullPointerException.class, () -> new LineProtocol(StandardCharsets.UTF_8, null));
    }

    @Test
    @DisplayName("自定义终止符（LF）：按 LF 切分")
    void testCustomLfTerminator() {
      LineProtocol p = new LineProtocol(StandardCharsets.UTF_8, "\n");
      byte[] frame = p.encode(Command.of("PING"));
      assertEquals("PING\n", new String(frame, StandardCharsets.UTF_8));

      List<Response> frames = p.decode("A\nB\n".getBytes(StandardCharsets.UTF_8), 0, 4);
      assertEquals(2, frames.size());
      // text() 包含终止符，body() 去掉终止符
      assertEquals("A\n", frames.get(0).text());
      assertEquals("B\n", frames.get(1).text());
      assertArrayEquals("A".getBytes(StandardCharsets.UTF_8), frames.get(0).body());
      assertArrayEquals("B".getBytes(StandardCharsets.UTF_8), frames.get(1).body());
    }
  }

  @Nested
  @DisplayName("LengthFieldProtocol 二进制协议")
  class LengthFieldProtocolTests {

    private final LengthFieldProtocol p = new LengthFieldProtocol();

    @Test
    @DisplayName("encode：帧结构为 STX + LEN_H + LEN_L + PAYLOAD + CRC")
    void testEncodeFrameStructure() {
      byte[] payload = {0x01, 0x02, 0x03};
      byte[] frame = p.encode(new Command(payload));

      assertEquals(1 + 2 + 3 + 1, frame.length);
      assertEquals((byte) 0xAA, frame[0]);
      assertEquals(0x00, frame[1]);
      assertEquals(0x03, frame[2]);
      assertEquals(0x01, frame[3]);
      assertEquals(0x02, frame[4]);
      assertEquals(0x03, frame[5]);

      byte expectedCrc = (byte) (0xAA ^ 0x00 ^ 0x03 ^ 0x01 ^ 0x02 ^ 0x03);
      assertEquals(expectedCrc, frame[6]);
    }

    @Test
    @DisplayName("decode：单帧完整二进制数据返回一条 Response")
    void testDecodeSingleBinaryFrame() {
      byte[] payload = {0x10, 0x20};
      byte[] frame = p.encode(new Command(payload));

      List<Response> frames = p.decode(frame, 0, frame.length);
      assertEquals(1, frames.size());
      // bytes() 返回完整帧（含 STX+LEN+PAYLOAD+CRC）
      assertArrayEquals(frame, frames.get(0).bytes());
      // body() 返回纯 payload（去掉 STX+LEN+CRC）
      assertArrayEquals(payload, frames.get(0).body());
    }

    @Test
    @DisplayName("decode：二进制粘包应拆分为多条 Response")
    void testDecodeBinarySticky() {
      byte[] f1 = p.encode(new Command(new byte[]{0x01}));
      byte[] f2 = p.encode(new Command(new byte[]{0x02, 0x03}));
      byte[] combined = new byte[f1.length + f2.length];
      System.arraycopy(f1, 0, combined, 0, f1.length);
      System.arraycopy(f2, 0, combined, f1.length, f2.length);

      List<Response> frames = p.decode(combined, 0, combined.length);
      assertEquals(2, frames.size());
      // bytes() 应等于完整命令帧（含帧头和校验）
      assertArrayEquals(f1, frames.get(0).bytes());
      assertArrayEquals(f2, frames.get(1).bytes());
      // body() 应为纯 payload
      assertArrayEquals(new byte[]{0x01}, frames.get(0).body());
      assertArrayEquals(new byte[]{0x02, 0x03}, frames.get(1).body());
    }

    @Test
    @DisplayName("decode：拆包分两次到达应重组")
    void testDecodeBinaryFragmented() {
      byte[] payload = {(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD};
      byte[] frame = p.encode(new Command(payload));
      int half = frame.length / 2;

      List<Response> first = p.decode(frame, 0, half);
      assertTrue(first.isEmpty(), "不完整帧不应产出 Response");

      // 缓存应包含半包
      byte[] temp = p.getBufferData();
      assertEquals(4, temp.length, "缓存应包含半包");
      assertArrayEquals(new byte[]{(byte) 0xAA, 0x00, 0x04, (byte) 0xAA}, temp);

      List<Response> second = p.decode(frame, half, frame.length - half);
      assertEquals(1, second.size());
      assertArrayEquals(frame, second.get(0).bytes());
      assertArrayEquals(payload, second.get(0).body());
    }

    @Test
    @DisplayName("decode：CRC 校验失败的帧应被丢弃，后续合法帧仍能解析")
    void testDecodeCrcFailureDiscarded() {
      byte[] payload = {0x01, 0x02};
      byte[] badFrame = p.encode(new Command(payload));
      badFrame[badFrame.length - 1] ^= 0xFF; // 破坏 CRC

      byte[] goodPayload = {0x55, 0x66};
      byte[] goodFrame = p.encode(new Command(goodPayload));

      // 先写入错误帧，再写入正确帧
      byte[] combined = new byte[badFrame.length + goodFrame.length];
      System.arraycopy(badFrame, 0, combined, 0, badFrame.length);
      System.arraycopy(goodFrame, 0, combined, badFrame.length, goodFrame.length);

      List<Response> frames = p.decode(combined, 0, combined.length);
      // 错误帧被丢弃，只解析出正确帧
      assertEquals(1, frames.size(), "CRC 失败的帧应被丢弃，只保留合法帧");
      assertArrayEquals(goodFrame, frames.get(0).bytes());
      assertArrayEquals(goodPayload, frames.get(0).body());
      // 缓存应为空
     byte[] temp = p.getBufferData();
      assertEquals(0, temp.length, "缓存应为空");
    }

    @Test
    @DisplayName("decode：payload 中含 0xAA（STX 字节）不应误判帧头")
    void testDecodeStxInPayload() {
      byte[] payload = {(byte) 0xAA, 0x01, (byte) 0xAA};
      byte[] frame = p.encode(new Command(payload));

      List<Response> frames = p.decode(frame, 0, frame.length);
      assertEquals(1, frames.size());
      // 返回帧长度应与编码出的完整帧一致
      assertEquals(frame.length, frames.get(0).bytes().length,
          "解码出的帧长度应与编码帧一致");
      assertArrayEquals(frame, frames.get(0).bytes());
      // payload 内容校验（body 应等于原始 payload，含 0xAA 魔数）
      assertArrayEquals(payload, frames.get(0).body());
    }

    @Test
    @DisplayName("encode：payload 超过 65535 字节应抛 IllegalArgumentException")
    void testEncodePayloadTooLarge() {
      byte[] huge = new byte[65536];
      assertThrows(IllegalArgumentException.class, () -> p.encode(new Command(huge)));
    }

    @Test
    @DisplayName("decode：垃圾数据前缀 + 合法帧，应跳过垃圾并解析出合法帧")
    void testDecodeResyncFromGarbage() {
      byte[] payload = {0x55};
      byte[] valid = p.encode(new Command(payload));
      byte[] withGarbage = new byte[3 + valid.length];
      withGarbage[0] = 0x00;
      withGarbage[1] = 0x11;
      withGarbage[2] = 0x22;
      System.arraycopy(valid, 0, withGarbage, 3, valid.length);

      List<Response> frames = p.decode(withGarbage, 0, withGarbage.length);
      assertEquals(1, frames.size());
      // bytes() 应为完整合法帧（跳过垃圾前缀）
      assertArrayEquals(valid, frames.get(0).bytes());
      // body() 应为纯 payload
      assertArrayEquals(payload, frames.get(0).body());
    }
  }

  @Nested
  @DisplayName("Command / Response 不可变性")
  class ImmutabilityTests {

    @Test
    @DisplayName("Command.payload() 修改返回数组不影响内部状态")
    void testCommandPayloadIsolation() {
      byte[] src = {1, 2, 3};
      Command cmd = new Command(src);
      byte[] got = cmd.payload();
      got[0] = 99;
      assertArrayEquals(new byte[]{1, 2, 3}, cmd.payload());
    }

    @Test
    @DisplayName("Response.bytes() 修改返回数组不影响内部状态")
    void testResponseBytesIsolation() {
      byte[] src = {1, 2, 3};
      Response r = new StringResponse(src, StandardCharsets.UTF_8, src);
      byte[] got = r.bytes();
      got[0] = 99;
      assertArrayEquals(new byte[]{1, 2, 3}, r.bytes());
    }

    @Test
    @DisplayName("Command.of(String) 使用 UTF-8 编码")
    void testCommandOfStringUsesUtf8() {
      Command cmd = Command.of("PING");
      assertArrayEquals("PING".getBytes(StandardCharsets.UTF_8), cmd.payload());
    }
  }

  @Test
  @DisplayName("LineProtocol 和 LengthFieldProtocol 都实现 Protocol 接口")
  void testBothImplementProtocol() {
    Protocol line = new LineProtocol();
    Protocol binary = new LengthFieldProtocol();
    assertNotNull(line);
    assertNotNull(binary);
  }
}