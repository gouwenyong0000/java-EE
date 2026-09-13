package com.example.instrument.exception;

/**
 * 连接相关异常，当与服务器的连接出现问题时抛出。
 * 
 * <p>可能触发此异常的场景：</p>
 * <ul>
 *   <li>连接建立失败（如服务器不可达）</li>
 *   <li>连接意外断开（如网络故障、服务器关闭）</li>
 *   <li>写入数据失败（如连接已关闭）</li>
 *   <li>读取数据失败（如连接重置）</li>
 *   <li>客户端已关闭但仍尝试使用</li>
 * </ul>
 * 
 * @see InstrumentException
 */
public class ConnectionException extends InstrumentException {
    
    /**
     * 使用指定消息创建异常。
     *
     * @param message 异常消息
     */
    public ConnectionException(String message) { 
        super(message); 
    }

    /**
     * 使用指定消息和原因创建异常。
     *
     * @param message 异常消息
     * @param cause   异常原因
     */
    public ConnectionException(String message, Throwable cause) { 
        super(message, cause); 
    }
}