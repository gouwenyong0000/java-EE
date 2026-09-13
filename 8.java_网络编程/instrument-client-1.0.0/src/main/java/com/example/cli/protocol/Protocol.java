package com.example.cli.protocol;

import com.example.cli.model.Command;
import com.example.cli.model.Response;

import java.nio.charset.Charset;
import java.util.List;

/**
 * 协议层接口 —— 定义"怎么把业务消息↔TCP字节流"互转的契约。
 *
 * <h3>在四层架构中的位置</h3>
 * <pre>
 *   接口层 ClientITF         ← 契约：init/connect/sendAndRegex
 *      │
 *   ┌──┴─────────────────────────────────┐
 *   │  协议层 Protocol (本接口)            │  ← "语言"：决定命令长什么样、响应怎么拆帧
 *   │     ├── LineProtocol (CRLF 文本)     │     把业务层的 Command/Response 翻译成
 *   │     └── LengthFieldProtocol (二进制) │     服务端能读懂的字节流
 *   └──┬─────────────────────────────────┘
 *      │
 *   通信层 SocketClientITFImpl  ← "运输"：TCP 连接、信号量、重连、缓冲
 * </pre>
 *
 * <h3>为什么要抽象成接口</h3>
 * <p>不同仪器用的协议完全不同：
 * <ul>
 *   <li>SCPI 仪器（泰克/是德/鼎阳）：命令以 {@code \r\n} 结尾 → {@link LineProtocol}</li>
 *   <li>自定义二进制协议（某些功率计/频谱仪）：固定头 + 长度 + CRC → {@link LengthFieldProtocol}</li>
 *   <li>Modbus RTU / Modbus TCP：又一种帧格式</li>
 * </ul>
 * 把编解码抽成接口后，Engine（SocketClientITFImpl）就不用关心具体协议 —— 它只管"发编码后的字节、收原始字节"，
 * 协议层负责把业务语义翻译成对端能读懂的帧。这就是**策略模式**的体现。
 *
 * <h3>协议实现必须满足的隐含约束</h3>
 * <ol>
 *   <li>{@code encode} 和 {@code decode} 必须是**对称**的：decode(encode(cmd)) 必须能还原出原始 cmd 对应的 Response</li>
 *   <li>decode 内部必须维护**跨包缓存**：TCP 是流式的，一次 decode 可能拿到半帧、也可能拿到多帧粘在一起</li>
 *   <li>decode 必须是**线程安全**的：Engine 的接收线程可能并发调用 decode（虽然本实现只有一个接收线程，但 encode 可能和 decode 并发）</li>
 * </ol>
 *
 * @see LineProtocol         SCPI 风格 CRLF 文本协议
 * @see LengthFieldProtocol  固定头 + 长度字段 + CRC8 的二进制协议
 */
public interface Protocol {

  /**
   * 编码：把业务层的 {@link Command} → 可直接 write 到 socket 的原始字节。
   *
   * <p>为什么这一步在协议层而不是 Engine 层？因为不同协议的"命令格式"完全不同：
   * <ul>
   *   <li>LineProtocol：{@code "MEAS:VOLT?"} → {@code [MEAS:VOLT?\r\n]}</li>
   *   <li>LengthFieldProtocol：{@code [0xAA][LEN_H][LEN_L][PAYLOAD][CRC8]}</li>
   * </ul>
   * Engine 只负责"把 byte[] 发出去"，不负责"byte[] 应该长什么样"。
   *
   * @param command 业务层的命令载荷（已经是 byte[]，可以是文本也可以是二进制）
   * @return 完整帧的字节数组（已经包含帧头/分隔符/CRC 等协议层附加字段）
   */
  byte[] encode(Command command);

  /**
   * 解码：把一次 TCP read 拿到的原始字节 → 切出若干个完整的 {@link Response} 帧。
   *
   * <h4>参数为什么是 (byte[] data, int offset, int length) 而不是 (byte[] data)</h4>
   * <p>Engine 的接收线程用的是固定大小的 readBuffer（8192 字节），一次 in.read() 可能读不满整个 buffer，
   * 所以只有 [offset, offset+length) 区间是有效数据。直接传子数组可以省一次 copy。
   *
   * <h4>为什么返回 List 而不是单个 Response</h4>
   * <p>因为**粘包**：TCP 是流式的，一次 read 可能拿到两帧甚至更多帧粘在一起。
   * decode 需要全部切出来一次性返回。同样，**拆包**时如果 buffer 里只有半截帧，返回空 List。
   *
   * <h4>decode 内部必须做什么</h4>
   * <pre>
   *  decode(data, off, len):
   *    ① 把 data[off..off+len) 追加到自己的跨包缓存里
   *    ② 从缓存里切出所有完整帧 → 构造 Response 对象
   *    ③ 把没切完的残余（半帧）留在缓存里
   *    ④ 返回切出来的完整帧列表（可能是空 List）
   * </pre>
   *
   * @param data   原始字节数组（通常是 Engine 的接收线程 readBuffer）
   * @param offset 有效数据在 data 中的起始偏移（inclusive）
   * @param length 有效数据长度
   * @return 本次成功切出的完整响应帧列表（空 List = 还在等更多字节凑帧）
   */
  List<Response> decode(byte[] data, int offset, int length);

  /**
   * 返回本协议使用的字符集。
   *
   * <p>Engine 层构造 {@link Response} 探针时，需要用这个 charset 把原始字节解码成可读文本，
   * 供 {@code ResponseMatcher} 做正则匹配。
   */
  Charset charset();

  /**
   * 清空协议内部的跨包缓存。
   *
   * <p>谁会调用？Engine 层的 {@code sendAndMatch} 在发送新命令前会调用，
   * 确保只匹配当前命令的响应，不被之前残留的半截帧干扰。
   *
   * <p>为什么这是协议层的职责而不是 Engine 层？因为跨包缓存是协议层自己的实现细节，
   * Engine 层不应该知道协议内部怎么存数据，只需要说一句"把缓存清干净"。
   */
  void clearCache();

  /**
   * 返回协议内部跨包缓存的当前字节快照（副本），便于调试和日志输出。
   *
   * <p>典型用途：当一次 TCP read 没有凑出完整帧时，Engine 层可以调用本方法
   * 把"半截帧"的字节内容打到日志里，帮助排查为什么匹配不上响应。
   *
   * <p>注意：返回的是副本，调用方修改不会影响协议内部状态。
   */
  byte[] getBufferData();

  /**
   * 从响应中提取业务载荷（body）。
   *
   * <p>这是一个便捷方法，语义等价于 {@code response.body()}，但放在 Protocol 接口上
   * 是为了强调"不同协议的 body 含义不同"这一语义：
   * <ul>
   *   <li>LineProtocol：去掉终止符后的文本字节</li>
   *   <li>LengthFieldProtocol：去掉 STX + LEN + CRC 后的 payload 字节</li>
   * </ul>
   *
   * @param response 协议解码产出的响应对象
   * @return 业务载荷字节（已剥离协议层帧头/分隔符/校验字段）
   */
  default byte[] body(Response response) {
    return response.body();
  }

}