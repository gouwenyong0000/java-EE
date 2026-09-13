package com.example.instrument.metrics;

import java.util.concurrent.atomic.LongAdder;

/**
 * 客户端指标收集器，用于统计客户端的运行状态。
 *
 * <p>收集的指标包括：</p>
 * <ul>
 *   <li>发送统计：sentFrames（发送帧数）、sentBytes（发送字节数）</li>
 *   <li>接收统计：receivedFrames（接收帧数）、matchedFrames（匹配成功帧数）、asyncFrames（异步帧数）</li>
 *   <li>错误统计：droppedAsync（丢弃的异步帧数）、listenerErrors（监听器错误数）</li>
 *   <li>连接统计：reconnects（重连次数）、timeouts（超时次数）</li>
 * </ul>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * ClientMetrics metrics = new ClientMetrics();
 *
 * // 发送数据时
 * metrics.sent(command.length());
 *
 * // 收到匹配响应时
 * metrics.receivedMatched();
 *
 * // 收到不匹配响应时
 * metrics.receivedAsync();
 *
 * // 获取统计快照
 * ClientMetrics.Snapshot snapshot = metrics.snapshot();
 * System.out.println("Sent: " + snapshot.sentFrames() + " frames, " + snapshot.sentBytes() + " bytes");
 * }</pre>
 *
 * @see ClientMetrics.Snapshot
 */
public final class ClientMetrics {

    private final LongAdder sentFrames = new LongAdder();
    private final LongAdder sentBytes = new LongAdder();
    private final LongAdder receivedFrames = new LongAdder();
    private final LongAdder matchedFrames = new LongAdder();
    private final LongAdder asyncFrames = new LongAdder();
    private final LongAdder droppedAsync = new LongAdder();
    private final LongAdder listenerErrors = new LongAdder();
    private final LongAdder reconnects = new LongAdder();
    private final LongAdder timeouts = new LongAdder();

    /**
     * 记录发送的数据。
     *
     * @param bytes 发送的字节数
     */
    public void sent(long bytes) {
        sentFrames.increment();
        sentBytes.add(bytes);
    }

    /**
     * 记录收到匹配的响应。
     */
    public void receivedMatched() {
        receivedFrames.increment();
        matchedFrames.increment();
    }

    /**
     * 记录收到异步（不匹配）的响应。
     */
    public void receivedAsync() {
        receivedFrames.increment();
        asyncFrames.increment();
    }

    /**
     * 记录丢弃的异步响应。
     */
    public void droppedAsync() {
        droppedAsync.increment();
    }

    /**
     * 记录监听器错误。
     */
    public void listenerErrors() {
        listenerErrors.increment();
    }

    /**
     * 记录重连事件。
     */
    public void reconnect() {
        reconnects.increment();
    }

    /**
     * 记录超时事件。
     */
    public void timeout() {
        timeouts.increment();
    }

    /**
     * 获取当前指标快照。
     *
     * @return 包含所有指标的快照
     */
    public Snapshot snapshot() {
        return new Snapshot(
            sentFrames.sum(),
            sentBytes.sum(),
            receivedFrames.sum(),
            matchedFrames.sum(),
            asyncFrames.sum(),
            droppedAsync.sum(),
            listenerErrors.sum(),
            reconnects.sum(),
            timeouts.sum()
        );
    }

    /**
     * 指标快照，记录某一时刻的所有指标值。
     */
    public record Snapshot(
        long sentFrames,
        long sentBytes,
        long receivedFrames,
        long matchedFrames,
        long asyncFrames,
        long droppedAsync,
        long listenerErrors,
        long reconnects,
        long timeouts
    ) {}
}