package com.example.cli.api;

import com.example.cli.model.Response;

/**
 * 异步数据事件监听器。
 *
 * <p>当底层 socket 收到一条有效响应时，会触发该回调，允许外部在不阻塞接收线程的前提下观察数据流。
 */
@FunctionalInterface
public interface DataListener {
  /**
   * 处理一条已解码的数据响应。
   * 实现中应尽量避免抛出异常，否则可能影响接收线程的持续运行。
   */
  void onData(Response response);
}
