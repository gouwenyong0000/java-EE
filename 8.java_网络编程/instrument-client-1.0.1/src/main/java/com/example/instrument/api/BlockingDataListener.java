package com.example.instrument.api;

import com.example.instrument.model.Response;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 阻塞式数据监听器，提供阻塞获取响应数据的能力。
 * 
 * 该类是 DataListener 的实现类，内部使用 BlockingQueue 来缓存无法匹配的响应数据。
 * 允许调用者以阻塞方式从队列中获取响应，适用于需要顺序处理所有响应数据的场景。
 * 
 * <p>使用示例：</p>
 * <pre>{@code
 * BlockingDataListener listener = client.blockingDataListener();
 * 
 * // 阻塞等待数据
 * Response response = listener.take();
 * System.out.println(response.text());
 * 
 * // 带超时的等待
 * Response response = listener.poll(Duration.ofSeconds(5));
 * if (response != null) {
 *     System.out.println(response.text());
 * }
 * }</pre>
 * 
 * @see DataListener
 * @see Response
 */
public final class BlockingDataListener implements DataListener {
    
    private final BlockingQueue<Response> queue;

    /**
     * 创建一个阻塞式数据监听器。
     *
     * @param queue 用于存储响应数据的阻塞队列，由 ResponseDispatcher 提供
     */
    public BlockingDataListener(BlockingQueue<Response> queue) { 
        this.queue = queue; 
    }

    /**
     * 接收响应数据并放入队列。
     * 
     * 实现 DataListener 接口，将响应数据 offer 到内部队列。
     * 如果队列已满，会根据配置的 OverflowPolicy 决定行为。
     *
     * @param response 收到的响应数据
     */
    @Override 
    public void onData(Response response) { 
        queue.offer(response); 
    }

    /**
     * 阻塞等待并获取下一个响应数据。
     * 
     * 如果队列为空，则一直阻塞直到有数据可用。
     *
     * @return 下一个响应数据
     * @throws InterruptedException 如果等待过程中被中断
     */
    public Response take() throws InterruptedException { 
        return queue.take(); 
    }

    /**
     * 阻塞等待指定时间获取响应数据。
     * 
     * 如果在指定超时时间内没有数据可用，返回 null。
     *
     * @param timeout 最大等待时间，必须为正数
     * @return 下一个响应数据，如果在超时时间内没有数据则返回 null
     * @throws InterruptedException 如果等待过程中被中断
     */
    public Response poll(Duration timeout) throws InterruptedException {
        return queue.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * 获取当前队列中的数据数量。
     *
     * @return 队列中等待处理的数据数量
     */
    public int size() { 
        return queue.size(); 
    }
}