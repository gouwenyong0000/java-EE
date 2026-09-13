package com.example.instrument.connection;

/**
 * 连接状态枚举，定义连接的生命周期状态。
 * 
 * <p>状态转换图：</p>
 * <pre>
 * NEW ──────► CONNECTING ──────► CONNECTED
 *               │                    │
 *               │                    │
 *               ▼                    ▼
 *           DISCONNECTED ◄──── DISCONNECTED
 *               │
 *               │
 *               ▼
 *            CLOSING ──────► CLOSED
 * </pre>
 * 
 * <p>状态说明：</p>
 * <ul>
 *   <li>NEW: 初始状态，连接刚创建但尚未尝试连接</li>
 *   <li>CONNECTING: 正在建立连接</li>
 *   <li>CONNECTED: 连接已建立，可以进行数据交换</li>
 *   <li>DISCONNECTED: 连接已断开（可能是主动断开或异常断开）</li>
 *   <li>CLOSING: 正在关闭连接</li>
 *   <li>CLOSED: 连接已彻底关闭，资源已释放</li>
 * </ul>
 * 
 * @see Connection
 */
public enum ConnectionState {
    
    /**
     * 初始状态。
     * 
     * 连接对象刚创建，尚未尝试建立连接。
     */
    NEW,
    
    /**
     * 正在连接状态。
     * 
     * 正在执行 TCP 三次握手。
     */
    CONNECTING,
    
    /**
     * 已连接状态。
     * 
     * TCP 连接已建立，可以进行数据读写。
     */
    CONNECTED,
    
    /**
     * 已断开状态。
     * 
     * 连接已断开，但资源尚未完全释放，可能还可以重连。
     */
    DISCONNECTED,
    
    /**
     * 正在关闭状态。
     * 
     * 正在执行关闭操作。
     */
    RECONNECTING,
    
    /**
     * 正在关闭状态。
     */
    CLOSING,
    
    /**
     * 已关闭状态。
     * 
     * 连接已彻底关闭，无法再使用。
     */
    CLOSED
}