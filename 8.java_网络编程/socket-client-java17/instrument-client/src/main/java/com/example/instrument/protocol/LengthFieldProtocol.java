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
 * 固定头 + 长度字段 + CRC8 校验的二进制帧协议。
 *
 * <h3>帧格式（大端字节序 / Big-Endian）</h3>
 *
 * <pre>
 *   ┌──────┬──────────────┬─────────────────┬───────┐
 *   │ STX  │ LEN_H + LEN_L │ PAYLOAD         │ CRC8  │
 *   │ 1byte│   2 bytes     │   LEN bytes     │ 1byte │
 *   │ 0xAA │ 高字节 + 低字节│                 │       │
 *   └──────┴──────────────┴─────────────────┴───────┘
 *     │         │                │                 │
 *     │         │                │                 └─ 异或校验 = STX + LEN_H + LEN_L + PAYLOAD
 *     │         │                └─ 有效载荷（仪器命令或响应）
 *     │         └─ 无符号 16 位，标识 PAYLOAD 字节数（0 ~ 65535）
 *     └─ 帧起始标记（Magic Byte），帮助解码端快速定位帧头
 * </pre>
 *
 * <h3>粘包 / 拆包处理</h3>
 *
 * <p>TCP 是流式协议，不保证一次 read 拿到恰好完整一帧。解码时先把所有新字节累计到内部 buffer，再从中逐帧提取。最后把未消费的残余字节（半截帧）留在 buffer 里等下一次。
 *
 * <h3>校验失败重同步（Resync）</h3>
 *
 * <p>如果 CRC8 校验不通过，说明当前位置的 STX 只是 payload 里碰巧出现了 0xAA，不是真的帧头。 此时 cursor++ 继续找下一个 STX，而不是报错整包丢弃 ——
 * 这就是轻量级重同步策略。
 *
 * <h3>线程安全</h3>
 *
 * <p>{@code encode} 和 {@code decode} 都加了 {@code synchronized}，因为它们共享内部 buffer。
 */
public final class LengthFieldProtocol implements Protocol {
  /** 帧起始标记（Magic Byte），帮助解码端快速定位一帧的开始位置。 */
  private static final byte STX = (byte) 0xAA;

  /** payload 最大允许字节数（64KB）。防止恶意客户端声称超大 payload 导致 buffer 无限等待。 */
  private static final int MAX_PAYLOAD_LEN = 65535;

  /** PAYLOAD 的字符集，用于把字节解码成 {@link Response#text()} 可读文本。 */
  private final Charset charset;

  /** 跨包缓存：TCP 是流式的，一次 decode 可能只拿到半帧或多帧粘在一起。 每次 decode 先把新字节追加进来，解析完完整帧后把残余写回。 */
  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

  public LengthFieldProtocol() {
    this(StandardCharsets.UTF_8);
  }

  public LengthFieldProtocol(Charset charset) {
    this.charset = charset;
  }

  /**
   * 计算校验和 —— 逐字节异或（XOR）。
   *
   * <p>严格来说这不是标准意义上的 CRC8，只是一个简单的 8 位异或校验，能快速发现单字节错误， 对于仪器命令/响应这种低噪声场景足够用了。如果需要更强检错能力，可以换成
   * CRC-8/ITU、 CRC-8/MAXIM 等多项式实现。
   *
   * @param a 原始字节数组
   * @param off 起始偏移（inclusive，参与校验）
   * @param len 参与校验的字节数（exclusive 终点 = off + len）
   * @return 8 位校验值
   */
  private static byte checksum(byte[] a, int off, int len) {
    byte x = 0;
    for (int i = off; i < off + len; i++) x ^= a[i];
    return x;
  }

  /**
   * 把 {@link Command} 组装成完整二进制帧。
   *
   * <pre>
   *  总帧长度 = 1(STX) + 2(LEN) + payload.length + 1(CRC)
   *                                    └─ CRC 覆盖前 N-1 字节，自身不参与校验 ─┘
   * </pre>
   *
   * @throws IllegalArgumentException payload 超过 65535 字节时抛出（长度字段只有 2 字节）
   */
  @Override
  public synchronized byte[] encode(Command command) {
    byte[] payload = command.payload();
    // LEN 是无符号 16 位整数，最大 65535
    if (payload.length > 0xFFFF)
      throw new IllegalArgumentException("payload too large (max 65535 bytes)");

    // ① 分配总帧长度
    byte[] out = new byte[1 + 2 + payload.length + 1];

    // ② 写帧头 Magic Byte
    out[0] = STX;

    // ③ 大端写 payload 长度（高字节在前）
    out[1] = (byte) (payload.length >>> 8); // 高 8 位
    out[2] = (byte) payload.length; // 低 8 位

    // ④ 拷贝 payload 到帧体
    System.arraycopy(payload, 0, out, 3, payload.length);

    // ⑤ 写 CRC8：对 STX + LEN + PAYLOAD 共 (frameLen - 1) 字节做异或
    out[out.length - 1] = checksum(out, 0, out.length - 1);

    return out;
  }

  /**
   * 把一段 TCP 原始字节流切分成若干个完整的 {@link Response} 帧。
   *
   * <h4>核心循环逻辑</h4>
   *
   * <pre>
   *   ① 内层 cursor++ 跳过非 STX 字节，定位下一帧头
   *          │
   *          ▼
   *   ② cursor + 4 > all.length ? → break（帧头 + 长度字段都没凑齐，等更多字节）
   *          │
   *          ▼
   *   ③ BigEndian 读出 payloadLen = (LEN_H << 8) | LEN_L
   *          │
   *          ▼
   *   ④ cursor + frameLen > all.length ? → break（整帧没凑齐，等更多字节）
   *          │
   *          ▼
   *   ⑤ CRC8 校验 ── pass → 切出完整帧，cursor 前进整帧长度
   *              ── fail  → cursor++（resync：当前 STX 是假的，找下一个）
   * </pre>
   *
   * <h4>拆包 / 粘包场景举例</h4>
   *
   * <pre>
   *  场景1 拆包：完整帧被拆成两次 read
   *    read1 → [AA 00 03]          → buffer 累计，cursor+frameLen 不够，break
   *    read2 → [48 49 21 55]       → buffer=[AA 00 03 48 49 21 55]，整帧 OK
   *
   *  场景2 粘包：两帧粘在一起
   *    decode 会在一次调用里产出两个 Response
   * </pre>
   */
  @Override
  public synchronized List<Response> decode(byte[] data, int offset, int length) {
    // 一次性追加，避免逐字节 write 的多次内在数组扩展检查
    buffer.write(data, offset, length);

    List<Response> result = new ArrayList<>();
    byte[] all = buffer.toByteArray();
    int cursor = 0;

    while (true) {
      // ① 内层循环：跳过所有不是 STX 的字节，定位下一帧头
      while (cursor < all.length && all[cursor] != STX) cursor++;

      // ② cursor+4 = 帧头(1) + 长度字段(2) + 至少 1 字节 payload(占位)，不够说明只有半截帧头
      if (cursor + 4 > all.length) break;

      // ③ 大端无符号 16 位读出 payload 长度
      //    & 0xFF 是因为 Java byte 是有符号的（-128~127），必须转成无符号再参与位运算
      int payloadLen = ((all[cursor + 1] & 0xFF) << 8) | (all[cursor + 2] & 0xFF);

      // payloadLen 超限 → 丢弃该假帧头，继续找下一个 STX
      if (payloadLen > MAX_PAYLOAD_LEN) {
        cursor++;
        continue;
      }

      // ④ 算整帧长度 = STX(1) + LEN(2) + payloadLen + CRC8(1)
      int frameLen = 1 + 2 + payloadLen + 1;

      // ⑤ 整帧在 buffer 里还没凑齐 → 等待下一次 decode
      if (cursor + frameLen > all.length) break;

      // ⑥ CRC8 校验（校验范围 = STX ~ PAYLOAD，CRC8 自身不参与校验）
      byte expected = checksum(all, cursor, frameLen - 1);
      byte actual = all[cursor + frameLen - 1];
      if (expected != actual) {
        // 校验失败 → 当前位置的 0xAA 只是 payload 里碰巧出现的假帧头
        // 轻量级重同步：cursor++ 继续找下一个 STX，不丢弃整个 buffer
        cursor++;
        continue;
      }

      // ⑦ 校验通过 → 切出完整帧，构造 Response
      result.add(new Response(Arrays.copyOfRange(all, cursor, cursor + frameLen), charset));
      cursor += frameLen;
    }

    // 清理 buffer，把还没消费的残余字节（半截帧）写回去，留待下一次 decode 继续拼接
    buffer.reset();
    if (cursor < all.length) buffer.writeBytes(Arrays.copyOfRange(all, cursor, all.length));

    return result;
  }

  @Override
  public Charset charset() {
    return charset;
  }

  /** 清空跨包缓存。sendAndMatch 发送新命令前调用，防止粘包干扰。 */
  @Override
  public synchronized void clearCache() {
    buffer.reset();
  }


}