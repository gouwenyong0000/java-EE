package com.example.instrument.connection;

import com.example.instrument.config.ClientConfig;
import com.example.instrument.exception.ConnectionException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * TCP 连接实现类，使用标准 Socket 实现 TCP 通信。
 * 
 * 该类封装了 Java Socket API，提供了：
 * <ul>
 *   <li>阻塞式连接管理</li>
 *   <li>基于流的读写操作</li>
 *   <li>连接状态跟踪</li>
 *   <li>线程安全的生命周期管理</li>
 * </ul>
 * 
 * <p>Socket 配置：</p>
 * <ul>
 *   <li>TCP_NODELAY: 禁用 Nagle 算法，减少延迟</li>
 *   <li>SO_KEEPALIVE: 启用 TCP keepalive，检测连接存活</li>
 *   <li>SO_RCVBUF: 接收缓冲区大小</li>
 *   <li>SO_TIMEOUT: Socket 读超时时间</li>
 * </ul>
 * 
 * @see Connection
 * @see ClientConfig
 */
public final class TcpConnection implements Connection {
    
    private final InetSocketAddress address;
    private final ClientConfig config;
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    
    private volatile Socket socket;
    private volatile InputStream input;
    private volatile OutputStream output;
    private volatile ConnectionState state = ConnectionState.NEW;

    /**
     * 构造函数，创建 TCP 连接实例。
     *
     * @param address 服务器地址，不能为空
     * @param config  客户端配置，不能为空
     * @throws NullPointerException 如果任何参数为空
     */
    public TcpConnection(InetSocketAddress address, ClientConfig config) {
        this.address = Objects.requireNonNull(address);
        this.config = Objects.requireNonNull(config);
    }

    /**
     * 建立 TCP 连接。
     * 
     * 使用配置的超时时间进行连接。
     * 连接成功后设置 Socket 选项并保存输入输出流。
     *
     * @throws ConnectionException 如果连接失败或连接已关闭
     */
    @Override 
    public void connect() {
        lifecycleLock.lock();
        try {
            if (state == ConnectionState.CLOSED) {
                throw new ConnectionException("connection is closed");
            }
            if (state == ConnectionState.CONNECTED) {
                return;
            }
            
            state = ConnectionState.CONNECTING;
            
            Socket s = new Socket();
            try {
                s.setTcpNoDelay(config.tcpNoDelay());
                s.setKeepAlive(config.keepAlive());
                s.setReceiveBufferSize(config.receiveBufferSize());
                s.setSoTimeout(config.socketReadTimeoutMillis());
                
                long timeoutMillis = Math.min(Integer.MAX_VALUE, config.connectTimeout().toMillis());
                s.connect(address, (int) timeoutMillis);
                
                input = s.getInputStream();
                output = s.getOutputStream();
                socket = s;
                state = ConnectionState.CONNECTED;
            } catch (IOException e) {
                try { s.close(); } catch (IOException ignored) {}
                state = ConnectionState.DISCONNECTED;
                throw new ConnectionException("connect failed: " + address, e);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 从连接读取数据。
     *
     * @param buffer 存储读取数据的缓冲区
     * @return 读取的字节数，-1 表示到达流的末尾
     * @throws IOException 如果读取失败或未连接
     */
    @Override 
    public int read(byte[] buffer) throws IOException {
        InputStream in = input;
        if (in == null || !isConnected()) {
            throw new IOException("not connected");
        }
        return in.read(buffer);
    }

    /**
     * 将数据写入连接并刷新。
     *
     * @param data 要写入的数据
     * @throws IOException 如果写入失败或未连接
     */
    @Override 
    public void write(byte[] data) throws IOException {
        OutputStream out = output;
        if (out == null || !isConnected()) {
            throw new IOException("not connected");
        }
        out.write(data);
        out.flush();
    }

    /**
     * 检查连接是否处于已连接状态。
     * 
     * 不仅检查状态标志，还会检查 Socket 的实际连接状态。
     *
     * @return 如果已连接返回 true
     */
    @Override 
    public boolean isConnected() {
        Socket s = socket;
        return state == ConnectionState.CONNECTED 
            && s != null 
            && s.isConnected() 
            && !s.isClosed();
    }

    /**
     * 获取当前连接状态。
     *
     * @return 连接状态枚举值
     */
    @Override 
    public ConnectionState state() {
        return state;
    }

    /**
     * 彻底关闭连接并释放资源。
     */
    @Override 
    public void close() {
        lifecycleLock.lock();
        try {
            if (state == ConnectionState.CLOSED) {
                return;
            }
            state = ConnectionState.CLOSING;
            closeQuietly(socket);
            socket = null;
            input = null;
            output = null;
            state = ConnectionState.CLOSED;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 断开连接（但不释放资源，允许重连）。
     * 
     * 与 close() 不同，disconnect() 之后可以再次调用 connect() 重连。
     */
    public void disconnect() {
        lifecycleLock.lock();
        try {
            if (state == ConnectionState.CLOSED) {
                return;
            }
            closeQuietly(socket);
            socket = null;
            input = null;
            output = null;
            state = ConnectionState.DISCONNECTED;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 安全关闭 Socket，忽略可能的异常。
     *
     * @param s 要关闭的 Socket
     */
    private static void closeQuietly(Socket s) {
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * 返回连接的文字描述。
     *
     * @return 包含地址信息的字符串
     */
    @Override 
    public String toString() {
        return "TcpConnection[" + address + "]";
    }
}