package com.example.cli.model;

/**
 * 表示从仪器接收到的一条响应。
 *
 * <p>这是一个接口，不同协议对应不同的实现类：
 * <ul>
 *   <li>{@link StringResponse} —— 文本协议（LineProtocol），body 为去掉终止符的文本内容</li>
 *   <li>{@link BinaryResponse} —— 二进制协议（LengthFieldProtocol），body 为去掉帧头/长度/CRC 的 payload</li>
 * </ul>
 *
 * <p>一条响应通常包含：
 * <ul>
 *   <li>{@link #bytes()} —— 完整帧的原始字节（含协议层帧头/分隔符/校验等）</li>
 *   <li>{@link #text()} —— 按协议字符集解码后的文本表示，便于上层匹配、日志输出</li>
 *   <li>{@link #body()} —— 业务载荷（payload），已剥离协议层的帧头/分隔符/校验字段</li>
 * </ul>
 */
public interface Response {

  /**
   * 返回响应完整帧的原始字节副本，避免调用方直接修改内部缓存数据。
   *
   * <p>注意：返回的是<b>完整帧</b>（包含协议层附加字段，如终止符、帧头、CRC 等）。
   * 如果只需要业务载荷，请使用 {@link #body()}。
   */
  byte[] bytes();

  /**
   * 返回按协议字符集解码后的文本内容，适合字符串比较或正则匹配。
   */
  String text();

  /**
   * 返回业务载荷（payload）的字节副本，已剥离协议层的帧头/分隔符/校验字段。
   *
   * <p>不同协议的 body 含义不同：
   * <ul>
   *   <li>LineProtocol：去掉终止符后的文本字节</li>
   *   <li>LengthFieldProtocol：去掉 STX + LEN + CRC 后的 payload 字节</li>
   * </ul>
   */
  byte[] body();

  /**
   * 返回该响应进入对象时的高精度时间戳，便于做耗时分析和调试。
   */
  long receivedAtNanos();
}