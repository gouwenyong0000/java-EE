package com.example.instrument.protocol;

import com.example.instrument.exception.ProtocolException;
import com.example.instrument.model.Response;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
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
 * @see Protocol
 * @see ProtocolEncoder
 * @see ProtocolDecoder
 */
public final class LengthFieldProtocol implements Protocol {

    /** STX 起始标记魔数常量 */
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
     * 创建编码器（使用 ByteBuffer 避免显式数组拷贝）。
     */
    @Override
    public ProtocolEncoder newEncoder() {
        return command -> {
            byte[] payload = command.bytes();
            if (payload.length > maxPayloadLength) {
                throw new ProtocolException("payload too large: " + payload.length);
            }

            int totalLen = payload.length + FRAME_OVERHEAD;
            ByteBuffer buf = ByteBuffer.allocate(totalLen);
            buf.put(STX);
            buf.putShort((short) payload.length);
            buf.put(payload);
            byte[] out = buf.array();
            out[totalLen - 1] = checksum(out, 0, totalLen - 1);
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
     * ByteBuffer 使用范式说明
     *
     * <p>本解码器使用 ByteBuffer 作为环形缓冲区，管理读写两种状态的切换。</p>
     *
     * <h3>核心概念</h3>
     * <ul>
     *   <li><b>position</b>：当前读写位置，指向下一个要读取/写入的字节</li>
     *   <li><b>limit</b>：结束位置，标记有效数据的边界</li>
     *   <li><b>capacity</b>：缓冲区总容量</li>
     * </ul>
     *
     * <h3>两种工作模式</h3>
     * <ol>
     *   <li><b>写模式</b>：写操作后 position 移动，limit = capacity
     *     <pre>[已写入数据][待写入区域]
     *           ^        ^
     *       position   capacity=limit</pre>
     *   </li>
     *   <li><b>读模式</b>：flip() 后 limit = 原 position，position = 0
     *     <pre>[已读][待读数据][未使用]
     *           ^     ^        ^
     *         pos   limit   capacity</pre>
     *   </li>
     * </ol>
     *
     * <h3>关键方法</h3>
     * <table border="1" cellpadding="5" cellspacing="0">
     *   <tr><th>方法</th><th>功能</th><th>状态变化</th></tr>
     *   <tr><td>flip()</td><td>切换写模式→读模式</td><td>limit=position, position=0</td></tr>
     *   <tr><td>compact()</td><td>压缩已读数据，保留未读数据</td><td>未读数据前移，position=remaining, limit=capacity</td></tr>
     *   <tr><td>clear()</td><td>重置缓冲区</td><td>position=0, limit=capacity（不清空数据）</td></tr>
     *   <tr><td>mark()/reset()</td><td>标记/恢复位置</td><td>保存当前 position，需要时可恢复</td></tr>
     *   <tr><td>remaining()</td><td>获取剩余可读字节数</td><td>返回 limit - position</td></tr>
     *   <tr><td>hasRemaining()</td><td>判断是否有数据可读</td><td>返回 position &lt; limit</td></tr>
     *   <tr><td>get()</td><td>读取下一字节</td><td>position += 1</td></tr>
     *   <tr><td>get(int i)</td><td>读取指定位置字节</td><td>position 不变</td></tr>
     * </table>
     *
     * <h3>本解码器工作流程</h3>
     * <pre>
     * 1. put(data) 后：position=数据长度, limit=1024（写模式）
     * 2. flip() 后：position=0, limit=数据长度（读模式）
     * 3. 处理完一帧后 compact() 后：未处理数据前移, position=未处理数据长度（写模式）
     * </pre>
     *
     * @see ByteBuffer
     */
    private static final class Decoder implements ProtocolDecoder {

        private final int max;
        private ByteBuffer buffer = ByteBuffer.allocate(1024);

        Decoder(int max) {
            this.max = max;
        }

        /**
         * 解码数据，提取完整的响应帧。
         *
         * <p>处理网络粘包、拆包问题。协议格式：[STX][长度(2字节)][数据][校验和]</p>
         *
         * @param data   字节数组
         * @param offset 数据起始偏移
         * @param length 数据长度
         * @return 解析出的响应列表
         */
        @Override
        public synchronized List<Response> decode(byte[] data, int offset, int length) {
            if (length == 0) {
                return List.of();
            }

            // 追加新数据到缓冲区
            ensureCapacity(length);
            buffer.put(data, offset, length);

            List<Response> out = new ArrayList<>();

            while (true) {
                // 切换到读模式
                buffer.flip();

                // 数据不足一个帧头，等待更多数据
                if (buffer.remaining() < FRAME_OVERHEAD) {
                    buffer.compact();
                    break;
                }

                // 查找 STX 起始标记
                int stxIndexOffSet = indexOfStx();
                if (stxIndexOffSet < 0) {
                    // 无有效帧，清空缓冲区
                    buffer.clear();
                    break;
                }

                // 跳过 STX 前的噪声数据,调整 position 到 STX 位置后，压缩已读数据，准备继续读取
                if (stxIndexOffSet > 0) {
                    buffer.position(buffer.position() + stxIndexOffSet);
                    buffer.compact();
                    buffer.flip();

                    if (buffer.remaining() < FRAME_OVERHEAD) {
                        buffer.compact();
                        break;
                    }
                }

                // 读取长度字段
                buffer.mark();// 标记当前 position 位置，用于后续帧提取
                buffer.get(); // 跳过 STX 字节
                int payloadLen = buffer.getShort() & 0xFFFF;
                buffer.reset();// 恢复到mark位置

                // 长度超限，丢弃 STX 后重同步
                if (payloadLen > max) {
                    buffer.position(buffer.position() + 1);
                    buffer.compact();
                    continue;
                }

                // 检查完整帧是否已到达
                int total = FRAME_OVERHEAD + payloadLen;
                if (buffer.remaining() < total) {
                    // 数据不完整（拆包），等待更多数据
                    buffer.compact();
                    break;
                }

                // 验证校验和
                byte expected = checksum(buffer.array(), buffer.position(), total - 1);
                byte actual = buffer.get(buffer.position() + total - 1);
                if (expected != actual) {
                    // 校验失败，丢弃 STX 后重同步
                    buffer.position(buffer.position() + 1);
                    buffer.compact();
                    continue;
                }

                // 提取帧数据和有效载荷
                byte[] frame = new byte[total];
                buffer.get(frame);
                byte[] body = new byte[payloadLen];
                System.arraycopy(frame, 3, body, 0, payloadLen);
                out.add(new Response(frame, body));

                // 压缩缓冲区，准备处理下一帧
                buffer.compact();
            }

            // 缓冲区溢出保护
            if (buffer.position() > max + FRAME_OVERHEAD) {
                throw new ProtocolException("binary buffer exceeds configured maximum");
            }

            return out;
        }

        /**
         * 查找 STX 标记的位置（相对于当前 position）。
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
         * 确保缓冲区有足够的剩余空间写入新数据。
         */
        private void ensureCapacity(int additionalLength) {
            if (buffer.remaining() < additionalLength) {
                int requiredSize = buffer.position() + additionalLength;
                int newCapacity = Math.max(buffer.capacity() * 2, requiredSize);
                ByteBuffer newBuf = ByteBuffer.allocate(newCapacity);
                buffer.flip();
                newBuf.put(buffer);
                buffer = newBuf;
            }
        }

        @Override
        public synchronized void reset() {
            buffer.clear();
        }
    }
}