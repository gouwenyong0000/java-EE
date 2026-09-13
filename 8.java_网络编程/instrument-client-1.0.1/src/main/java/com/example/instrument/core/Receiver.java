package com.example.instrument.core;

import com.example.instrument.connection.Connection;
import com.example.instrument.protocol.ProtocolDecoder;
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
 * @see Connection
 * @see ProtocolDecoder
 * @see ResponseDispatcher
 */
final class Receiver implements Runnable {
    
    private final Connection connection;
    private final ProtocolDecoder decoder;
    private final ResponseDispatcher dispatcher;
    private final int bufferSize;
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
        this.bufferSize = bufferSize;
        this.errorHandler = Objects.requireNonNull(errorHandler);
    }

    /**
     * 停止接收。
     * 
     * 设置 running = false，接收线程会在下次循环检查时退出。
     */
    void stop() {
        running = false;
    }

    /**
     * 接收循环。
     * 
     * 持续从连接读取数据，解码并分发，直到 stop() 被调用或连接断开。
     */
    @Override 
    public void run() {
        running = true;
        byte[] buffer = new byte[bufferSize];
        
        try {
            while (running && connection.isConnected()) {
                int n = connection.read(buffer);
                if (n < 0) {
                    throw new IOException("remote peer closed connection");
                }
                if (n == 0) {
                    continue;
                }
                
                List<com.example.instrument.model.Response> frames = decoder.decode(buffer, 0, n);
                for (var frame : frames) {
                    dispatcher.dispatch(frame);
                }
            }
        } catch (Throwable t) {
            if (running) {
                errorHandler.accept(t);
            }
        } finally {
            running = false;
        }
    }
}