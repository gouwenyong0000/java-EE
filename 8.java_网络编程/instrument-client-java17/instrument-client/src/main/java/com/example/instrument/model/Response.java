package com.example.instrument.model;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * 表示从仪器接收到的一条响应。
 *
 * <p>一条响应通常包含原始字节数组和按协议字符集解码后的文本表示，便于上层进行匹配、日志输出和业务判断。
 */
public final class Response {
  private final byte[] bytes;
  private final String text;
  private final long receivedAtNanos;

  public Response(byte[] bytes, Charset charset) {
    // 保存原始字节副本，并按指定字符集生成可读文本，确保响应内容不能被外部篡改。
    this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    this.text = new String(this.bytes, Objects.requireNonNull(charset, "charset"));
    this.receivedAtNanos = System.nanoTime();
  }

  /**
   * 返回响应原始字节副本，避免调用方直接修改内部缓存数据。
   */
  public byte[] bytes() {
    return bytes.clone();
  }

  /**
   * 返回按协议字符集解码后的文本内容，适合字符串比较或正则匹配。
   */
  public String text() {
    return text;
  }

  /**
   * 返回该响应进入对象时的高精度时间戳，便于做耗时分析和调试。
   */
  public long receivedAtNanos() {
    return receivedAtNanos;
  }

  @Override
  public String toString() {
    return "Response{text='%s', bytes=%d}"
        .formatted(text.replace("\r", "\\r").replace("\n", "\\n"), bytes.length);
  }
}
