package com.example.instrument.connection;

import java.io.IOException;

/**
 * 连接接口，定义了 TCP 连接的抽象操作。
 * 
 * 该接口封装了低层次的 Socket I/O 操作，为上层提供统一的连接抽象。
 * 实现类负责管理底层的 Socket 连接，包括连接建立、数据读写、状态管理。
 * 
 * <p>接口设计原则：</p>
 * <ul>
 *   <li>简单性：只包含必要的 I/O 操作</li>
 *   <li>资源管理：实现 AutoCloseable，支持 try-with-resources</li>
 *   <li>状态追踪：通过 ConnectionState 枚举追踪连接状态</li>
 * </ul>
 * 
 * @see TcpConnection
 * @see ConnectionState
 */
public interface Connection extends AutoCloseable {
    
    /**
     * 建立 TCP 连接。
     * 
     * 如果已经连接，此方法不执行任何操作。
     * 如果连接已关闭（CLOSED），则抛出异常。
     *
     * @throws java.io.IOException 如果连接失败
     */
    void connect();

    /**
     * 从连接读取数据到缓冲区。
     * 
     * 这是一个阻塞操作，会一直等待数据可用或连接关闭。
     *
     * @param buffer 存储读取数据的缓冲区
     * @return 读取的字节数，如果到达流的末尾则返回 -1
     * @throws java.io.IOException 如果读取失败或未连接
     */
    int read(byte[] buffer) throws IOException;

    /**
     * 将数据写入连接。
     * 
     * 写入后会自动 flush，确保数据立即发送。
     *
     * @param data 要写入的数据
     * @throws java.io.IOException 如果写入失败或未连接
     */
    void write(byte[] data) throws IOException;

    /**
     * 检查连接是否处于已连接状态。
     *
     * @return 如果已连接返回 true，否则返回 false
     */
    boolean isConnected();

    /**
     * 获取当前连接状态。
     *
     * @return 连接状态枚举值
     * @see ConnectionState
     */
    ConnectionState state();

    /**
     * 彻底关闭连接并释放资源。
     * 
     * 关闭后连接无法再次使用。
     */
    @Override
    void close();
}