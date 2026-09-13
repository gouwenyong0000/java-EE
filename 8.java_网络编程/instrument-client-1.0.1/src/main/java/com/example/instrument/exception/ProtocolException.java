package com.example.instrument.exception;

/**
 * 协议相关异常，当协议编解码出现问题时抛出。
 * 
 * <p>可能触发此异常的场景：</p>
 * <ul>
 *   <li>数据格式错误（如分隔符不匹配、长度字段超限）</li>
 *   <li>校验和不匹配</li>
 *   <li>数据长度超出协议允许范围</li>
 *   <li>解码过程中发现数据损坏</li>
 * </ul>
 * 
 * @see InstrumentException
 * @see com.example.instrument.protocol.Protocol
 */
public class ProtocolException extends InstrumentException {
    
    /**
     * 使用指定消息创建异常。
     *
     * @param message 异常消息
     */
    public ProtocolException(String message) { 
        super(message); 
    }
}