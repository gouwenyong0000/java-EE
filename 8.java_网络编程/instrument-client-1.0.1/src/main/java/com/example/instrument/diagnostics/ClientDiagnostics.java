package com.example.instrument.diagnostics;

import com.example.instrument.network.ConnectionState;
import com.example.instrument.metrics.ClientMetrics;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端诊断信息收集器，提供运行时状态查询能力。
 *
 * <p>诊断信息包括：</p>
 * <ul>
 *   <li>连接状态历史</li>
 *   <li>最近请求记录</li>
 *   <li>错误统计</li>
 *   <li>线程状态</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * ClientDiagnostics diagnostics = new ClientDiagnostics(address);
 *
 * // 记录事件
 * diagnostics.recordConnectionStateChange(ConnectionState.CONNECTED);
 * diagnostics.recordRequestSent("IDN", System.nanoTime());
 * diagnostics.recordRequestCompleted("IDN", true, Duration.ofMillis(50));
 *
 * // 获取诊断报告
 * DiagnosticReport report = diagnostics.getReport();
 * System.out.println(report);
 * }</pre>
 */
public final class ClientDiagnostics {

    private final InetSocketAddress address;
    private final List<StateChangeEvent> stateHistory = new CopyOnWriteArrayList<>();
    private final Map<String, RequestRecord> recentRequests = new ConcurrentHashMap<>();
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final AtomicLong lastActivityTimestamp = new AtomicLong(System.nanoTime());

    private volatile ConnectionState currentState = ConnectionState.NEW;
    private volatile Instant connectedSince;
    private volatile int reconnectCount;
    private volatile String lastError;

    /**
     * 创建诊断收集器。
     *
     * @param address 服务器地址
     */
    public ClientDiagnostics(InetSocketAddress address) {
        this.address = Objects.requireNonNull(address);
    }

    /**
     * 记录连接状态变化。
     *
     * @param newState 新的连接状态
     */
    public void recordConnectionStateChange(ConnectionState newState) {
        ConnectionState oldState = this.currentState;
        this.currentState = newState;
        if (newState == ConnectionState.CONNECTED) {
            this.connectedSince = Instant.now();
        }
        stateHistory.add(new StateChangeEvent(oldState, newState, System.nanoTime()));
        touch();
    }

    /**
     * 记录请求发送。
     *
     * @param commandText 命令文本摘要
     * @param timestampNanos 发送时间戳（纳秒）
     */
    public void recordRequestSent(String commandText, long timestampNanos) {
        recentRequests.put(commandText, new RequestRecord(commandText, timestampNanos));
        totalRequests.incrementAndGet();
        touch();
    }

    /**
     * 记录请求完成。
     *
     * @param commandText 命令文本摘要
     * @param success 是否成功
     * @param latency 请求延迟
     */
    public void recordRequestCompleted(String commandText, boolean success, Duration latency) {
        RequestRecord record = recentRequests.get(commandText);
        if (record != null) {
            record.complete(success, latency);
        }
        if (!success) {
            totalErrors.incrementAndGet();
        }
        touch();
    }

    /**
     * 记录错误。
     *
     * @param error 错误信息
     */
    public void recordError(String error) {
        this.lastError = error;
        totalErrors.incrementAndGet();
        touch();
    }

    /**
     * 记录重连。
     */
    public void recordReconnect() {
        this.reconnectCount++;
        touch();
    }

    /**
     * 合并指标快照。
     *
     * @param snapshot 指标快照
     */
    public void mergeMetrics(ClientMetrics.Snapshot snapshot) {
        touch();
    }

    /**
     * 获取诊断报告。
     *
     * @return 诊断报告
     */
    public DiagnosticReport getReport() {
        return new DiagnosticReport(
            address,
            currentState,
            connectedSince,
            reconnectCount,
            totalRequests.get(),
            totalErrors.get(),
            lastError,
            stateHistory.isEmpty() ? null : stateHistory.get(stateHistory.size() - 1),
            List.copyOf(recentRequests.values())
        );
    }

    /**
     * 清除历史数据。
     */
    public void clear() {
        stateHistory.clear();
        recentRequests.clear();
        totalRequests.set(0);
        totalErrors.set(0);
        lastError = null;
        reconnectCount = 0;
    }

    private void touch() {
        lastActivityTimestamp.set(System.nanoTime());
    }

    /**
     * 状态变化事件。
     */
    public record StateChangeEvent(
        ConnectionState from,
        ConnectionState to,
        long timestampNanos
    ) {}

    /**
     * 请求记录。
     */
    public static final class RequestRecord {
        private final String commandText;
        private final long sentAtNanos;
        private volatile boolean completed;
        private volatile boolean success;
        private volatile Duration latency;

        RequestRecord(String commandText, long sentAtNanos) {
            this.commandText = commandText;
            this.sentAtNanos = sentAtNanos;
        }

        void complete(boolean success, Duration latency) {
            this.completed = true;
            this.success = success;
            this.latency = latency;
        }

        public String commandText() { return commandText; }
        public long sentAtNanos() { return sentAtNanos; }
        public boolean isCompleted() { return completed; }
        public boolean isSuccess() { return success; }
        public Duration latency() { return latency; }

        @Override
        public String toString() {
            return "RequestRecord{cmd='%s', completed=%s, success=%s, latency=%s}"
                .formatted(commandText, completed, success, latency);
        }
    }

    /**
     * 诊断报告。
     */
    public record DiagnosticReport(
        InetSocketAddress address,
        ConnectionState currentState,
        Instant connectedSince,
        int reconnectCount,
        long totalRequests,
        long totalErrors,
        String lastError,
        StateChangeEvent lastStateChange,
        List<RequestRecord> recentRequests
    ) {
        @Override
        public String toString() {
            return "DiagnosticReport{" +
                "address=" + address +
                ", state=" + currentState +
                ", connectedSince=" + connectedSince +
                ", reconnects=" + reconnectCount +
                ", requests=" + totalRequests +
                ", errors=" + totalErrors +
                ", lastError='" + lastError + '\'' +
                '}';
        }
    }
}