package com.example.instrument.core;

import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.model.Response;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 请求管理器，负责管理当前等待中的请求。
 * 
 * <p>核心职责：</p>
 * <ul>
 *   <li>维护一个"当前 pending 请求"的引用</li>
 *   <li>提供线程安全的注册、注销、分发、失败操作</li>
 *   <li>保证同时只有一个请求在等待响应</li>
 * </ul>
 * 
 * <p>设计考虑：使用 AtomicReference 实现单个 pending 请求的线程安全管理。
 * 同一时刻只能有一个请求在等待，这是因为响应没有请求 ID，只能按顺序匹配。</p>
 * 
 * @see PendingRequest
 * @see ResponseMatcher
 * @see com.example.instrument.core.InstrumentClientImpl
 */
final class RequestManager {
    
    private final AtomicReference<PendingRequest> pending = new AtomicReference<>();

    /**
     * 注册一个新的待处理请求。
     * 
     * 使用 CAS 操作确保只有一个请求被注册。
     * 如果已有请求在等待，则抛出 IllegalStateException。
     *
     * @param matcher 响应匹配器
     * @return 新创建的 PendingRequest
     * @throws IllegalStateException 如果已有请求在等待
     */
    PendingRequest register(ResponseMatcher matcher) {
        PendingRequest request = new PendingRequest(matcher);
        if (!pending.compareAndSet(null, request)) {
            throw new IllegalStateException("another request is already pending");
        }
        return request;
    }

    /**
     * 分发响应给待处理请求。
     * 
     * 如果没有待处理请求，返回 false。
     * 如果有待处理请求，尝试用 matcher 匹配响应，匹配成功则完成请求并返回 true。
     *
     * @param response 收到的响应
     * @return 如果成功分发给某个请求返回 true，否则返回 false
     */
    boolean dispatch(Response response) {
        PendingRequest request = pending.get();
        if (request == null) {
            return false;
        }
        // matcher 可能拒绝当前响应。此时保留 pending，继续等待后续响应。
        if (!request.tryComplete(response)) {
            return false;
        }
        // 使用 CAS，防止超时线程同时 remove 时误删后来注册的请求。
        pending.compareAndSet(request, null);
        return true;
    }

    /**
     * 移除指定的待处理请求。
     * 
     * 通常在超时或中断时调用。
     *
     * @param request 要移除的请求
     */
    void remove(PendingRequest request) {
        pending.compareAndSet(request, null);
    }

    /**
     * 使所有待处理请求失败。
     * 
     * 当连接断开或客户端关闭时调用，所有等待中的请求都会收到异常。
     *
     * @param cause 失败原因
     */
    void fail(Throwable cause) {
        PendingRequest r = pending.getAndSet(null);
        if (r != null) {
            r.future().completeExceptionally(cause);
        }
    }

    /**
     * 检查是否有待处理的请求。
     *
     * @return 如果有待处理请求返回 true
     */
    boolean hasPending() {
        return pending.get() != null;
    }
}