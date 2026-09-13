package com.example.cli.model;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * 文本协议响应实现（LineProtocol）。
 *
 * <p>{@link #bytes()} 返回完整帧（含终止符），{@link #body()} 返回去掉终止符后的业务文本字节。
 */
public final class StringResponse implements Response {
  private final byte[] bytes;
  private final String text;
  private final byte[] body;
  private final long receivedAtNanos;

  /**
   * 构造文本响应。
   *
   * @param frame   完整帧字节（含终止符）
   * @param charset 用于把帧字节解码为文本
   * @param body    业务载荷字节（已去掉终止符）
   */
  public StringResponse(byte[] frame, Charset charset, byte[] body) {
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
    return text;
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
    return "StringResponse{text='%s', bytes=%d, body=%d}"
        .formatted(text.replace("\r", "\\r").replace("\n", "\\n"), bytes.length, body.length);
  }
}