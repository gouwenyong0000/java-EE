package com.example.instrument.core;

import com.example.instrument.api.*;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.connection.*;
import com.example.instrument.exception.ConnectionException;
import com.example.instrument.exception.RequestTimeoutException;
import com.example.instrument.model.*;
import com.example.instrument.metrics.ClientMetrics;
import com.example.instrument.protocol.*;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * InstrumentClient 接口的主要实现类。
 *
 * <p>架构概述：</p>
 * <p>该类是客户端的核心实现，整合了连接管理、协议编解码和请求处理。
 * 主要组件如下：</p>
 * <ul>
 *   <li>TcpConnection：底层 TCP 连接</li>
 *   <li>ProtocolEncoder/ProtocolDecoder：协议编解码</li>
 *   <li>RequestManager：请求生命周期管理</li>
 *   <li>ResponseDispatcher：响应分发</li>
 *   <li>Receiver：数据接收线程</li>
 * </ul>
 *
 * <p>线程模型：</p>
 * <ul>
 *   <li>主线程：发起请求、连接管理</li>
 *   <li>Receiver 线程：持续从连接读取数据并解码</li>
 *   <li>请求执行器：单线程池，执行异步请求</li>
 *   <li>重连调度器：单线程池，处理重连延迟</li>
 * </ul>
 *
 * <p>关键设计：</p>
 * <ul>
 *   <li>双锁策略：requestLock 用于请求级同步，lifecycleLock 用于连接生命周期同步</li>
 *   <li>幂等重试：IDEMPOTENT 命令在连接断开时会自动重试</li>
 *   <li>优雅关闭：使用 volatile 标志位协调各线程退出</li>
 * </ul>
 *
 * @see InstrumentClient
 * @see TcpConnection
 * @see RequestManager
 * @see ResponseDispatcher
 */
public final class InstrumentClientImpl implements InstrumentClient {

    private static final Logger log = LoggerFactory.getLogger(InstrumentClientImpl.class);

    private final InetSocketAddress address;
    private final Protocol protocol;
    private final ClientConfig config;
    private final TcpConnection connection;
    private final ProtocolEncoder encoder;
    private final ProtocolDecoder decoder;
    private final RequestManager requestManager = new RequestManager();
    private final ClientMetrics metrics = new ClientMetrics();
    private final ResponseDispatcher dispatcher;
    private final ReconnectPolicy reconnectPolicy;

    private final ReentrantLock requestLock = new ReentrantLock(true);
    private final ReentrantLock lifecycleLock = new ReentrantLock(true);

    private final ExecutorService requestExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "instrument-request"));
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "instrument-reconnect"));

    private final CopyOnWriteArrayList<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();

    private volatile Receiver receiver;
    private volatile Thread receiverThread;
    private volatile boolean closed;
    private volatile boolean reconnectSuppressed = true;
    private volatile int reconnectAttempt;

    /**
     * 构造函数。
     *
     * @param address 服务器地址
     * @param protocol 通信协议
     * @param config 客户端配置
     */
    public InstrumentClientImpl(InetSocketAddress address, Protocol protocol, ClientConfig config) {
        this.address = Objects.requireNonNull(address);
        this.protocol = Objects.requireNonNull(protocol);
        this.config = Objects.requireNonNull(config);
        this.connection = new TcpConnection(address, config);
        this.encoder = protocol.newEncoder();
        this.decoder = protocol.newDecoder();
        this.dispatcher = new ResponseDispatcher(requestManager, config, metrics);
        this.reconnectPolicy = new ReconnectPolicy(config.reconnect());
    }

    /**
     * 连接到服务器。
     *
     * 如果已连接则不执行任何操作。
     * 连接成功后启动接收线程。
     */
    @Override
    public void connect() {
        lifecycleLock.lock();
        try {
            ensureNotClosed();
            reconnectSuppressed = false;
            if (connection.isConnected()) {
                log.debug("already connected to {}", address);
                return;
            }
            log.info("connecting to {}", address);
            connection.connect();
            decoder.reset();
            startReceiver();
            reconnectAttempt = 0;
            notifyConnected();
            log.info("connected to {} successfully", address);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 启动接收线程。
     */
    private void startReceiver() {
        Receiver old = receiver;
        if (old != null) {
            old.stop();
        }
        Receiver r = new Receiver(connection, decoder, dispatcher, config.receiveBufferSize(), this::onReceiverFailure);
        receiver = r;
        Thread t = new Thread(r, "instrument-receiver");
        t.setDaemon(true);
        receiverThread = t;
        t.start();
    }

    /**
     * 接收线程失败处理。
     */
    private void onReceiverFailure(Throwable cause) {
        if (closed) return;

        lifecycleLock.lock();
        try {
            if (!connection.isConnected()) {
                // 连接已断开
            }
            log.warn("receiver failed on {}: {}", address, cause.toString());
            connection.disconnect();
            requestManager.fail(new ConnectionException("connection lost: " + address, cause));
            notifyDisconnected(cause);

            if (!reconnectSuppressed) {
                scheduleReconnect(cause);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 调度重连。
     */
    private void scheduleReconnect(Throwable cause) {
        if (!reconnectPolicy.enabled() || reconnectScheduled.getAndSet(true)) {
            return;
        }
        reconnectAttempt = 0;
        scheduleNextReconnect(cause);
    }

    /**
     * 调度下一次重连。
     */
    private void scheduleNextReconnect(Throwable cause) {
        int attempt = ++reconnectAttempt;
        if (!reconnectPolicy.canAttempt(attempt)) {
            reconnectScheduled.set(false);
            log.error("reconnect to {} failed after {} attempts", address, attempt - 1, cause);
            notifyReconnectFailed(cause);
            return;
        }

        Duration delay = reconnectPolicy.delay(attempt);
        log.info("reconnecting to {} attempt={}/{} delay={}", address, attempt, reconnectPolicy.maxAttempts(), delay);
        notifyReconnecting(attempt, delay, cause);

        scheduler.schedule(() -> {
            if (closed || reconnectSuppressed) {
                reconnectScheduled.set(false);
                return;
            }
            try {
                connect();
                reconnectScheduled.set(false);
            } catch (Throwable e) {
                scheduleNextReconnect(e);
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 断开与服务器的连接。
     */
    @Override
    public void disconnect() {
        lifecycleLock.lock();
        try {
            if (closed) return;
            log.info("disconnecting from {}...", address);
            reconnectSuppressed = true;
            reconnectScheduled.set(false);
            Receiver r = receiver;
            if (r != null) r.stop();
            connection.disconnect();
            requestManager.fail(new ConnectionException("client disconnected"));
            notifyDisconnected(null);
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public boolean isConnected() {
        return connection.isConnected();
    }

    /**
     * 同步发送请求并等待响应。
     *
     * 对于 IDEMPOTENT 命令，如果连接断开会自动重连重试。
     */
    @Override
    public Response request(Command command, ResponseMatcher matcher, Duration timeout, CommandIdempotency idempotency) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(matcher);
        Objects.requireNonNull(timeout);
        Objects.requireNonNull(idempotency);

        Request request = new Request(command, matcher, timeout, idempotency);
        int attempts = 0;

        while (true) {
            try {
                return executeOnce(request);
            } catch (ConnectionException e) {
                if (request.idempotency() != CommandIdempotency.IDEMPOTENT || attempts >= reconnectPolicy.maxAttempts()) {
                    throw e;
                }
                attempts++;
                reconnectForRetry();
            }
        }
    }

    /**
     * 执行单次请求。
     */
    private Response executeOnce(Request request) {
        requestLock.lock();
        PendingRequest pending = null;
        try {
            // 协议没有请求 ID，同一连接只能同时等待一个响应。
            // requestLock 覆盖“检查、注册、写入”这三个步骤，避免并发占用响应通道。
            if (requestManager.hasPending()) {
                throw new IllegalStateException("another request is pending");
            }
            if (!isConnected()) {
                if (request.idempotency() != CommandIdempotency.IDEMPOTENT) {
                    throw new ConnectionException("not connected (non-idempotent command)");
                }
                connect();
            }

            // 先注册 pending，再写入命令，避免快速响应在 write 返回前被误判为异步数据。
            pending = requestManager.register(request.matcher());

            try {
                connection.write(encoder.encode(request.command()));
                metrics.sent(request.command().length());
            } catch (Exception e) {
                requestManager.remove(pending);
                onReceiverFailure(e);
                throw new ConnectionException("write failed: " + address, e);
            }

            try {
                // future 由 Receiver -> Decoder -> Dispatcher -> RequestManager 这条链路完成；
                // 当前线程只负责在调用方指定的期限内等待结果。
                return pending.future().get(request.timeout().toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                requestManager.remove(pending);
                throw new RequestTimeoutException("request timeout after " + request.timeout() + ": " + request.command());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                requestManager.remove(pending);
                throw new ConnectionException("request interrupted", e);
            } catch (ExecutionException e) {
                requestManager.remove(pending);
                Throwable c = e.getCause();
                if (c instanceof ConnectionException ce) {
                    throw ce;
                }
                throw new ConnectionException("request failed", c);
            }
        } finally {
            requestLock.unlock();
        }
    }

    /**
     * 为重试执行重连。
     */
    private void reconnectForRetry() {
        lifecycleLock.lock();
        try {
            if (closed) {
                throw new ConnectionException("client is closed");
            }
            reconnectSuppressed = false;
            connection.disconnect();
            decoder.reset();
            connection.connect();
            startReceiver();
            notifyConnected();
        } catch (ConnectionException e) {
            throw e;
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public CompletableFuture<Response> requestAsync(Command command, ResponseMatcher matcher, Duration timeout, CommandIdempotency idempotency) {
        return CompletableFuture.supplyAsync(() -> request(command, matcher, timeout, idempotency), requestExecutor);
    }

    @Override
    public void send(Command command) {
        requestLock.lock();
        try {
            if (requestManager.hasPending()) {
                throw new IllegalStateException("cannot send while a request is pending");
            }
            if (!isConnected()) {
                connect();
            }
            try {
                connection.write(encoder.encode(command));
                metrics.sent(command.length());
            } catch (Exception e) {
                onReceiverFailure(e);
                throw new ConnectionException("write failed: " + address, e);
            }
        } finally {
            requestLock.unlock();
        }
    }

    @Override
    public void addDataListener(DataListener listener) {
        dispatcher.addListener(listener);
    }

    @Override
    public void removeDataListener(DataListener listener) {
        dispatcher.removeListener(listener);
    }

    @Override
    public void addConnectionListener(ConnectionListener listener) {
        connectionListeners.add(Objects.requireNonNull(listener));
    }

    @Override
    public void removeConnectionListener(ConnectionListener listener) {
        connectionListeners.remove(listener);
    }

    @Override
    public BlockingDataListener blockingDataListener() {
        return new BlockingDataListener(dispatcher.queue());
    }

    /**
     * 获取客户端指标快照。
     *
     * @return 指标快照
     */
    public ClientMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }

    private void ensureNotClosed() {
        if (closed) {
            throw new ConnectionException("client is closed");
        }
    }

    private void notifyConnected() {
        for (var l : connectionListeners) {
            try { l.onConnected(); } catch (Throwable ignored) {}
        }
    }

    private void notifyDisconnected(Throwable cause) {
        for (var l : connectionListeners) {
            try { l.onDisconnected(cause); } catch (Throwable ignored) {}
        }
    }

    private void notifyReconnecting(int attempt, Duration delay, Throwable cause) {
        for (var l : connectionListeners) {
            try { l.onReconnecting(attempt, delay, cause); } catch (Throwable ignored) {}
        }
    }

    private void notifyReconnectFailed(Throwable cause) {
        for (var l : connectionListeners) {
            try { l.onReconnectFailed(cause); } catch (Throwable ignored) {}
        }
    }

    /**
     * 关闭客户端并释放资源。
     */
    @Override
    public void close() {
        lifecycleLock.lock();
        try {
            if (closed) return;
            closed = true;
            log.info("closing client for {}...", address);
            reconnectSuppressed = true;
            reconnectScheduled.set(false);
            Receiver r = receiver;
            if (r != null) r.stop();
            connection.close();
            decoder.reset();
            requestManager.fail(new ConnectionException("client closed"));
        } finally {
            lifecycleLock.unlock();
        }
        requestExecutor.shutdownNow();
        scheduler.shutdownNow();
        log.info("client for {} closed", address);
    }
}