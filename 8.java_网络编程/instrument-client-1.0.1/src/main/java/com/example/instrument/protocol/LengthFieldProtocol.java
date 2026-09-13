package com.example.instrument.protocol;

import com.example.instrument.exception.ProtocolException;
import com.example.instrument.model.Command;
import com.example.instrument.model.Response;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 长度字段协议实现，使用固定头部格式来标识帧边界。
 * 
 * <p>协议格式：</p>
 * <pre>
 * [STX][长度高字节][长度低字节][数据...][校验和]
 *   1        2          2        N         1
 * </pre>
 * 
 * <ul>
 *   <li>STX: 起始标记，固定为 0xAA</li>
 *   <li>长度: 16 位无符号整数，表示数据部分的长度</li>
 *   <li>数据: 实际的命令或响应数据</li>
 *   <li>校验和: 从 STX 到数据最后一字节的 XOR 校验和</li>
 * </ul>
 * 
 * <p>示例：</p>
 * <pre>
 * 命令: "PING" (4 字节)
 * 编码后: [0xAA, 0x00, 0x04, 'P','I','N','G', checksum]
 * 
 * 响应: "OK" (2 字节)
 * 接收自: [0xAA, 0x00, 0x02, 'O','K', checksum]
 * </pre>
 * 
 * <p>错误处理：</p>
 * <ul>
 *   <li>校验和不匹配：自动跳过当前帧的 STX 字节，继续尝试同步下一个帧</li>
 *   <li>数据长度超限：自动丢弃并继续同步</li>
 *   <li>缓冲区超限：抛出 ProtocolException</li>
 * </ul>
 * 
 * @see Protocol
 * @see ProtocolEncoder
 * @see ProtocolDecoder
 */
public final class LengthFieldProtocol implements Protocol {
    
    private final Charset charset;
    private final int maxPayloadLength;

    /**
     * 构造函数。
     *
     * @param charset          字符编码
     * @param maxPayloadLength 最大数据载荷长度（0-65535）
     * @throws IllegalArgumentException 如果参数无效
     */
    public LengthFieldProtocol(Charset charset, int maxPayloadLength) {
        this.charset = Objects.requireNonNull(charset);
        if (maxPayloadLength < 0 || maxPayloadLength > 65535) {
            throw new IllegalArgumentException();
        }
        this.maxPayloadLength = maxPayloadLength;
    }

    /**
     * 创建使用默认参数的长度字段协议。
     *
     * @param charset 字符编码
     * @return 新的 LengthFieldProtocol 实例
     */
    public static LengthFieldProtocol defaults(Charset charset) {
        return new LengthFieldProtocol(charset, 65535);
    }

    /**
     * 获取协议使用的字符编码。
     *
     * @return 字符编码
     */
    @Override 
    public Charset charset() {
        return charset;
    }

    /**
     * 创建编码器。
     * 
     * 编码格式：0xAA + 长度(2字节) + 数据 + XOR校验和
     */
    @Override 
    public ProtocolEncoder newEncoder() {
        return command -> {
            byte[] payload = command.bytes();
            if (payload.length > maxPayloadLength) {
                throw new ProtocolException("payload too large: " + payload.length);
            }
            
            byte[] out = new byte[payload.length + 4];
            out[0] = (byte) 0xAA;
            out[1] = (byte) (payload.length >>> 8);
            out[2] = (byte) payload.length;
            System.arraycopy(payload, 0, out, 3, payload.length);
            out[out.length - 1] = checksum(out, 0, out.length - 1);
            return out;
        };
    }

    /**
     * 创建解码器。
     *
     * @return 新的解码器实例
     */
    @Override 
    public ProtocolDecoder newDecoder() {
        return new Decoder(maxPayloadLength);
    }

    /**
     * 计算 XOR 校验和。
     * 
     * 从指定偏移量开始，对指定长度的字节进行 XOR 运算。
     *
     * @param b   字节数组
     * @param off 起始偏移
     * @param len 长度
     * @return 校验和
     */
    static byte checksum(byte[] b, int off, int len) {
        byte x = 0;
        for (int i = off; i < off + len; i++) {
            x ^= b[i];
        }
        return x;
    }

    /**
     * 长度字段协议解码器内部类。
     * 
     * 维护一个 ByteArrayOutputStream 作为缓冲区，
     * 自动处理分片、粘包、校验和验证等问题。
     */
    private static final class Decoder implements ProtocolDecoder {
        
        private final int max;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        Decoder(int max) {
            this.max = max;
        }

        /**
         * 解码数据，提取完整的响应帧。
         * 
         * 解码流程：
         * <ol>
         *   <li>将新数据追加到缓冲区。</li>
         *   <li>查找 STX（0xAA）标记并丢弃前置噪声。</li>
         *   <li>读取长度字段，确认完整帧已经到达。</li>
         *   <li>验证校验和，提取合法响应。</li>
         *   <li>校验失败时前移一个字节，继续重新同步。</li>
         * </ol>
         */
        @Override 
        public synchronized List<Response> decode(byte[] data, int offset, int length) {
            if (length == 0) {
                return List.of();
            }
            
            buffer.write(data, offset, length);
            List<Response> out = new ArrayList<>();
            
            while (true) {
                byte[] b = buffer.toByteArray();
                
                if (b.length < 4) {
                    break;
                }
                
                int stxIndex = indexOfStx(b);
                if (stxIndex < 0) {
                    // 没有 STX 时，当前缓存不可能组成完整帧，可以全部丢弃。
                    buffer.reset();
                    break;
                }
                
                if (stxIndex > 0) {
                    // 丢弃 STX 前的噪声并重新对齐；同一次 read() 中的其他帧仍会保留。
                    buffer.reset();
                    buffer.write(b, stxIndex, b.length - stxIndex);
                    b = buffer.toByteArray();
                    if (b.length < 4) {
                        break;
                    }
                }
                
                int payloadLen = ((b[1] & 0xff) << 8) | (b[2] & 0xff);
                if (payloadLen > max) {
                    // 长度字段来自远端，发现超限时只丢弃一个字节，继续尝试重同步。
                    discardOne();
                    continue;
                }
                
                int total = 4 + payloadLen;
                if (b.length < total) {
                    break;
                }
                
                byte expected = checksum(b, 0, total - 1);
                byte actual = b[total - 1];
                if (expected != actual) {
                    // 校验失败时只前移一个字节，避免跳过紧随其后的合法帧。
                    discardOne();
                    continue;
                }
                
                byte[] frame = Arrays.copyOfRange(b, 0, total);
                byte[] body = Arrays.copyOfRange(b, 3, total - 1);
                out.add(new Response(frame, body));
                
                buffer.reset();
                if (b.length > total) {
                    // 一个 TCP 读取可能携带多个完整帧，保留当前帧之后的剩余字节。
                    buffer.write(b, total, b.length - total);
                }
            }
            
            if (buffer.size() > max + 4) {
                throw new ProtocolException("binary buffer exceeds configured maximum");
            }
            
            return out;
        }

        /**
         * 查找 STX (0xAA) 标记的位置。
         */
        private int indexOfStx(byte[] b) {
            for (int i = 0; i < b.length; i++) {
                if ((b[i] & 0xff) == 0xAA) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * 丢弃缓冲区中的第一个字节，用于同步。
         */
        private void discardOne() {
            byte[] b = buffer.toByteArray();
            buffer.reset();
            if (b.length > 1) {
                buffer.write(b, 1, b.length - 1);
            }
        }

        @Override 
        public synchronized void reset() {
            buffer.reset();
        }
    }
}