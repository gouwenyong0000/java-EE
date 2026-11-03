package com.example.instrument.transport.tcp;

/**
 * 连接状态枚举，定义连接的完整生命周期状态。
 *
 * <p>状态转换图：</p>
 * <pre>
 * NEW ──► CONNECTING ──► CONNECTED
 *             │             │  │
 *             ▼             │  │
 *       DIS_CONNECTED ◄─────┘  │
 *             │                │
 *             ▼                ▼
 *       RECONNECTING         CLOSING ──► CLOSED
 *             │
 *             ▼
 *        CONNECTED
 * </pre>
 *
 * <p>状态说明：</p>
 * <ul>
 *   <li>NEW: 初始状态，连接对象刚创建，尚未尝试建立连接</li>
 *   <li>CONNECTING: 正在建立 TCP 连接（三次握手进行中）</li>
 *   <li>CONNECTED: 连接已建立，可进行数据读写</li>
 *   <li>DIS_CONNECTED: 连接已断开（主动或异常），资源尚未完全释放，可尝试重连</li>
 *   <li>RECONNECTING: 正在尝试重新建立连接</li>
 *   <li>CLOSING: 正在执行 TCP 连接关闭流程</li>
 *   <li>CLOSED: 连接已彻底关闭，所有资源已释放</li>
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
     * 正在建立 TCP 连接（三次握手进行中）。
     */
    CONNECTING,

    /**
     * 连接已建立，可进行数据读写。
     */
    CONNECTED,

    /**
     * 连接已断开（主动或异常），资源尚未完全释放，可尝试重连。
     */
    DIS_CONNECTED,

    /**
     * 正在尝试重新建立连接。
     */
    RECONNECTING,

    /**
     * 正在执行 TCP 连接关闭流程。
     */
    CLOSING,

    /**
     * 连接已彻底关闭，所有资源已释放，无法再使用。
     */
    CLOSED
}