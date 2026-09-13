package com.example.instrument.protocol;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 协议接口，定义了命令编码和响应解码的抽象。
 * 
 * 该接口是整个协议层的基础，连接了应用层和网络层。
 * 实现类需要定义具体的编解码格式，如：
 * <ul>
 *   <li>分隔符协议（如 SCPI 的换行符分隔）</li>
 *   <li>长度字段协议（如 Modbus 的 0xAA + 长度 + 数据 + 校验和）</li>
 *   <li>自定义协议</li>
 * </ul>
 * 
 * <p>设计原则：</p>
 * <ul>
 *   <li>无状态：Protocol 实例不保存状态，每次调用创建新的编码器/解码器</li>
 *   <li>线程安全：编解码器本身应该是线程安全的，或者每次创建新实例</li>
 *   <li>职责单一：只负责数据格式转换，不涉及业务逻辑</li>
 * </ul>
 * 
 * @see ProtocolEncoder
 * @see ProtocolDecoder
 * @see LineProtocol
 * @see LengthFieldProtocol
 */
public interface Protocol {
    
    /**
     * 创建一个新的协议编码器。
     * 
     * 每次调用返回一个新的编码器实例，确保线程安全。
     *
     * @return 协议编码器实例
     */
    ProtocolEncoder newEncoder();

    /**
     * 创建一个新的协议解码器。
     * 
     * 每次调用返回一个新的解码器实例，因为解码器通常包含状态（如缓冲区）。
     *
     * @return 协议解码器实例
     */
    ProtocolDecoder newDecoder();

    /**
     * 获取协议使用的字符编码。
     * 
     * 默认使用 UTF-8。
     *
     * @return 字符编码
     */
    default Charset charset() { 
        return StandardCharsets.UTF_8; 
    }
}