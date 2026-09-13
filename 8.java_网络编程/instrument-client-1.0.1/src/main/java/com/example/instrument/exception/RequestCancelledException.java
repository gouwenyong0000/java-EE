package com.example.instrument.exception;

/**
 * 请求取消异常，当请求被取消时抛出。
 *
 * <p>可能触发此异常的场景：</p>
 * <ul>
 *   <li>客户端被关闭，所有 pending 请求都被取消</li>
 *   <li>连接断开，所有 pending 请求都被取消</li>
 *   <li>用户主动取消请求</li>
 * </ul>
 *
 * @see InstrumentException
 * @see com.example.instrument.api.InstrumentClient#close
 */
public class RequestCancelledException extends InstrumentException {

    /**
     * 使用指定消息创建异常。
     *
     * @param message 异常消息
     */
    public RequestCancelledException(String message) {
        super(message);
    }
}