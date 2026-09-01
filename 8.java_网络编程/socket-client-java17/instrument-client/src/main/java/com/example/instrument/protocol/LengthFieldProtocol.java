package com.example.instrument.protocol;

import com.example.instrument.model.Command;
import com.example.instrument.model.Response;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Example binary frame: [0xAA][LEN_H][LEN_L][PAYLOAD...][CRC8],
 * 其中，LEN 代表有效载荷字节数，CRC8 是报头和有效载荷的简单异或校验。
 *
 * <p>该协议适用于固定头+长度字段+校验和的二进制仪器通信，能够在无文本分隔符的情况下可靠定位一帧完整数据。
 */
public final class LengthFieldProtocol implements Protocol {
  private static final byte STX = (byte) 0xAA;
  private final Charset charset;
  // 由于 TCP 是流式协议，必须缓存未完整的二进制帧，避免跨包导致的数据丢失。
  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

  public LengthFieldProtocol() {
    this(StandardCharsets.UTF_8);
  }

  public LengthFieldProtocol(Charset charset) {
    this.charset = charset;
  }

  @Override
  public synchronized byte[] encode(Command command) {
    // 头部固定为 STX + 2 字节长度 + payload + CRC8，便于接收端精确解析。
    byte[] payload = command.payload();
    if (payload.length > 0xFFFF) throw new IllegalArgumentException("payload too large");
    byte[] out = new byte[1 + 2 + payload.length + 1];
    out[0] = STX; // 起始标记，标识帧头
    out[1] = (byte) (payload.length >>> 8); // 长度高字节
    out[2] = (byte) payload.length; // 长度低字节
    System.arraycopy(payload, 0, out, 3, payload.length);
    out[out.length - 1] = checksum(out, 0, out.length - 1);
    return out;
  }

  @Override
  public synchronized List<Response> decode(byte[] data, int offset, int length) {
    // 追加新接收的字节，并从缓存中尝试解析出多个完整帧。
    for (int i = 0; i < length; i++) buffer.write(data[offset + i]);
    List<Response> result = new ArrayList<>();
    byte[] all = buffer.toByteArray();
    int cursor = 0;
    while (true) {
      while (cursor < all.length && all[cursor] != STX) cursor++;
      if (cursor + 4 > all.length) break;
      int payloadLen = ((all[cursor + 1] & 0xFF) << 8) | (all[cursor + 2] & 0xFF);
      int frameLen = 1 + 2 + payloadLen + 1;
      if (cursor + frameLen > all.length) break;
      byte expected = checksum(all, cursor, frameLen - 1);
      byte actual = all[cursor + frameLen - 1];
      if (expected != actual) {
        // 如果校验失败，则从当前位置继续寻找下一个可能的帧起点，做轻量级 resync。
        cursor++;
        continue;
      }
      result.add(new Response(Arrays.copyOfRange(all, cursor, cursor + frameLen), charset));
      cursor += frameLen;
    }
    buffer.reset();
    if (cursor < all.length) buffer.writeBytes(Arrays.copyOfRange(all, cursor, all.length));
    return result;
  }

  @Override
  public Charset charset() {
    return charset;
  }

  /**
   * 计算 CRC8 校验值。
   * 这里只做简单异或校验，适合轻量级、低复杂度的设备协议。
   */
  private static byte checksum(byte[] a, int off, int len) {
    byte x = 0;
    for (int i = off; i < off + len; i++) x ^= a[i];
    return x;
  }
}
