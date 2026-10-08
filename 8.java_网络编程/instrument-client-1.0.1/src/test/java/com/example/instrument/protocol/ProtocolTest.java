package com.example.instrument.protocol;

import com.example.instrument.model.Command;
import com.example.instrument.model.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Protocol 协议编解码测试")
class ProtocolTest {

    private static final Charset UTF8 = StandardCharsets.UTF_8;

    @Nested
    @DisplayName("LineProtocol CRLF")
    class LineProtocolCrlf {

        private final LineProtocol proto = LineProtocol.crlf(UTF8);

        @Test
        @DisplayName("编码器在命令后添加 CRLF")
        void encoderAppendsCrlf() {
            ProtocolEncoder encoder = proto.newEncoder();
            byte[] encoded = encoder.encode(Command.text("PING"));
            assertArrayEquals("PING\r\n".getBytes(UTF8), encoded);
        }

        @Test
        @DisplayName("空命令编码为 CRLF")
        void emptyCommandEncodesAsCrlf() {
            ProtocolEncoder encoder = proto.newEncoder();
            byte[] encoded = encoder.encode(Command.text(""));
            assertArrayEquals("\r\n".getBytes(UTF8), encoded);
        }

        @Test
        @DisplayName("解码器将 CRLF 分隔的数据解析为响应")
        void decoderSplitsByCrlf() {
            ProtocolDecoder decoder = proto.newDecoder();
            String raw = "OK\r\nFAIL\r\n";
            List<Response> responses = decoder.decode(
                raw.getBytes(UTF8), 0, raw.getBytes(UTF8).length);

            assertEquals(2, responses.size());
            assertEquals("OK", responses.get(0).text(UTF8));
            assertEquals("FAIL", responses.get(1).text(UTF8));
        }

        @Test
        @DisplayName("解码器处理分片数据（数据分批到达）")
        void decoderHandlesFragmentedData() {
            ProtocolDecoder decoder = proto.newDecoder();
            String part1 = "HEL";
            String part2 = "LO\r\n";
            List<Response> r1 = decoder.decode(part1.getBytes(UTF8), 0, part1.getBytes(UTF8).length);
            assertTrue(r1.isEmpty(), "不完整的数据不应产生响应");

            List<Response> r2 = decoder.decode(part2.getBytes(UTF8), 0, part2.getBytes(UTF8).length);
            assertEquals(1, r2.size());
            assertEquals("HELLO", r2.get(0).text(UTF8));
        }

        @Test
        @DisplayName("解码器处理空数据")
        void decoderHandlesEmptyData() {
            ProtocolDecoder decoder = proto.newDecoder();
            List<Response> responses = decoder.decode(new byte[0], 0, 0);
            assertTrue(responses.isEmpty());
        }

        @Test
        @DisplayName("解码器处理单字节数据")
        void decoderHandlesSingleByte() {
            ProtocolDecoder decoder = proto.newDecoder();
            byte[] part1 = "X".getBytes(UTF8);
            byte[] part2 = "\r\n".getBytes(UTF8);

            assertTrue(decoder.decode(part1, 0, part1.length).isEmpty());
            List<Response> responses = decoder.decode(part2, 0, part2.length);
            assertEquals(1, responses.size());
            assertEquals("X", responses.get(0).text(UTF8));
        }

        @Test
        @DisplayName("解码器将帧数据(body)与原始数据(frame)区分")
        void decoderSeparatesFrameAndBody() {
            ProtocolDecoder decoder = proto.newDecoder();
            String raw = "DATA\r\n";
            List<Response> responses = decoder.decode(
                raw.getBytes(UTF8), 0, raw.getBytes(UTF8).length);

            assertEquals(1, responses.size());
            Response resp = responses.get(0);
            assertArrayEquals(raw.getBytes(UTF8), resp.bytes());
            assertArrayEquals("DATA".getBytes(UTF8), resp.body());
        }

        @Test
        @DisplayName("charset() 返回指定的字符集")
        void charsetReturnsSpecified() {
            assertEquals(UTF8, proto.charset());
        }
    }

    @Nested
    @DisplayName("LineProtocol LF")
    class LineProtocolLf {

        private final LineProtocol proto = LineProtocol.lf(UTF8);

        @Test
        @DisplayName("编码器添加 LF 分隔符")
        void encoderAppendsLf() {
            ProtocolEncoder encoder = proto.newEncoder();
            byte[] encoded = encoder.encode(Command.text("CMD"));
            assertArrayEquals("CMD\n".getBytes(UTF8), encoded);
        }

        @Test
        @DisplayName("解码器按 LF 分隔")
        void decoderSplitsByLf() {
            ProtocolDecoder decoder = proto.newDecoder();
            String raw = "A\nB\nC\n";
            List<Response> responses = decoder.decode(
                raw.getBytes(UTF8), 0, raw.getBytes(UTF8).length);

            assertEquals(3, responses.size());
            assertEquals("A", responses.get(0).text(UTF8));
            assertEquals("B", responses.get(1).text(UTF8));
            assertEquals("C", responses.get(2).text(UTF8));
        }
    }

    @Nested
    @DisplayName("LineProtocol 边界条件")
    class LineProtocolEdgeCases {

        @Test
        @DisplayName("withMaxFrameLength 创建小帧长度协议")
        void withMaxFrameLengthSmall() {
            LineProtocol proto = LineProtocol.crlf(UTF8).withMaxFrameLength(4);
            ProtocolDecoder decoder = proto.newDecoder();
            byte[] data = "1\r\n".getBytes(UTF8);
            List<Response> responses = decoder.decode(data, 0, data.length);
            assertEquals(1, responses.size());
            assertEquals("1", responses.get(0).text(UTF8));
        }

        @Test
        @DisplayName("withMaxFrameLength 过大帧抛出 ProtocolException")
        void maxFrameLengthExceeded() {
            LineProtocol proto = LineProtocol.crlf(UTF8).withMaxFrameLength(3);
            ProtocolDecoder decoder = proto.newDecoder();
            byte[] data = "ABCD".getBytes(UTF8);
            assertThrows(com.example.instrument.exception.ProtocolException.class,
                () -> decoder.decode(data, 0, data.length));
        }
    }

    @Nested
    @DisplayName("LengthFieldProtocol")
    class LengthField {

        private final LengthFieldProtocol proto = LengthFieldProtocol.defaults(UTF8);

        @Test
        @DisplayName("编码器生成 STX+长度+数据+校验和 格式")
        void encoderFormat() {
            ProtocolEncoder encoder = proto.newEncoder();
            byte[] encoded = encoder.encode(Command.text("HI"));
            assertNotNull(encoded);
            assertEquals("HI".getBytes(UTF8).length + 4, encoded.length);
            assertEquals((byte) 0xAA, encoded[0]);
        }

        @Test
        @DisplayName("编码器校验和正确")
        void encoderChecksumCorrect() {
            ProtocolEncoder encoder = proto.newEncoder();
            byte[] data = "ABCD".getBytes(UTF8);
            byte[] encoded = encoder.encode(Command.of(data));

            assertEquals((byte) 0xAA, encoded[0]);
            assertEquals(data.length, ((encoded[1] & 0xff) << 8) | (encoded[2] & 0xff));
            byte expectedChecksum = LengthFieldProtocol.checksum(encoded, 0, encoded.length - 1);
            assertEquals(expectedChecksum, encoded[encoded.length - 1]);
        }

        @Test
        @DisplayName("解码器提取正确的 body")
        void decoderExtractsCorrectBody() {
            ProtocolEncoder encoder = proto.newEncoder();
            ProtocolDecoder decoder = proto.newDecoder();
            byte[] encoded = encoder.encode(Command.text("TEST"));

            List<Response> responses = decoder.decode(encoded, 0, encoded.length);
            assertEquals(1, responses.size());
            assertArrayEquals("TEST".getBytes(UTF8), responses.get(0).body());
        }

        @Test
        @DisplayName("解码器处理分片到达")
        void decoderHandlesFragmentedArrival() {
            ProtocolEncoder encoder = proto.newEncoder();
            ProtocolDecoder decoder = proto.newDecoder();
            byte[] encoded = encoder.encode(Command.text("DATA"));

            int split = encoded.length / 2;
            List<Response> r1 = decoder.decode(encoded, 0, split);
            assertTrue(r1.isEmpty());

            List<Response> r2 = decoder.decode(encoded, split, encoded.length - split);
            assertEquals(1, r2.size());
            assertEquals("DATA", r2.get(0).text(UTF8));
        }

        @Test
        @DisplayName("解码器处理粘包（多个完整帧在一个包中）")
        void decoderHandlesMultipleFrames() {
            ProtocolEncoder encoder = proto.newEncoder();
            ProtocolDecoder decoder = proto.newDecoder();
            byte[] e1 = encoder.encode(Command.text("A"));
            byte[] e2 = encoder.encode(Command.text("BB"));
            byte[] combined = new byte[e1.length + e2.length];
            System.arraycopy(e1, 0, combined, 0, e1.length);
            System.arraycopy(e2, 0, combined, e1.length, e2.length);

            List<Response> responses = decoder.decode(combined, 0, combined.length);
            assertEquals(2, responses.size());
            assertEquals("A", responses.get(0).text(UTF8));
            assertEquals("BB", responses.get(1).text(UTF8));
        }

        @Test
        @DisplayName("解码器丢弃 STX 前的噪声字节")
        void decoderDropsNoiseBeforeStx() {
            ProtocolEncoder encoder = proto.newEncoder();
            ProtocolDecoder decoder = proto.newDecoder();
            byte[] encoded = encoder.encode(Command.text("OK"));
            byte[] withNoise = new byte[encoded.length + 2];
            withNoise[0] = 0x01;
            withNoise[1] = 0x02;
            System.arraycopy(encoded, 0, withNoise, 2, encoded.length);

            List<Response> responses = decoder.decode(withNoise, 0, withNoise.length);
            assertEquals(1, responses.size());
            assertEquals("OK", responses.get(0).text(UTF8));
        }

        @Test
        @DisplayName("空数据解码返回空列表")
        void emptyDecodeReturnsEmpty() {
            ProtocolDecoder decoder = proto.newDecoder();
            assertTrue(decoder.decode(new byte[0], 0, 0).isEmpty());
        }
    }

    @Nested
    @DisplayName("Protocol 接口默认方法")
    class ProtocolDefaults {

        @Test
        @DisplayName("name() 返回简单类名")
        void nameReturnsSimpleClassName() {
            Protocol p = LineProtocol.crlf(UTF8);
            assertEquals("LineProtocol", p.name());
        }

        @Test
        @DisplayName("charset() 默认返回 UTF-8")
        void defaultCharsetIsUtf8() {
            Protocol p = new Protocol() {
                @Override
                public ProtocolEncoder newEncoder() {
                    return cmd -> cmd.bytes();
                }

                @Override
                public ProtocolDecoder newDecoder() {
                    return new ProtocolDecoder() {
                        @Override
                        public List<Response> decode(byte[] data, int offset, int length) {
                            return List.of();
                        }

                        @Override
                        public void reset() {
                        }
                    };
                }
            };
            assertEquals(StandardCharsets.UTF_8, p.charset());
        }
    }
}