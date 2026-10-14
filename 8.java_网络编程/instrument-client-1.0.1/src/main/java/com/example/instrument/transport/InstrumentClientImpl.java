package com.example.instrument.transport;

import com.example.instrument.api.*;
import com.example.instrument.core.config.ClientConfig;
import com.example.instrument.core.diagnostics.ClientDiagnostics;
import com.example.instrument.core.diagnostics.ClientDiagnostics.DiagnosticReport;
import com.example.instrument.core.exception.ConnectionException;
import com.example.instrument.core.exception.RequestTimeoutException;
import com.example.instrument.core.metrics.ClientMetrics;
import com.example.instrument.core.model.*;
import com.example.instrument.protocol.*;
import com.example.instrument.transport.reconnect.ReconnectPolicy;
import com.example.instrument.transport.tcp.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * InstrumentClient 接口的主要实现类。
 *
 * <h2>一、架构概述</h2>
 * <p>该类是客户端的核心实现，整合了连接管理、协议编解码和请求处理。</p>
 * <p>核心组件：</p>
 * <ul>
 *   <li>TcpConnection：底层 TCP 连接管理</li>
 *   <li>ProtocolEncoder/ProtocolDecoder：协议消息的编码与解码</li>
 *   <li>RequestManager： Pending 请求的生命周期管理</li>
 *   <li>ResponseDispatcher：将接收到的响应分发给对应的 Pending 请求</li>
 *   <li>Receiver：独立线程，持续从 TCP 连接读取并解码数据</li>
 *   <li>ReconnectPolicy：重连策略（指数退避+抖动）</li>
 * </ul>
 *
 * <h2>二、线程模型</h2>
 * <ul>
 *   <li><b>主线程</b>：发起 connect/disconnect/request 等操作</li>
 *   <li><b>Receiver 线程</b>：Daemon 线程，持续从 TCP 流读取数据并通过 Dispatcher 分发</li>
 *   <li><b>requestExecutor</b>：单线程池，执行 requestAsync() 的异步请求</li>
 *   <li><b>scheduler</b>：单线程调度器，执行重连延迟任务</li>
 * </ul>
 *
 * <h2>三、关键设计</h2>
 * <ul>
 *   <li><b>双锁策略</b>：
 *     <ul>
 *       <li>requestLock 保护"检查 pending → 注册 → 写入命令"的原子操作，防止并发请求抢占响应通道</li>
 *       <li>lifecycleLock 保护连接生命周期操作，防止多线程同时操作 Socket 导致状态不一致</li>
 *     </ul>
 *   </li>
 *   <li><b>幂等重试</b>：IDEMPOTENT 命令在连接异常时自动重连重试</li>
 *   <li><b>优雅关闭</b>：volatile 标志位协调各线程有序退出</li>
 *   <li><b>先注册后写入</b>：避免快速响在 write 返回前被误判为异步数据</li>
 * </ul>
 *
 * <h2>四、代码核心步骤</h2>
 * <p><b>连接流程 (connect)</b>：检查关闭状态 → 解除重连抑制 → 建立 TCP → 重置解码器 → 启动接收线程 → 重置计数器</p>
 * <p><b>请求流程 (executeOnce)</b>：检查 pending → 注册请求 → 写入命令 → 等待响应 → 超时/异常处理</p>
 * <p><b>重连流程 (scheduleNextReconnect)</b>：检查上限 → 计算退避延迟 → 延迟调度 → connect 失败则递归重试</p>
 * <p><b>关闭流程 (close)</b>：CAS 设置 closed → 停止接收线程 → 关闭连接 → 失败 pending 请求 → 关闭线程池</p>
 *
 * @see InstrumentClient
 * @see TcpConnection
 * @see RequestManager
 * @see ResponseDispatcher
 */
public final class InstrumentClientImpl implements InstrumentClient {

    private static final Logger log = LoggerFactory.getLogger(InstrumentClientImpl.class);

    /**
     * ========== 核心组件（架构概述） ==========
     */
    private final InetSocketAddress address;
    private final Protocol protocol;
    private final ClientConfig config;
    private final TcpConnection connection;
    private final ProtocolEncoder encoder;
    private final ProtocolDecoder decoder;
    private final RequestManager requestManager = new RequestManager();
    private final ClientMetrics metrics = new ClientMetrics();
    private final ClientDiagnostics diagnostics;
    private final ResponseDispatcher dispatcher;
    private final ReconnectPolicy reconnectPolicy;

    /**
     * ========== 双锁策略（关键设计） ==========
     * requestLock：保护"检查 pending → 注册 → 写入命令"的原子操作，防止并发请求抢占响应通道
     * lifecycleLock：保护连接生命周期操作，防止多线程同时操作 Socket 导致状态不一致
     */
    private final ReentrantLock requestLock = new ReentrantLock(true);
    private final ReentrantLock lifecycleLock = new ReentrantLock(true);

    /**
     * ========== 线程池（线程模型） ==========
     * requestExecutor：单线程池，执行 requestAsync() 的异步请求，避免阻塞调用方线程
     * scheduler：单线程调度器，执行重连延迟任务，确保重连按退避策略有序进行
     */
    private final ExecutorService requestExecutor;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "instrument-reconnect");
        t.setDaemon(true);
        return t;
    });

    /**
     * ========== 事件监听器（架构概述） ==========
     */
    private final CopyOnWriteArrayList<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ClientInterceptor> interceptors = new CopyOnWriteArrayList<>();

    /**
     * ========== 重连调度标志（关键设计） ==========
     * CAS 原子操作确保同一时刻只有一个重连任务在调度中，防止重复调度
     */
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();

    /**
     * ========== 接收线程（线程模型） ==========
     * receiver：当前活跃的接收器实例，disconnect/reconnect 时替换
     * receiverThread：接收线程引用，用于调试和状态诊断
     */
    private volatile Receiver receiver;
    private volatile Thread receiverThread;

    /**
     * ========== 生命周期标志（关键设计） ==========
     */
    /** closed：终态标志，CAS 保证只关闭一次*/
    private volatile boolean closed;

    /**
     * reconnectSuppressed：控制是否允许自动重连
     * <p>控制场景：</p>
     * <ul>
     *   <li>true（抑制）：用户主动调用 disconnect()、close() 时设置，阻止自动重连</li>
     *   <li>false（允许）：connect()、reconnectForRetry() 时设置，允许网络异常后自动重连</li>
     * </ul>
     * <p>初始值 true：未连接时不进行重连，只有显式调用 connect() 后才允许自动重连</p>
     */
    private volatile boolean reconnectSuppressed = true;

    /** reconnectAttempt：当前重连尝试次数，成功连接后重置为 0*/
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
        this.requestExecutor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(config.asyncRequestQueueCapacity()),
                        r -> {
                            Thread t = new Thread(r, "instrument-request");
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        this.connection = new TcpConnection(address, config);
        this.encoder = protocol.newEncoder();
        this.decoder = protocol.newDecoder();
        this.diagnostics = new ClientDiagnostics(address);
        this.dispatcher = new ResponseDispatcher(requestManager, config, metrics);
        this.reconnectPolicy = new ReconnectPolicy(config.reconnect());
    }

    /**
     * 连接到服务器。
     *
     * <h2>功能概述</h2>
     * <p>建立与服务器的 TCP 连接，启动数据接收线程，使客户端进入可通信状态。</p>
     *
     * <h2>线程模型</h2>
     * <p>在主线程执行，持有 lifecycleLock 完成连接过程。</p>
     *
     * <h2>关键设计</h2>
     * <ul>
     *   <li><b>幂等性</b>：已连接时直接返回，不重复建立连接</li>
     *   <li><b>重连控制</b>：设置 reconnectSuppressed = false，解除重连抑制</li>
     *   <li><b>状态重置</b>：decoder.reset() 在 startReceiver() 之前调用，避免用旧状态解码新数据</li>
     * </ul>
     *
     * <h2>代码核心步骤</h2>
     * <p>检查 closed → 解除重连抑制 → 幂等检查 → 建立 TCP → 重置解码器 → 启动接收线程 → 重置计数器</p>
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

            // 建立 TCP 连接
            connection.connect();

            // 重置解码器状态，清除旧连接的残留数据
            decoder.reset();
            startReceiver();// 启动接收线程，开始读取 TCP 流
            reconnectAttempt = 0;// 成功连接后重置重连尝试次数

            diagnostics.recordConnectionStateChange(ConnectionState.CONNECTED);
            notifyConnected();
            log.info("connected to {} successfully", address);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 启动接收线程。
     *
     * <p>关键步骤：</p>
     * <ol>
     *   <li>停止旧的 receiver（如果存在），防止多个接收线程同时运行</li>
     *   <li>创建新的 Receiver，绑定到当前连接和解码器</li>
     *   <li>以 Daemon 线程启动，确保 JVM 退出时不会阻塞</li>
     * </ol>
     *
     * <p>注意：此方法不在锁内调用，因为 startReceiver() 只由 connect() 和
     * reconnectForRetry() 调用，这两个方法已经持有 lifecycleLock。</p>
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
     *
     * <p>触发时机：Receiver 在读取过程中遇到 IOException 或连接断开。</p>
     * <p>处理流程：</p>
     * <ol>
     *   <li>快速退出：如果已经 closed，直接返回</li>
     *   <li>断开底层连接（确保状态一致）</li>
     *   <li>失败所有 pending 请求（CompletableFuture.completeExceptionally）</li>
     *   <li>通知监听器</li>
     *   <li>如果未被抑制，调度重连</li>
     * </ol>
     *
     * <p>注意：此方法由 Receiver 线程回调，需要获取 lifecycleLock 来
     * 保护连接状态，防止与主线程的 connect()/disconnect() 并发。</p>
     */
    private void onReceiverFailure(Throwable cause) {
        if (closed) return;

        lifecycleLock.lock();
        try {
            if (!connection.isConnected()) {
                // 连接已断开
            }
            log.warn("receiver failed on {}: {}", address, cause.toString());
            diagnostics.recordError("receiver: " + cause.toString());
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
     *
     * <p>入口检查：</p>
     * <ul>
     *   <li>重连功能未启用 → 直接返回</li>
     *   <li>已有重连任务在调度中（CAS getAndSet） → 直接返回，防止重复调度</li>
     * </ul>
     *
     * <p>CAS 保证：reconnectScheduled.getAndSet(true) 是原子操作，
     * 即使 receiver 线程和主线程同时调用此方法，也只有一个能成功设置标志。</p>
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
     *
     * <p>流程：</p>
     * <ol>
     *   <li>递增重连计数器</li>
     *   <li>检查是否超过最大尝试次数 → 超过则通知失败并退出</li>
     *   <li>计算退避延迟（指数退避 + 抖动）</li>
     *   <li>通知监听器（onReconnecting 回调）</li>
     *   <li>在 scheduler 中延迟执行 connect()</li>
     * </ol>
     *
     * <p>递归调度：如果 connect() 失败，lambda 中会递归调用 scheduleNextReconnect()，
     * 形成"尝试 → 失败 → 延迟 → 再尝试"的循环，直到成功或达到上限。</p>
     *
     * <p>退出条件：</p>
     * <ul>
     *   <li>client 已 closed → 停止重连</li>
     *   <li>reconnectSuppressed = true（用户主动 disconnect） → 停止重连</li>
     *   <li>达到最大尝试次数 → 停止重连</li>
     *   <li>connect() 成功 → 停止重连（reconnectScheduled 重置为 false）</li>
     * </ul>
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
        diagnostics.recordReconnect();
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
     *
     * <p>与网络异常断开的区别：</p>
     * <ul>
     *   <li>disconnect() 是用户主动操作，设置 reconnectSuppressed = true 阻止自动重连</li>
     *   <li>网络异常断开时 reconnectSuppressed 保持 false，触发自动重连</li>
     * </ul>
     *
     * <p>清理顺序：</p>
     * <ol>
     *   <li>设置 reconnectSuppressed = true（阻止重连）</li>
     *   <li>取消已调度的重连任务</li>
     *   <li>停止接收线程</li>
     *   <li>断开 TCP 连接</li>
     *   <li>失败所有 pending 请求</li>
     *   <li>通知监听器</li>
     * </ol>
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
            diagnostics.recordConnectionStateChange(ConnectionState.DIS_CONNECTED);
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
     * <p>幂等重试逻辑：</p>
     * <ul>
     *   <li>IDEMPOTENT：连接异常时自动重连重试（循环执行 executeOnce）</li>
     *   <li>NON_IDEMPOTENT：任何异常直接抛出，不重试（防止重复执行副作用命令）</li>
     *   <li>UNKNOWN：默认不重试</li>
     * </ul>
     *
     * <p>重试上限：最多重试 reconnectPolicy.maxAttempts() 次，
     * 超过上限后即使命令是 IDEMPOTENT 也会抛出异常。</p>
     *
     * <p>注意：每次重试都会调用 reconnectForRetry()，这会断开旧连接、
     * 建立新连接、重启接收线程。重试的延迟由重连策略的退避算法决定。</p>
     */
    @Override
    public Response request(Command command, ResponseMatcher matcher, Duration timeout, CommandIdempotency idempotency) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(matcher, "matcher");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(idempotency, "idempotency");

        notifyInterceptorsBeforeRequest(command, idempotency);
        long startNanos = System.nanoTime();
        String cmdSummary = command.toString();
        diagnostics.recordRequestSent(cmdSummary, startNanos);

        RequestContext requestContext = new RequestContext(command, matcher, timeout, idempotency);
        int attempts = 0;

        while (true) {
            try {
                Response response = executeOnce(requestContext);
                Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
                diagnostics.recordRequestCompleted(cmdSummary, true, latency);
                notifyInterceptorsAfterResponse(command, response, latency);
                return response;
            } catch (Exception e) {
                // 只对明确的连接故障执行自动重试；参数错误、并发占用、响应超时等不应被误当作网络故障。
                // 响应超时意味着设备可能已经执行命令，是否重试必须由调用方显式决定。
                if (!(e instanceof ConnectionException)
                    || requestContext.idempotency() != CommandIdempotency.IDEMPOTENT
                    || attempts >= reconnectPolicy.maxAttempts()) {
                    diagnostics.recordRequestCompleted(cmdSummary, false, Duration.ZERO);
                    diagnostics.recordError(e.getClass().getSimpleName() + ": " + e.getMessage());
                    notifyInterceptorsAfterFailure(command, e);
                    throw e;
                }
                attempts++;
                reconnectForRetry();
            }
        }
    }

    /**
     * 执行单次请求。
     *
     * <p>requestLock 保护的原子操作序列：</p>
     * <ol>
     *   <li>检查是否有 pending 请求 → 有则抛出 IllegalStateException</li>
     *   <li>检查连接状态 → 未连接则尝试连接（仅 IDEMPOTENT 命令）</li>
     *   <li>注册 pending 请求 → 将 matcher 注册到 RequestManager</li>
     *   <li>写入命令 → 通过 encoder 编码后写入 TCP 流</li>
     *   <li>等待响应 → 在调用方指定的超时时间内等待 future 完成</li>
     * </ol>
     *
     * <p>为什么"先注册再写入"？</p>
     * 如果先写入命令再注册 pending，在快速网络环境下，服务器可能在 write() 返回前
     * 就返回了响应。此时 Receiver 线程收到响应后发现没有 pending 请求，
     * 会将其误判为异步数据（async data）而丢失。
     *
     * <p>异常处理：</p>
     * <ul>
     *   <li>TimeoutException → 移除 pending，抛出 RequestTimeoutException</li>
     *   <li>InterruptedException → 恢复中断标志，移除 pending</li>
     *   <li>ExecutionException → 解包 cause，如果是 ConnectionException 则直接抛出</li>
     *   <li>写入异常 → 移除 pending，触发 onReceiverFailure，抛出 ConnectionException</li>
     * </ul>
     */
    private Response executeOnce(RequestContext requestContext) {
        requestLock.lock();
        PendingRequest pending = null;
        try {
            // 协议没有请求 ID，同一连接只能同时等待一个响应。
            // requestLock 覆盖“检查、注册、写入”这三个步骤，避免并发占用响应通道。
            if (requestManager.hasPending()) {
                throw new IllegalStateException("another requestContext is pending");
            }
            if (!isConnected()) {
                if (requestContext.idempotency() != CommandIdempotency.IDEMPOTENT) {
                    throw new ConnectionException("not connected (non-idempotent command)");
                }
                connect();
            }

            // 先注册 pending，再写入命令，避免快速响应在 write 返回前被误判为异步数据。
            pending = requestManager.register(requestContext.matcher());

            try {
                connection.write(encoder.encode(requestContext.command()));
                metrics.sent(requestContext.command().length());
            } catch (Exception e) {
                requestManager.remove(pending);
                onReceiverFailure(e);
                throw new ConnectionException("write failed: " + address, e);
            }

            try {
                // future 由 Receiver -> Decoder -> Dispatcher -> RequestManager 这条链路完成；
                // 当前线程只负责在调用方指定的期限内等待结果。
                return pending.future().get(requestContext.timeout().toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                requestManager.remove(pending);
                throw new RequestTimeoutException("requestContext timeout after " + requestContext.timeout() + ": " + requestContext.command());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                requestManager.remove(pending);
                throw new ConnectionException("requestContext interrupted", e);
            } catch (ExecutionException e) {
                requestManager.remove(pending);
                Throwable c = e.getCause();
                if (c instanceof ConnectionException ce) {
                    throw ce;
                }
                throw new ConnectionException("requestContext failed", c);
            }
        } finally {
            requestLock.unlock();
        }
    }

    /**
     * 为重试执行重连。
     *
     * <p>与正常 connect() 的区别：</p>
     * <ul>
     *   <li>reconnectForRetry() 会先 disconnect() 旧连接，再 connect() 新连接</li>
     *   <li>connect() 只在未连接时建立连接，不会主动断开</li>
     * </ul>
     *
     * <p>重连步骤：</p>
     * <ol>
     *   <li>检查是否已关闭</li>
     *   <li>解除重连抑制（允许后续自动重连）</li>
     *   <li>断开旧连接（清理 Socket 资源）</li>
     *   <li>重置解码器（清除旧连接的残留状态）</li>
     *   <li>建立新连接</li>
     *   <li>启动新的接收线程</li>
     *   <li>通知监听器连接已恢复</li>
     * </ol>
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
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(matcher, "matcher");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(idempotency, "idempotency");
        CompletableFuture<Response> future = new CompletableFuture<>();
        try {
            requestExecutor.execute(() -> {
                try {
                    future.complete(request(command, matcher, timeout, idempotency));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException rejected) {
            future.completeExceptionally(new RejectedExecutionException(
                "asynchronous request queue is full or client is closed", rejected));
        }
        return future;
    }

    /**
     * 发送即忘命令（不等待响应）。
     *
     * <p>适用场景：</p>
     * <ul>
     *   <li>不需要响应的控制命令（如 *RST 复位）</li>
     *   <li>异步数据订阅（如仪器主动推送数据）</li>
     * </ul>
     *
     * <p>与 request() 的区别：</p>
     * <ul>
     *   <li>不注册 pending 请求，不等待响应</li>
     *   <li>不检查 idempotency，不自动重试</li>
     *   <li>如果连接断开，只尝试一次 connect()</li>
     * </ul>
     */
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
        connectionListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void removeConnectionListener(ConnectionListener listener) {
        connectionListeners.remove(listener);
    }

    /**
     * 添加拦截器。
     *
     * @param interceptor 拦截器实例
     */
    public void addInterceptor(ClientInterceptor interceptor) {
        interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
    }

    /**
     * 移除拦截器。
     *
     * @param interceptor 拦截器实例
     */
    public void removeInterceptor(ClientInterceptor interceptor) {
        interceptors.remove(interceptor);
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
    @Override
    public ClientMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }

    /**
     * 获取客户端诊断报告，用于问题排查和状态监控。
     *
     * @return 诊断报告
     */
    @Override
    public DiagnosticReport diagnostics() {
        return diagnostics.getReport();
    }

    private void ensureNotClosed() {
        if (closed) {
            throw new ConnectionException("client is closed");
        }
    }

    private void notifyInterceptorsBeforeRequest(Command command, CommandIdempotency idempotency) {
        for (var interceptor : interceptors) {
            try {
                interceptor.beforeRequest(command, idempotency);
            } catch (Throwable t) {
                log.warn("interceptor beforeRequest error", t);
            }
        }
    }

    private void notifyInterceptorsAfterResponse(Command command, Response response, Duration latency) {
        for (var interceptor : interceptors) {
            try {
                interceptor.afterResponse(command, response, latency);
            } catch (Throwable t) {
                log.warn("interceptor afterResponse error", t);
            }
        }
    }

    private void notifyInterceptorsAfterFailure(Command command, Throwable throwable) {
        for (var interceptor : interceptors) {
            try {
                interceptor.afterFailure(command, throwable);
            } catch (Throwable t) {
                log.warn("interceptor afterFailure error", t);
            }
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
     *
     * <p>终态操作：closed = true 是不可逆的，一旦关闭无法恢复。</p>
     *
     * <p>关闭顺序（关键）：</p>
     * <ol>
     *   <li>CAS 设置 closed = true（防止重复关闭）</li>
     *   <li>设置 reconnectSuppressed = true（阻止任何重连）</li>
     *   <li>取消已调度的重连任务</li>
     *   <li>停止接收线程（设置 running = false）</li>
     *   <li>关闭 TCP 连接（释放 Socket 资源）</li>
     *   <li>重置解码器（清除内部缓冲区）</li>
     *   <li>失败所有 pending 请求（通知等待中的线程）</li>
     *   <li>记录诊断状态变更</li>
     *   <li>释放锁后，关闭线程池（shutdown + awaitTermination）</li>
     * </ol>
     *
     * <p>注意：线程池的关闭在锁外执行，因为 awaitTermination() 会阻塞，
     * 不应该在持有 lifecycleLock 的情况下阻塞。</p>
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
            diagnostics.recordConnectionStateChange(ConnectionState.CLOSED);
        } finally {
            lifecycleLock.unlock();
        }
        shutdownExecutorGracefully(requestExecutor, "request-executor");
        shutdownExecutorGracefully(scheduler, "reconnect-scheduler");
        log.info("client for {} closed", address);
    }

    private void shutdownExecutorGracefully(ExecutorService executor, String name) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                log.warn("{} did not terminate in time, forcing shutdown", name);
                executor.shutdownNow();
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    log.error("{} failed to terminate", name);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}