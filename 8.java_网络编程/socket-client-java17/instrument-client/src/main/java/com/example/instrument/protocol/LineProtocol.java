package com.example.instrument.protocol;

import com.example.instrument.model.Command;
import com.example.instrument.model.Response;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 基于终止符分隔的文本协议（典型 CRLF）。
 *
 * <h3>适用场景</h3>
 *
 * <p>绝大多数台式/手持仪器使用 SCPI 指令集，底层就是这种协议：命令以 {@code \r\n} 结尾， 响应也以 {@code \r\n}
 * 结尾。适用于：Tektronix、Keysight、Rigol、Keysight 等主流品牌。
 *
 * <h3>帧格式</h3>
 *
 * <pre>
 *   ┌─────────────────┬──────────┐
 *   │    PAYLOAD       │ TERMINATOR│
 *   │  "MEAS:VOLT?"     │  "\r\n"    │
 *   └─────────────────┴──────────┘
 * </pre>
 *
 * <h3>与 LengthFieldProtocol 的对比</h3>
 *
 * <pre>
 *   LineProtocol        优势：简单易读、WireShark 可直接解析、适合 SCPI
 *                      劣势：依赖终止符，如果 payload 本身含 \r\n 需转义
 *   LengthFieldProtocol 优势：二进制安全、无歧义、适合自定义二进制协议
 *                      劣势：不可读、需要双方约定帧格式
 * </pre>
 *
 * <h3>粘包 / 拆包处理流程</h3>
 *
 * <pre>
 *   decode 被调用时：
 *     ① 追加新收到的字节 → buffer（跨包缓存）
 *     ② 用 indexOf 从 frameStart 往后找 terminator 位置
 *     ③ 找到 → 切出 [frameStart, idx+terminator.length) → 加入 result
 *              frameStart 前移 → 继续 ②
 *     ④ 没找到 → frameStart 不动（说明 buffer 里只有半截帧）
 *     ⑤ reset buffer，把残余写回去 → 等下一次 decode 继续拼接
 * </pre>
 *
 * <h3>线程安全</h3>
 *
 * <p>{@code encode} 和 {@code decode} 都加了 {@code synchronized}，因为 {@link #buffer} 被两个方法 共享（decode
 * 写，decode 清）。发送线程和接收线程可能同时调用。
 */
public final class LineProtocol implements Protocol {
  /** PAYLOAD 的字符集，用于把原始字节 ↔ 可读文本。 协议层不关心业务层用什么语言，只负责 bytes ↔ text 的转换。 */
  private final Charset charset;

  /** 终止符（分隔符），标识一帧的结束。 默认 {@code "\r\n"}，也可以配置为 {@code "\n"}（Linux 风格）或 {@code "\r"}（老 Mac 风格）。 */
  private final byte[] terminator;

  /**
   * 跨包缓存 —— TCP 是流式的，一次 read 可能拿到三种情况：
   *
   * <ul>
   *   <li>完整一帧（最简单）
   *   <li>半截帧（拆包：响应还没收完）
   *   <li>多帧粘在一起（粘包：多条响应一次到达）
   * </ul>
   *
   * 每次 decode 先把新字节追加进来，解析完所有完整帧后，把残余半帧写回 buffer 等下次继续拼。
   */
  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

  /** 默认的字符编码。 */
  public LineProtocol() {
    this(StandardCharsets.UTF_8, "\r\n");
  }

  /** 使用指定的字符编码和终止符创建协议。 */
  public LineProtocol(Charset charset, String terminator) {
    this.charset = Objects.requireNonNull(charset, "charset");
    Objects.requireNonNull(terminator, "terminator");
    if (terminator.isEmpty()) throw new IllegalArgumentException("terminator must not be empty");
    this.terminator = terminator.getBytes(charset);
  }

  /**
   * 字节数组版本的 indexOf —— 朴素线性匹配。
   *
   * <p>这里没上 KMP / Boyer-Moore 算法，因为 terminator 通常很短（2 字节 CRLF）， 朴素扫描在小数据量下更简单也更快。外层用 {@code outer:}
   * 标签配合 {@code continue outer} 实现"发现不匹配就跳过整个起始位置"。
   *
   * @param source 源数组（被搜索的大数组）
   * @param target 目标数组（要搜索的 terminator）
   * @param from 起始搜索位置（inclusive）
   * @return target 首次出现的起始索引；没找到返回 -1
   */
  private static int indexOf(byte[] source, byte[] target, int from) {
    for (int i = from; i <= source.length - target.length; i++) {
      boolean match = true;
      for (int j = 0; j < target.length; j++) {
        if (source[i + j] != target[j]) {
          match = false;
          break;
        }
      }
      if (match) return i;
    }
    return -1;
  }

  /**
   * 编码：payload 直接追加 terminator。 对于 SCPI 命令 {@code "MEAS:VOLT?"}，默认编码后为 {@code "MEAS:VOLT?\r\n"}。
   */
  @Override
  public synchronized byte[] encode(Command command) {
    byte[] payload = command.payload();
    byte[] out = new byte[payload.length + terminator.length];
    System.arraycopy(payload, 0, out, 0, payload.length); // 命令本体
    System.arraycopy(terminator, 0, out, payload.length, terminator.length); // 追加终止符
    return out;
  }

  /**
   * 解码：从累积缓冲区中逐帧切分。
   *
   * <pre>
   *  buffer 累积: [P A Y L O A D \r\n P A Y L \r]  ← 第二帧还没收完（半帧）
   *                 ↑ frameStart=0              ↑ 找到 terminator
   *                 → 切第一帧 [0..8)
   *                 → frameStart 跳到 10
   *                 → 再找 terminator，找不到
   *                 → 保留 [10..end) 即 "PAYL\r" 回 buffer，等下次
   * </pre>
   */
  @Override
  public synchronized List<Response> decode(byte[] data, int offset, int length) {
    // 一次性追加，避免逐字节 write 的多次内在数组扩展检查
    buffer.write(data, offset, length);

    byte[] all = buffer.toByteArray();
    List<Response> result = new ArrayList<>();
    int frameStart = 0;
    int idx;

    // 第二步：循环查找 terminator，每找到一次切出一帧
    while ((idx = indexOf(all, terminator, frameStart)) >= 0) {
      int frameEnd = idx + terminator.length; // frameEnd 包含 terminator 本身
      byte[] frame = new byte[frameEnd - frameStart];
      System.arraycopy(all, frameStart, frame, 0, frame.length);
      result.add(new Response(frame, charset));
      frameStart = frameEnd; // 游标前进到下一帧起点
    }

    // 第三步：把残余（可能是空）写回 buffer，等下次 decode 继续拼接
    buffer.reset();
    if (frameStart < all.length) buffer.write(all, frameStart, all.length - frameStart);

    return result;
  }

  /** 返回字符编码。 */
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