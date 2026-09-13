package com.example.instrument.exception;

/**
 * 配置相关异常，当配置参数无效时抛出。
 *
 * <p>可能触发此异常的场景：</p>
 * <ul>
 *   <li>超时时间设置为负数或零</li>
 *   <li>缓冲区大小设置为非正数</li>
 *   <li>重连配置参数无效（如初始延迟大于最大延迟）</li>
 *   <li>必需参数为空</li>
 * </ul>
 *
 * @see InstrumentException
 * @see com.example.instrument.config.ClientConfig
 */
public class ConfigurationException extends InstrumentException {

    /**
     * 使用指定消息创建异常。
     *
     * @param message 异常消息
     */
    public ConfigurationException(String message) {
        super(message);
    }
}