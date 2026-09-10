package com.example.cli.model;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 二进制协议响应实现（LengthFieldProtocol）。
 *
 * <p>{@link #bytes()} 返回完整帧（含 STX + LEN + PAYLOAD + CRC），
 * {@link #body()} 返回去掉 STX + LEN + CRC 后的 payload 字节。
 */
public final class BinaryResponse implements Response {
  private final byte[] bytes;
  private final String text;
  private final byte[] body;
  private final long receivedAtNanos;

  /**
   * 构造二进制响应。
   *
   * @param frame   完整帧字节（含 STX + LEN + PAYLOAD + CRC）
   * @param charset 用于把帧字节解码为文本表示
   * @param body    业务载荷字节（已去掉 STX + LEN + CRC）
   */
  public BinaryResponse(byte[] frame, Charset charset, byte[] body) {
    this.bytes = Objects.requireNonNull(frame, "frame").clone();
    this.text = new String(this.bytes, Objects.requireNonNull(charset, "charset"));
    this.body = Objects.requireNonNull(body, "body").clone();
    this.receivedAtNanos = System.nanoTime();
  }

  @Override
  public byte[] bytes() {
    return bytes.clone();
  }

  @Override
  public String text() {
    return new String(body(), StandardCharsets.UTF_8);
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  @Override
  public long receivedAtNanos() {
    return receivedAtNanos;
  }

  @Override
  public String toString() {
    return "BinaryResponse{bytes=%d, body=%d}".formatted(bytes.length, body.length);
  }
}