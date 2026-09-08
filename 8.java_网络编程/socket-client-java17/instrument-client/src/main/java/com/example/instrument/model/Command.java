package com.example.instrument.model;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 表示发送给仪器的命令载荷。
 *
 * <p>命令本身可以是文本指令，也可以是二进制 payload；在本工程中它被统一封装为字节数组，便于协议层编码。
 */
public record Command(byte[] payload) {
  public Command {
    // 保护调用方传入数组，避免外部后续修改导致命令内容被篡改。
    Objects.requireNonNull(payload, "payload");
    payload = payload.clone();
  }

  /**
   * 从字符串创建命令，使用 UTF-8 编码（与 LineProtocol 默认字符集一致）。
   */
  public static Command of(String command) {
    return new Command(command.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public byte[] payload() {
    // 返回副本，防止外部直接修改内部字节数组。
    return payload.clone();
  }
}