package com.example.instrument.core;

import com.example.instrument.api.DataListener;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.metrics.ClientMetrics;
import com.example.instrument.model.Response;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 响应分发器，负责将收到的响应分发给请求或数据监听器。
 *
 * <p>分发逻辑：</p>
 * <ol>
 *   <li>收到响应后，首先尝试通过 RequestManager 分发给匹配的 pending 请求</li>
 *   <li>如果有匹配的 pending 请求，则响应被该请求消费，分发结束</li>
 *   <li>如果没有匹配的 pending 请求，则响应进入异步队列，触发数据监听器</li>
 * </ol>
 *
 * <p>异步队列处理：</p>
 * <p>当响应无法匹配任何 pending 请求时，会先放入异步队列。
 * 如果队列已满，则根据配置的 OverflowPolicy 决定行为：</p>
 * <ul>
 *   <li>DROP_OLDEST: 丢弃最老的响应，添加新响应</li>
 *   <li>DROP_NEWEST: 丢弃新响应，保留队列中的响应</li>
 *   <li>BLOCK: 阻塞直到队列有空位</li>
 *   <li>FAIL: 直接抛出异常</li>
 * </ul>
 *
 * @see RequestManager
 * @see DataListener
 * @see com.example.instrument.config.ClientConfig.OverflowPolicy
 */
final class ResponseDispatcher {
    
    private static final Logger log = LoggerFactory.getLogger(ResponseDispatcher.class);

    private final RequestManager requestManager;
    private final ClientConfig.OverflowPolicy overflowPolicy;
    private final BlockingQueue<Response> asyncQueue;
    private final CopyOnWriteArrayList<DataListener> listeners = new CopyOnWriteArrayList<>();
    private final ClientMetrics metrics;

    /**
     * 构造函数。
     *
     * @param requestManager 请求管理器
     * @param config         客户端配置
     * @param metrics        指标收集器
     */
    ResponseDispatcher(RequestManager requestManager, ClientConfig config, ClientMetrics metrics) {
        this.requestManager = Objects.requireNonNull(requestManager);
        this.overflowPolicy = config.overflowPolicy();
        this.asyncQueue = new ArrayBlockingQueue<>(config.asyncQueueCapacity());
        this.metrics = metrics;
    }

    /**
     * 分发响应。
     *
     * 优先尝试分发给 pending 请求，如果失败则放入异步队列。
     *
     * @param response 收到的响应
     */
    void dispatch(Response response) {
        if (requestManager.dispatch(response)) {
            metrics.receivedMatched();
            return;
        }

        metrics.receivedAsync();
        offer(response);

        for (DataListener listener : listeners) {
            try {
                listener.onData(response);
            } catch (Throwable t) {
                metrics.listenerErrors();
                log.warn("data listener error", t);
            }
        }
    }

    /**
     * 将响应放入异步队列。
     *
     * 根据配置的 OverflowPolicy 决定队列满时的行为。
     *
     * @param response 要放入队列的响应
     */
    private void offer(Response response) {
        switch (overflowPolicy) {
            case DROP_NEWEST -> {
                if (!asyncQueue.offer(response)) {
                    metrics.droppedAsync();
                }
            }
            case DROP_OLDEST -> {
                if (!asyncQueue.offer(response)) {
                    asyncQueue.poll();
                    if (!asyncQueue.offer(response)) {
                        metrics.droppedAsync();
                    } else {
                        log.debug("async queue full, dropped oldest");
                    }
                }
            }
            case BLOCK -> {
                try {
                    asyncQueue.put(response);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    metrics.droppedAsync();
                    log.debug("async queue put interrupted");
                }
            }
            case FAIL -> {
                if (!asyncQueue.offer(response)) {
                    log.warn("async queue full, failing");
                    throw new IllegalStateException("async response queue is full");
                }
            }
        }
    }

    /**
     * 添加数据监听器。
     *
     * @param listener 要添加的监听器
     * @throws NullPointerException 如果 listener 为空
     */
    void addListener(DataListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /**
     * 移除数据监听器。
     *
     * @param listener 要移除的监听器
     */
    void removeListener(DataListener listener) {
        listeners.remove(listener);
    }

    /**
     * 获取异步队列。
     *
     * 用于 BlockingDataListener 的实现。
     *
     * @return 异步响应队列
     */
    BlockingQueue<Response> queue() {
        return asyncQueue;
    }

    /**
     * 清空异步队列。
     */
    void clearQueue() {
        asyncQueue.clear();
    }
}