package com.example.instrument.transport.tcp;

/**
 * 连接状态枚举，描述 TCP 连接的生命周期状态。
 *
 * <p>状态转移图：</p>
 * <pre>
 * NEW ──connect()──► CONNECTING ──成功──► CONNECTED
 *                       │                    │
 *                    失败│                 disconnect()
 *                       ▼                    ▼
 *                 DIS_CONNECTED ◄────────────┘
 *
 * 任意非 CLOSED 状态 ──close()──► CLOSING ──► CLOSED
 *
 * DIS_CONNECTED ──connect()──► CONNECTING（重连时复用 CONNECTING）
 * </pre>
 *
 * <p>状态转移说明：</p>
 * <ul>
 *   <li>NEW、DIS_CONNECTED → CONNECTING：调用 connect() 建立或重新建立连接</li>
 *   <li>CONNECTING → CONNECTED：连接成功</li>
 *   <li>CONNECTING → DIS_CONNECTED：连接失败</li>
 *   <li>CONNECTED → DIS_CONNECTED：调用 disconnect() 断开连接</li>
 *   <li>任意非 CLOSED 状态 → CLOSING → CLOSED：调用 close() 关闭连接并释放资源</li>
 * </ul>
 *
 * <p>状态说明：</p>
 * <ul>
 *   <li>NEW：初始状态，连接对象刚创建，尚未尝试建立连接</li>
 *   <li>CONNECTING：正在建立 TCP 连接并初始化输入、输出流</li>
 *   <li>CONNECTED：连接已建立，可进行数据读写</li>
 *   <li>DIS_CONNECTED：连接已断开，Socket 和流已关闭，可再次调用 connect() 重连</li>
 *   <li>RECONNECTING：预留状态；当前实现中重连过程使用 CONNECTING</li>
 *   <li>CLOSING：正在执行 TCP 连接关闭流程</li>
 *   <li>CLOSED：连接已彻底关闭，所有资源已释放</li>
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