package com.example.instrument.testing;

import com.example.instrument.network.Connection;
import com.example.instrument.network.ConnectionState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可控制的模拟连接，用于单元测试。
 *
 * <p>支持以下测试场景：</p>
 * <ul>
 *   <li>模拟正常读写</li>
 *   <li>模拟连接失败</li>
 *   <li>模拟读取超时</li>
 *   <li>模拟写入失败</li>
 *   <li>注入预定义的响应数据</li>
 *   <li>捕获发送的命令数据</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * MockConnection conn = new MockConnection();
 * conn.enqueueResponse(new byte[]{'O', 'K', '\r', '\n'});
 * conn.connect();
 * byte[] data = new byte[100];
 * int n = conn.read(data); // 返回预定义的响应
 * }</pre>
 */
public final class MockConnection implements Connection {

    private final BlockingQueue<byte[]> responseQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<byte[]> sentCommands = new LinkedBlockingQueue<>();
    private final AtomicReference<IOException> readFailure = new AtomicReference<>();
    private final AtomicReference<IOException> writeFailure = new AtomicReference<>();
    private final AtomicBoolean connectFailure = new AtomicBoolean();
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.NEW);
    private final AtomicBoolean autoConnect = new AtomicBoolean(true);

    private ByteArrayOutputStream writeBuffer;

    /**
     * 将响应数据加入队列，下次 read() 时返回。
     *
     * @param data 响应数据
     */
    public void enqueueResponse(byte[] data) {
        responseQueue.offer(Objects.requireNonNull(data).clone());
    }

    /**
     * 将多条响应数据加入队列。
     *
     * @param dataList 响应数据列表
     */
    public void enqueueResponses(byte[]... dataList) {
        for (byte[] data : dataList) {
            enqueueResponse(data);
        }
    }

    /**
     * 设置下次 read() 时抛出的异常。
     *
     * @param exception 要抛出的异常
     */
    public void setReadFailure(IOException exception) {
        readFailure.set(exception);
    }

    /**
     * 设置下次 write() 时抛出的异常。
     *
     * @param exception 要抛出的异常
     */
    public void setWriteFailure(IOException exception) {
        writeFailure.set(exception);
    }

    /**
     * 设置 connect() 是否失败。
     *
     * @param fail 如果为 true，connect() 将抛出 IOException
     */
    public void setConnectFailure(boolean fail) {
        connectFailure.set(fail);
    }

    /**
     * 获取已发送的命令数据。
     *
     * @return 发送的命令数据，如果没有则返回 null
     */
    public byte[] pollSentCommand() {
        return sentCommands.poll();
    }

    /**
     * 获取已发送的命令数据（带超时）。
     *
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 发送的命令数据，如果超时则返回 null
     */
    public byte[] pollSentCommand(long timeout, TimeUnit unit) throws InterruptedException {
        return sentCommands.poll(timeout, unit);
    }

    /**
     * 获取已发送命令的数量。
     *
     * @return 发送命令的数量
     */
    public int sentCommandCount() {
        return sentCommands.size();
    }

    /**
     * 清空响应队列。
     */
    public void clearResponses() {
        responseQueue.clear();
    }

    /**
     * 清空已发送命令队列。
     */
    public void clearSentCommands() {
        sentCommands.clear();
    }

    /**
     * 设置是否自动连接（默认 true）。
     *
     * @param auto 如果为 false，connect() 将不改变状态
     */
    public void setAutoConnect(boolean auto) {
        autoConnect.set(auto);
    }

    @Override
    public void connect() {
        if (connectFailure.getAndSet(false)) {
            throw new com.example.instrument.exception.ConnectionException("simulated connect failure");
        }
        if (autoConnect.get()) {
            state.set(ConnectionState.CONNECTED);
            writeBuffer = new ByteArrayOutputStream();
        }
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        IOException failure = readFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }

        byte[] data = responseQueue.poll();
        if (data == null) {
            return 0;
        }

        int len = Math.min(data.length, buffer.length);
        System.arraycopy(data, 0, buffer, 0, len);
        return len;
    }

    @Override
    public void write(byte[] data) throws IOException {
        IOException failure = writeFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }

        sentCommands.offer(data.clone());
        if (writeBuffer != null) {
            writeBuffer.write(data);
        }
    }

    @Override
    public boolean isConnected() {
        return state.get() == ConnectionState.CONNECTED;
    }

    @Override
    public ConnectionState state() {
        return state.get();
    }

    /**
     * 模拟断开连接。
     */
    public void disconnect() {
        state.set(ConnectionState.DISCONNECTED);
    }

    @Override
    public void close() {
        state.set(ConnectionState.CLOSED);
        writeBuffer = null;
    }

    /**
     * 获取写入缓冲区的内容。
     *
     * @return 写入的字节数据
     */
    public byte[] getWrittenBytes() {
        return writeBuffer != null ? writeBuffer.toByteArray() : new byte[0];
    }
}