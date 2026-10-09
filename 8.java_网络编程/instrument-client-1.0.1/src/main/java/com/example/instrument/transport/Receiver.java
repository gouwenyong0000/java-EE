package com.example.instrument.transport;

import com.example.instrument.protocol.ProtocolDecoder;
import com.example.instrument.transport.tcp.Connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 数据接收器，负责从连接读取数据并解码。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>在独立线程中运行，持续从连接读取数据</li>
 *   <li>使用协议解码器将字节数据转换为 Response 对象</li>
 *   <li>将解码后的响应交给 ResponseDispatcher 分发</li>
 *   <li>处理连接断开、读取错误等情况</li>
 * </ul>
 *
 * <p>生命周期：</p>
 * <ol>
 *   <li>创建时指定连接、解码器、分发器等组件</li>
 *   <li>调用 start() 启动接收线程</li>
 *   <li>接收线程持续读取数据，直到 stop() 被调用或连接断开</li>
 *   <li>发生错误时调用 errorHandler 回调</li>
 * </ol>
 *
 * <p>性能优化：</p>
 * <ul>
 *   <li>循环中不重复调用 isConnected()，依赖 read() 的阻塞语义</li>
 *   <li>buffer 在构造函数中预分配，避免每次创建</li>
 * </ul>
 *
 * @see Connection
 * @see ProtocolDecoder
 * @see ResponseDispatcher
 */
final class Receiver implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(Receiver.class);

    private final Connection connection;
    private final ProtocolDecoder decoder;
    private final ResponseDispatcher dispatcher;
    private final byte[] buffer;
    private final Consumer<Throwable> errorHandler;
    private volatile boolean running;

    /**
     * 构造函数。
     *
     * @param connection   数据连接
     * @param decoder      协议解码器
     * @param dispatcher   响应分发器
     * @param bufferSize   接收缓冲区大小
     * @param errorHandler 错误处理回调
     */
    Receiver(Connection connection, ProtocolDecoder decoder, ResponseDispatcher dispatcher,
             int bufferSize, Consumer<Throwable> errorHandler) {
        this.connection = Objects.requireNonNull(connection);
        this.decoder = Objects.requireNonNull(decoder);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.buffer = new byte[bufferSize];
        this.errorHandler = Objects.requireNonNull(errorHandler);
    }

    /**
     * 停止接收。
     *
     * 设置 running = false，接收线程会在下次循环检查时退出。
     * 如果线程正阻塞在 read() 上，需要调用者额外关闭连接以唤醒线程。
     */
    void stop() {
        running = false;
    }

    /**
     * 接收循环。
     *
     * 持续从连接读取数据，解码并分发，直到 stop() 被调用或连接断开。
     *
     * <p>退出条件：</p>
     * <ul>
     *   <li>running 被设为 false（stop() 被调用）</li>
     *   <li>read() 返回 -1（对端关闭连接）</li>
     *   <li>read() 抛出 IOException（网络异常）</li>
     * </ul>
     */
    @Override
    public void run() {
        running = true;
        log.debug("receiver started, bufferSize={}", buffer.length);

        try {
            while (running) {
                int n = connection.read(buffer);
                if (n < 0) {
                    throw new IOException("remote peer closed connection");
                }
                if (n == 0) {
                    continue;
                }

                if (log.isTraceEnabled()) {
                    log.trace("received {} bytes", n);
                }

                List<com.example.instrument.core.model.Response> frames = decoder.decode(buffer, 0, n);
                for (var frame : frames) {
                    dispatcher.dispatch(frame);
                }
            }
        } catch (Throwable t) {
            if (running) {
                log.warn("receiver stopped due to error: {}", t.getMessage());
                errorHandler.accept(t);
            }
        } finally {
            running = false;
            log.debug("receiver stopped");
        }
    }
}