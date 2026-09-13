package com.example.instrument.exception;

/**
 * 请求超时异常，当请求在指定时间内未收到响应时抛出。
 *
 * <p>可能触发此异常的场景：</p>
 * <ul>
 *   <li>服务器未在超时时间内响应</li>
 *   <li>网络延迟过大</li>
 *   <li>服务器负载过高无法及时处理请求</li>
 *   <li>命令格式错误导致服务器无响应</li>
 * </ul>
 *
 * @see InstrumentException
 * @see com.example.instrument.api.InstrumentClient#request
 */
public class RequestTimeoutException extends InstrumentException {

    /**
     * 使用指定消息创建异常。
     *
     * @param message 异常消息
     */
    public RequestTimeoutException(String message) {
        super(message);
    }
}