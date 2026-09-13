package com.example.instrument.protocol;

import com.example.instrument.model.Command;

/**
 * 协议编码器接口，将 Command 编码为字节数组。
 * 
 * 这是函数式接口，可以使用 lambda 表达式实现。
 * 编码后的字节数组可以直接通过网络发送。
 * 
 * @see Protocol
 * @see com.example.instrument.protocol.LineProtocol
 * @see com.example.instrument.protocol.LengthFieldProtocol
 */
@FunctionalInterface
public interface ProtocolEncoder {
    
    /**
     * 将命令编码为字节数组。
     *
     * @param command 要编码的命令
     * @return 编码后的字节数组，可以直接发送
     */
    byte[] encode(Command command);
}