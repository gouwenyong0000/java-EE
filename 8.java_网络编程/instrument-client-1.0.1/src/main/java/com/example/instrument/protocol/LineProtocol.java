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
 * 行分隔符协议实现，使用指定的字节序列作为帧分隔符。
 * 
 * <p>协议格式：</p>
 * <pre>
 * [数据][分隔符]
 * </pre>
 * 
 * <p>例如使用 CRLF (\r\n) 作为分隔符：</p>
 * <pre>
 * 命令: "PING"  -> 编码为: [P,I,N,G,\r,\n]
 * 响应: "OK"    <- 接收自: [O,K,\r,\n]
 * </pre>
 * 
 * <p>支持的特性：</p>
 * <ul>
 *   <li>分片处理：数据可能被分割成多个包，解码器会缓存不完整的数据</li>
 *   <li>粘包处理：多个响应可能在一个包中，解码器会逐一解析</li>
 *   <li>最大帧长度限制：防止恶意数据导致内存溢出</li>
 * </ul>
 * 
 * <p>使用示例：</p>
 * <pre>{@code
 * // 创建使用 CRLF 分隔的协议
 * Protocol protocol = LineProtocol.crlf(StandardCharsets.UTF_8);
 * 
 * // 创建使用 LF 分隔的协议
 * Protocol protocol2 = LineProtocol.lf(StandardCharsets.UTF_8);
 * 
 * // 创建自定义最大帧长度的协议
 * Protocol protocol3 = LineProtocol.crlf(StandardCharsets.UTF_8).withMaxFrameLength(4096);
 * }</pre>
 * 
 * @see Protocol
 * @see ProtocolEncoder
 * @see ProtocolDecoder
 */
public final class LineProtocol implements Protocol {
    
    private final byte[] delimiter;
    private final Charset charset;
    private final int maxFrameLength;

    /**
     * 私有构造函数。
     *
     * @param delimiter       分隔符字节数组
     * @param charset         字符编码
     * @param maxFrameLength  最大帧长度
     */
    private LineProtocol(byte[] delimiter, Charset charset, int maxFrameLength) {
        this.delimiter = delimiter.clone();
        this.charset = Objects.requireNonNull(charset);
        this.maxFrameLength = maxFrameLength;
        
        if (delimiter.length == 0) {
            throw new IllegalArgumentException("delimiter is empty");
        }
        if (maxFrameLength <= delimiter.length) {
            throw new IllegalArgumentException("maxFrameLength too small");
        }
    }

    /**
     * 创建使用 CRLF (\r\n) 作为分隔符的协议。
     *
     * @param charset 字符编码
     * @return 新的 LineProtocol 实例
     */
    public static LineProtocol crlf(Charset charset) { 
        return new LineProtocol(new byte[]{'\r', '\n'}, charset, 1024 * 1024); 
    }

    /**
     * 创建使用 LF (\n) 作为分隔符的协议。
     *
     * @param charset 字符编码
     * @return 新的 LineProtocol 实例
     */
    public static LineProtocol lf(Charset charset) { 
        return new LineProtocol(new byte[]{'\n'}, charset, 1024 * 1024); 
    }

    /**
     * 创建一个带有自定义最大帧长度的协议副本。
     *
     * @param max 新的最大帧长度
     * @return 新的 LineProtocol 实例
     */
    public LineProtocol withMaxFrameLength(int max) { 
        return new LineProtocol(delimiter, charset, max); 
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
     * 编码逻辑：在命令数据后追加分隔符。
     */
    @Override 
    public ProtocolEncoder newEncoder() {
        return command -> {
            byte[] body = command.bytes();
            byte[] out = Arrays.copyOf(body, body.length + delimiter.length);
            System.arraycopy(delimiter, 0, out, body.length, delimiter.length);
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
        return new Decoder(delimiter, maxFrameLength); 
    }

    /**
     * 行协议解码器内部类。
     * 
     * 维护一个 ByteArrayOutputStream 作为缓冲区，累积接收到的数据，
     * 直到找到完整的分隔符才进行解码。
     */
    private static final class Decoder implements ProtocolDecoder {
        
        private final byte[] delimiter;
        private final int max;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        Decoder(byte[] delimiter, int max) {
            this.delimiter = delimiter.clone();
            this.max = max;
        }

        /**
         * 解码数据，查找分隔符并提取响应。
         * 
         * 解码流程：
         * 1. 将新数据追加到缓冲区
         * 2. 在缓冲区中查找分隔符
         * 3. 每找到一个分隔符，提取其前面的内容作为响应
         * 4. 保留分隔符之后的数据，继续累积
         */
        @Override 
        public synchronized List<Response> decode(byte[] data, int offset, int length) {
            if (length == 0) {
                return List.of();
            }
            if (offset < 0 || length < 0 || offset + length > data.length) {
                throw new IndexOutOfBoundsException();
            }
            
            buffer.write(data, offset, length);
            
            if (buffer.size() > max + delimiter.length) {
                throw new ProtocolException("line frame exceeds max length: " + max);
            }
            
            byte[] bytes = buffer.toByteArray();
            List<Response> out = new ArrayList<>();
            int start = 0;
            
            while (true) {
                int pos = indexOf(bytes, delimiter, start);
                if (pos < 0) {
                    break;
                }
                
                int frameEnd = pos + delimiter.length;
                byte[] frame = Arrays.copyOfRange(bytes, start, frameEnd);
                byte[] body = Arrays.copyOfRange(bytes, start, pos);
                out.add(new Response(frame, body));
                start = frameEnd;
            }
            
            if (start > 0) {
                buffer.reset();
                buffer.write(bytes, start, bytes.length - start);
            }
            
            if (buffer.size() > max) {
                throw new ProtocolException("line frame exceeds max length: " + max);
            }
            
            return out;
        }

        /**
         * 在字节数组中查找目标序列。
         */
        private static int indexOf(byte[] data, byte[] target, int from) {
            outer: for (int i = from; i <= data.length - target.length; i++) {
                for (int j = 0; j < target.length; j++) {
                    if (data[i + j] != target[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }

        @Override 
        public synchronized void reset() {
            buffer.reset();
        }
    }
}