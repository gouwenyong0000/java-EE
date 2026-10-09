package com.example.instrument.protocol;

import com.example.instrument.exception.ProtocolException;
import com.example.instrument.model.Response;
import java.nio.ByteBuffer;
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

    /** STX 起始标记 */
    private static final byte STX = (byte) 0xAA;

    /** 帧头长度：STX(1) + 长度字段(2) + 校验和(1) */
    private static final int FRAME_OVERHEAD = 4;

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

            byte[] out = new byte[payload.length + FRAME_OVERHEAD];
            out[0] = STX;
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
     * 使用 ByteBuffer 作为缓冲区，通过 flip/compact 操作管理读写位置，
     * 自动处理分片、粘包、校验和验证等问题。
     *
     * 优化点：
     * - 使用 ByteBuffer 管理缓冲区，利用其 compact() 方法高效压缩数据
     * - 避免手动维护 readPos/writePos，由 ByteBuffer 内部管理
     * - 动态扩容机制，初始容量 1024 字节，按需翻倍增长
     */
    private static final class Decoder implements ProtocolDecoder {

        private final int max;
        // 还可以使用ArrayOutputStream等其他缓冲区实现
        private ByteBuffer buffer = ByteBuffer.allocate(1024);

        Decoder(int max) {
            this.max = max;
        }

        /**
         * 解码数据，提取完整的响应帧。
         * <p>
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

            ensureCapacity(length);// 确保缓冲区有足够的容量
            buffer.put(data, offset, length);

            List<Response> out = new ArrayList<>();

            while (true) {
                buffer.flip();
                if (buffer.remaining() < FRAME_OVERHEAD) {
                    buffer.compact();
                    break;
                }

                int stxIndex = indexOfStx();

                // 没有找到 STX 标记，丢弃前置噪声
                if (stxIndex < 0) {
                    buffer.clear();
                    break;
                }

                if (stxIndex > 0) {
                    //
                    buffer.position(buffer.position() + stxIndex);
                    buffer.compact();
                    buffer.flip();
                    if (buffer.remaining() < FRAME_OVERHEAD) {
                        buffer.compact();
                        break;
                    }
                }

                buffer.mark();
                buffer.get();
                short payloadLen = buffer.getShort();
                buffer.reset();

                if ((payloadLen & 0xFFFF) > max) {
                    buffer.position(buffer.position() + 1);
                    buffer.compact();
                    continue;
                }

                int total = FRAME_OVERHEAD + payloadLen;
                if (buffer.remaining() < total) {
                    buffer.compact();
                    break;
                }

                byte expected = checksum(buffer.array(), buffer.position(), total - 1);
                byte actual = buffer.get(buffer.position() + total - 1);
                if (expected != actual) {
                    buffer.position(buffer.position() + 1);
                    buffer.compact();
                    continue;
                }

                byte[] frame = new byte[total];
                buffer.get(frame);
                byte[] body = Arrays.copyOfRange(frame, 3, total - 1);
                out.add(new Response(frame, body));

                buffer.compact();
            }

            if (buffer.position() > max + FRAME_OVERHEAD) {
                throw new ProtocolException("binary buffer exceeds configured maximum");
            }

            return out;
        }

        /**
         * 查找 STX (0xAA) 标记的位置（相对于当前 position）。
         */
        private int indexOfStx() {
            int originalPos = buffer.position();
            while (buffer.hasRemaining()) {
                if (buffer.get() == STX) {
                    int found = buffer.position() - 1 - originalPos;
                    buffer.position(originalPos);
                    return found;
                }
            }
            buffer.position(originalPos);
            return -1;
        }

        /**
         * 确保缓冲区有足够的容量。
         */
        private void ensureCapacity(int additionalLength) {
            if (buffer.remaining() < additionalLength) {
                int newCapacity = Math.max(buffer.capacity() * 2, buffer.position() + additionalLength);
                ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
                buffer.flip();
                newBuffer.put(buffer);
                buffer = newBuffer;
            }
        }

        @Override
        public synchronized void reset() {
            buffer.clear();
        }
    }
}