package com.example.instrument.exception;

/**
 * 仪器客户端异常基类，所有客户端自定义异常的父类。
 * 
 * 该类是 RuntimeException 的子类，因此不需要显式声明或捕获。
 * 主要用于：
 * <ul>
 *   <li>连接相关异常 {@link ConnectionException}</li>
 *   <li>协议相关异常 {@link ProtocolException}</li>
 *   <li>请求超时异常 {@link RequestTimeoutException}</li>
 *   <li>请求取消异常 {@link RequestCancelledException}</li>
 *   <li>配置相关异常 {@link ConfigurationException}</li>
 * </ul>
 * 
 * @see ConnectionException
 * @see ProtocolException
 * @see RequestTimeoutException
 */
public class InstrumentException extends RuntimeException {
    
    /**
     * 使用指定消息创建异常。
     *
     * @param message 异常消息
     */
    public InstrumentException(String message) { 
        super(message); 
    }

    /**
     * 使用指定消息和原因创建异常。
     *
     * @param message 异常消息
     * @param cause   异常原因
     */
    public InstrumentException(String message, Throwable cause) { 
        super(message, cause); 
    }
}