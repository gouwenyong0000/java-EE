package com.example.instrument.api;

import com.example.instrument.model.Command;
import com.example.instrument.model.CommandIdempotency;
import com.example.instrument.model.Response;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 仪器通信客户端接口，定义与仪器进行 TCP 通信的核心操作。
 * 
 * 该接口是整个客户端框架的核心抽象，提供了：
 * - 连接管理：connect()、disconnect()、isConnected()、close()
 * - 同步请求：request() - 发送命令并等待响应
 * - 异步请求：requestAsync() - 异步发送命令并返回 Future
 * - 无应答发送：send() - 发送命令但不等待响应
 * - 事件监听：连接状态监听器和数据监听器
 * 
 * 实现类应该确保线程安全，能够在多线程环境下正常工作。
 * 
 * @see Command
 * @see Response
 * @see ResponseMatcher
 * @see CommandIdempotency
 */
public interface InstrumentClient extends AutoCloseable {
    
    /**
     * 连接到服务器。
     * 
     * 如果已经连接，则此方法不执行任何操作。
     * 连接过程中会启动接收线程来监听服务器响应。
     * 如果配置了重连策略，首次连接失败可能会触发重连。
     *
     * @throws com.example.instrument.exception.ConnectionException 如果连接失败
     */
    void connect();

    /**
     * 断开与服务器的连接。
     * 
     * 断开连接后，客户端会停止接收线程，并标记为非自动重连模式。
     * 所有 pending 的请求都会以 ConnectionException 失败。
     *
     * @throws com.example.instrument.exception.ConnectionException 如果断开连接时发生错误
     */
    void disconnect();

    /**
     * 检查客户端是否已连接到服务器。
     *
     * @return 如果已连接返回 true，否则返回 false
     */
    boolean isConnected();

    /**
     * 同步发送命令并等待匹配的响应。
     * 
     * 该方法会阻塞直到收到与 matcher 匹配的响应，或者超时。
     * 如果在请求过程中连接丢失，且命令是幂等的（IDEMPOTENT），可能会自动重连并重试。
     *
     * @param command    要发送的命令，不能为空
     * @param matcher    用于匹配响应的匹配器，不能为空
     * @param timeout    等待响应的超时时间，必须为正数，不能为空
     * @param idempotency 命令的幂等性，详见 {@link CommandIdempotency}
     * @return 匹配到的响应对象
     * @throws NullPointerException 如果任何参数为空
     * @throws IllegalArgumentException 如果 timeout 不是正数
     * @throws com.example.instrument.exception.RequestTimeoutException 如果等待响应超时
     * @throws com.example.instrument.exception.ConnectionException 如果连接失败或连接中断
     */
    Response request(Command command, ResponseMatcher matcher, Duration timeout, CommandIdempotency idempotency);

    /**
     * 同步发送命令并等待匹配的响应（使用默认幂等性）。
     * 
     * 默认幂等性为 UNKNOWN，不会自动重试。
     *
     * @param command    要发送的命令，不能为空
     * @param matcher    用于匹配响应的匹配器，不能为空
     * @param timeout    等待响应的超时时间，必须为正数，不能为空
     * @return 匹配到的响应对象
     * @see #request(Command, ResponseMatcher, Duration, CommandIdempotency)
     */
    default Response request(Command command, ResponseMatcher matcher, Duration timeout) {
        return request(command, matcher, timeout, CommandIdempotency.UNKNOWN);
    }

    /**
     * 异步发送命令并返回 CompletableFuture。
     * 
     * 该方法在独立的线程池中执行请求，不会阻塞调用线程。
     * 适用于需要并发发送多个请求的场景。
     *
     * @param command    要发送的命令，不能为空
     * @param matcher    用于匹配响应的匹配器，不能为空
     * @param timeout    等待响应的超时时间，必须为正数，不能为空
     * @param idempotency 命令的幂等性，详见 {@link CommandIdempotency}
     * @return 包含响应结果的 CompletableFuture，如果失败则携带异常
     * @throws NullPointerException 如果任何参数为空
     */
    CompletableFuture<Response> requestAsync(Command command, ResponseMatcher matcher, Duration timeout,
                                              CommandIdempotency idempotency);

    /**
     * 发送命令但不等待响应。
     * 
     * 该方法适用于只发送命令而不关心响应的场景，例如发送控制命令。
     * 如果在发送时未连接，会自动建立连接。
     *
     * @param command 要发送的命令，不能为空
     * @throws NullPointerException 如果 command 为空
     * @throws com.example.instrument.exception.ConnectionException 如果发送失败或未连接
     * @throws IllegalStateException 如果当前有其他请求正在进行中
     */
    void send(Command command);

    /**
     * 添加数据监听器，用于接收异步响应数据。
     * 
     * 当收到无法匹配任何 pending 请求的响应时，会触发数据监听器。
     * 可以添加多个监听器，所有监听器都会收到相同的数据。
     *
     * @param listener 数据监听器，不能为空
     * @throws NullPointerException 如果 listener 为空
     * @see DataListener
     */
    void addDataListener(DataListener listener);

    /**
     * 移除之前添加的数据监听器。
     *
     * @param listener 要移除的数据监听器，不能为空
     * @throws NullPointerException 如果 listener 为空
     */
    void removeDataListener(DataListener listener);

    /**
     * 添加连接状态监听器，用于接收连接状态变化事件。
     * 
     * 连接状态事件包括：连接建立、连接断开、正在重连、重连失败等。
     * 可以添加多个监听器，所有监听器都会收到相同的事件。
     *
     * @param listener 连接状态监听器，不能为空
     * @throws NullPointerException 如果 listener 为空
     * @see ConnectionListener
     */
    void addConnectionListener(ConnectionListener listener);

    /**
     * 移除之前添加的连接状态监听器。
     *
     * @param listener 要移除的连接状态监听器，不能为空
     * @throws NullPointerException 如果 listener 为空
     */
    void removeConnectionListener(ConnectionListener listener);

    /**
     * 创建一个阻塞式数据监听器。
     * 
     * 返回的 BlockingDataListener 允许调用者以阻塞方式从队列中获取响应数据。
     * 适用于需要顺序处理所有响应数据的场景。
     *
     * @return 阻塞式数据监听器实例
     * @see BlockingDataListener
     */
    BlockingDataListener blockingDataListener();

    /**
     * 关闭客户端并释放所有资源。
     * 
     * 关闭后会：
     * 1. 标记客户端为关闭状态
     * 2. 停止接收线程
     * 3. 关闭 socket 连接
     * 4. 取消所有 pending 的请求
     * 5. 关闭内部线程池
     * 
     * 关闭后的客户端不能再次使用。
     * 该方法实现了 AutoCloseable 接口，可以使用 try-with-resources 语法。
     */
    @Override
    void close();
}