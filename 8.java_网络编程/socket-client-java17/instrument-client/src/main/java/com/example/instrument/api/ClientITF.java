package com.example.instrument.api;

import com.example.instrument.model.Command;
import com.example.instrument.model.Response;
import java.time.Duration;

public interface ClientITF extends AutoCloseable {
  /** 初始化客户端内部状态，通常在真正建立连接前调用。 该步骤负责启动后台接收线程等所需资源，并确保后续 API 具备可用环境。 */
  void init();

  /** 建立到服务端的 TCP 连接。 若连接尚未初始化或已断开，则会根据配置执行重连或抛出异常。 */
  void connect();

  /** 主动断开当前连接，并停止接收线程和待处理请求。 常用于关闭客户端、重置状态或在错误场景中清理资源。 */
  void disconnect();

  /** 判断当前客户端是否仍然保持着有效连接。 这里通常检查 socket 是否存在、未关闭且输入输出流未被关闭。 */
  boolean isConnected();

  /**
   * 发送命令并等待满足 matcher 条件的响应返回（同步阻塞）。
   *
   * @param command 要发送的命令
   * @param matcher 响应匹配器
   * @param timeout 超时时间；为 null 时使用 {@link com.example.instrument.engine.SocketClientConfig#responseTimeout()} 默认值
   */
  Response sendAndMatch(Command command, ResponseMatcher matcher, Duration timeout);

  /** 等同于 {@code sendAndMatch(command, matcher, null)}，使用 config 默认响应超时。 */
  default Response sendAndMatch(Command command, ResponseMatcher matcher) {
    return sendAndMatch(command, matcher, null);
  }

  /**
   * 发送字符串命令 + 正则匹配响应（便捷入口）。
   *
   * @param command SCPI 风格字符串命令（如 "MEAS:VOLT?"）
   * @param regex   期望响应的正则表达式（如 "VOLT:[0-9.]+\\r\\n"）
   * @param timeout 超时时间；为 null 时使用 config 默认值
   */
  default Response sendAndRegex(String command, String regex, Duration timeout) {
    return sendAndMatch(Command.of(command), response -> response.text().matches(regex), timeout);
  }

  /** 等同于 {@code sendAndRegex(command, regex, null)}，使用 config 默认超时。 */
  default Response sendAndRegex(String command, String regex) {
    return sendAndRegex(command, regex, null);
  }

  /** 注册数据监听器，用于收到服务端响应或推送数据时触发回调。 监听器通常用于异步观察流量，而不是替代同步请求等待逻辑。 */
  void addListener(DataListener listener);

  /** 移除已注册的监听器，避免后续再次收到事件回调。 */
  void removeListener(DataListener listener);

  /** 创建一个阻塞式监听器，用于从内部消息队列中按顺序获取响应数据。 适合对接需要“等待下一条数据”语义的消费模式。 */
  BlockingDataListener blockingListener();

  @Override
  default void close() {
    // AutoCloseable 的默认关闭行为，直接调用 disconnect() 来释放资源。
    disconnect();
  }
}