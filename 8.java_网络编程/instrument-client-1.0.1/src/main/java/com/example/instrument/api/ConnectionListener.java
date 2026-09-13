package com.example.instrument.api;

/**
 * 连接状态监听器接口，用于接收客户端连接状态变化事件。
 * 
 * 该接口定义了多个回调方法，涵盖连接的完整生命周期：
 * - onConnected: 连接成功建立
 * - onDisconnected: 连接断开（可能是主动断开或异常断开）
 * - onReconnecting: 正在尝试重连
 * - onReconnectFailed: 重连失败
 * 
 * <p>使用示例：</p>
 * <pre>{@code
 * client.addConnectionListener(new ConnectionListener() {
 *     @Override
 *     public void onConnected() {
 *         System.out.println("Connected to server");
 *     }
 *     
 *     @Override
 *     public void onDisconnected(Throwable cause) {
 *         System.out.println("Disconnected: " + (cause != null ? cause.getMessage() : "normal"));
 *     }
 *     
 *     @Override
 *     public void onReconnecting(int attempt, Duration delay, Throwable cause) {
 *         System.out.println("Reconnecting... attempt " + attempt + " after " + delay);
 *     }
 *     
 *     @Override
 *     public void onReconnectFailed(Throwable cause) {
 *         System.out.println("Reconnect failed: " + cause.getMessage());
 *     }
 * });
 * }</pre>
 * 
 * @see com.example.instrument.api.InstrumentClient#addConnectionListener
 */
public interface ConnectionListener {
    
    /**
     * 连接成功建立时调用。
     * 
     * 可能在首次连接或重连成功后调用。
     */
    default void onConnected() {}

    /**
     * 连接断开时调用。
     * 
     * 当连接意外断开或调用 disconnect() 方法主动断开时都会触发。
     *
     * @param cause 导致断开的原因。如果是因为调用 disconnect() 主动断开，则为 null；
     *              如果是异常断开，则包含具体的异常信息
     */
    default void onDisconnected(Throwable cause) {}

    /**
     * 正在尝试重连时调用。
     * 
     * 当启用自动重连且连接断开后，会按照配置的策略尝试重连。
     *
     * @param attempt 当前重连尝试的次数（从 1 开始）
     * @param delay   距离下次重连的延迟时间
     * @param cause   导致需要重连的原因
     */
    default void onReconnecting(int attempt, java.time.Duration delay, Throwable cause) {}

    /**
     * 重连失败，所有重试次数用尽后调用。
     * 
     * 当按照重连策略尝试了所有次数后仍然无法连接成功时触发。
     *
     * @param cause 导致重连失败的最终原因
     */
    default void onReconnectFailed(Throwable cause) {}
}