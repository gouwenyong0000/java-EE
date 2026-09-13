package com.example.instrument.model;

import com.example.instrument.api.ResponseMatcher;
import java.time.Duration;
import java.util.Objects;

/**
 * 请求记录类，将命令、匹配器、超时时间和幂等性封装在一起。
 * 
 * 这是一个不可变记录类（record），用于在请求处理过程中传递上下文信息。
 * RequestManager 使用它来管理请求的生命周期。
 * 
 * <p>请求验证：</p>
 * <ul>
 *   <li>command 不能为空</li>
 *   <li>matcher 不能为空</li>
 *   <li>timeout 必须为正数</li>
 *   <li>idempotency 不能为空</li>
 * </ul>
 * 
 * @see Command
 * @see ResponseMatcher
 * @see CommandIdempotency
 * @see com.example.instrument.core.RequestManager
 */
public record Request(Command command, ResponseMatcher matcher, Duration timeout,
                      CommandIdempotency idempotency) {
    
    /**
     * 构造函数，验证参数有效性。
     *
     * @param command    命令，不能为空
     * @param matcher    响应匹配器，不能为空
     * @param timeout    超时时间，必须为正数，不能为空
     * @param idempotency 幂等性，不能为空
     * @throws NullPointerException     如果任何参数为空
     * @throws IllegalArgumentException 如果 timeout 不是正数
     */
    public Request {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(matcher, "matcher");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(idempotency, "idempotency");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be > 0");
        }
    }
}