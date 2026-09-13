package com.example.instrument.core;

import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.model.Response;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 待处理请求封装类，将响应匹配器和 Future 关联在一起。
 * 
 * <p>设计目的：</p>
 * 当发送一个请求后，需要等待匹配的响应。使用 PendingRequest 来管理这个等待过程：
 * <ul>
 *   <li>ResponseMatcher：用于判断收到的响应是否匹配当前请求</li>
 *   <li>CompletableFuture：用于在响应匹配时完成等待</li>
 * </ul>
 * 
 * <p>工作流程：</p>
 * <pre>
 * 1. 创建 PendingRequest，注册 matcher
 * 2. 发送命令，等待响应
 * 3. 收到响应后，调用 tryComplete(response)
 *    - 如果 matcher.matches(response) 返回 true，则完成 Future
 *    - 否则返回 false，表示响应不匹配
 * 4. 调用方通过 future.get() 等待响应完成
 * </pre>
 * 
 * @see ResponseMatcher
 * @see RequestManager
 */
final class PendingRequest {
    
    private final ResponseMatcher matcher;
    private final CompletableFuture<Response> future = new CompletableFuture<>();

    /**
     * 构造函数。
     *
     * @param matcher 响应匹配器，不能为空
     * @throws NullPointerException 如果 matcher 为空
     */
    PendingRequest(ResponseMatcher matcher) {
        this.matcher = Objects.requireNonNull(matcher);
    }

    /**
     * 尝试完成请求。
     * 
     * 如果响应匹配，则完成 Future 并返回 true。
     * 如果响应不匹配，则返回 false。
     *
     * @param response 收到的响应
     * @return 如果响应匹配且成功完成 Future 返回 true，否则返回 false
     */
    boolean tryComplete(Response response) {
        if (matcher.matches(response)) {
            return future.complete(response);
        }
        return false;
    }

    /**
     * 获取关联的 Future。
     *
     * @return CompletableFuture<Response>
     */
    CompletableFuture<Response> future() {
        return future;
    }
}