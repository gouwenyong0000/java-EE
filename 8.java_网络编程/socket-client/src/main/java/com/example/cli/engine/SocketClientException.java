package com.example.cli.engine;

/**
 * 仪器客户端运行时异常。
 *
 * <p>用于封装连接失败、响应超时、协议错误、发送失败等运行期问题，方便上层统一处理异常。
 */
public class SocketClientException extends RuntimeException {
  /**
   * 创建一个仅包含错误说明的异常实例。
   */
  public SocketClientException(String message) {
    super(message);
  }

  /**
   * 创建一个附带根因的异常实例，便于排查真实失败原因。
   */
  public SocketClientException(String message, Throwable cause) {
    super(message, cause);
  }
}
