package com.example.instrument.protocol;

import com.example.instrument.model.Command;
import com.example.instrument.model.Response;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CRLF-delimited instrument protocol. Good for SCPI-like text instruments.
 *
 * <p>基于回车+换行分隔符的文本协议，适用于类似 SCPI 的仪器指令格式。 发送时在 payload 后追加 terminator，接收时按 terminator
 * 切分完整帧，并保留残余不完整数据等待下一次读取。
 */
public final class LineProtocol implements Protocol {
  /** 字符编码。 */
  private final Charset charset;

  /** 终止符，用于分隔帧。 */
  private final byte[] terminator;

  /** 用于临时保存分包后的半帧数据。 */
  // 这个缓冲区用于保存分包后的半帧数据，确保跨 TCP 读包时也能正确还原完整消息。
  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

  /** 默认的字符编码。 */
  public LineProtocol() {
    this(StandardCharsets.UTF_8, "\r\n");
  }

  /** 使用指定的字符编码和终止符创建协议。 */
  public LineProtocol(Charset charset, String terminator) {
    this.charset = charset;
    this.terminator = terminator.getBytes(charset);
  }

  /** 文本协议的编码比较简单，直接在 payload 后追加分隔符即可。 */
  @Override
  public synchronized byte[] encode(Command command) {
    // 文本协议最简单的编码方式：原始 payload + 分隔符。
    byte[] payload = command.payload();
    byte[] out = new byte[payload.length + terminator.length];
    System.arraycopy(payload, 0, out, 0, payload.length);
    System.arraycopy(terminator, 0, out, payload.length, terminator.length);
    return out;
  }

  /** 解码时按帧处理，每帧之间用分隔符隔开。 */
  @Override
  public synchronized List<Response> decode(byte[] data, int offset, int length) {
    // 把本次读取到的字节追加到缓冲区里，保证即使一条响应被拆成多个 TCP 包也能重组。
    for (int i = 0; i < length; i++) buffer.write(data[offset + i]);
    byte[] all = buffer.toByteArray();
    List<Response> result = new ArrayList<>();
    int frameStart = 0;
    int idx;
    while ((idx = indexOf(all, terminator, frameStart)) >= 0) {
      int frameEnd = idx + terminator.length;
      byte[] frame = new byte[frameEnd - frameStart];
      System.arraycopy(all, frameStart, frame, 0, frame.length);
      result.add(new Response(frame, charset));
      frameStart = frameEnd;
    }
    if (frameStart > 0) {
      // 保留最后一个不完整帧，等后续数据到达后继续拼接。
      buffer.reset();
      buffer.writeBytes(slice(all, frameStart, all.length));
    }
    return result;
  }

  /** 返回字符编码。 */
  @Override
  public Charset charset() {
    return charset;
  }

  /** 在 source 中查找 target 的第一次出现位置，返回索引，没有找到则返回 -1。 */
  private static int indexOf(byte[] source, byte[] target, int from) {
    outer:
    for (int i = from; i <= source.length - target.length; i++) {
      for (int j = 0; j < target.length; j++) {
        if (source[i + j] != target[j]) continue outer;
      }
      return i;
    }
    return -1;
  }

  /** 从 source 的 from 到 to 位置截取字节。 */
  private static byte[] slice(byte[] source, int from, int to) {
    byte[] result = new byte[to - from];
    System.arraycopy(source, from, result, 0, result.length);
    return result;
  }
}
