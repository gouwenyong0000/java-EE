package com.example.instrument.protocol;

import com.example.instrument.model.Response;
import java.util.List;

/**
 * 协议解码器接口，将字节数据解码为 Response 对象列表。
 * 
 * 解码器需要处理以下网络通信中的常见问题：
 * <ul>
 *   <li>分片（Fragmentation）：数据被分割成多个数据包</li>
 *   <li>粘包（Sticky Packet）：多个响应合并在一个数据包中</li>
 *   <li>不完整数据：数据到达时不完整，需要缓存等待更多数据</li>
 * </ul>
 * 
 * <p>线程安全：</p>
 * 实现类应该是线程安全的，或者每次解码调用是原子的。
 * 如果解码器包含内部状态（如缓冲区），需要做好同步。
 * 
 * @see Protocol
 * @see Response
 * @see com.example.instrument.protocol.LineProtocol
 * @see com.example.instrument.protocol.LengthFieldProtocol
 */
public interface ProtocolDecoder {
    
    /**
     * 解码字节数据为响应列表。
     * 
     * 这是一个增量解码方法：
     * <ul>
     *   <li>输入可能是完整的一个响应</li>
     *   <li>输入可能是多个响应</li>
     *   <li>输入可能是不完整的响应（需要缓存等待更多数据）</li>
     *   <li>输入可能是上次解码剩余数据 + 新数据的组合</li>
     * </ul>
     *
     * @param data   原始字节数据
     * @param offset 数据起始偏移量
     * @param length 数据长度
     * @return 解码出的响应列表，如果数据不完整则返回空列表
     * @throws IndexOutOfBoundsException 如果 offset 或 length 无效
     * @throws com.example.instrument.exception.ProtocolException 如果数据格式错误
     */
    List<Response> decode(byte[] data, int offset, int length);

    /**
     * 重置解码器状态。
     * 
     * 当连接断开后重新连接时，需要重置解码器以清除残留的缓冲区数据。
     */
    void reset();
}