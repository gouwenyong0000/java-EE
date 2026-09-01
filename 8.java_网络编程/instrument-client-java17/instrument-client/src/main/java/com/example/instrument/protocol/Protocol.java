package com.example.instrument.protocol;

import com.example.instrument.model.Command;
import com.example.instrument.model.Response;

import java.nio.charset.Charset;
import java.util.List;

/**
 * 协议层接口。
 *
 * <p>负责定义如何把命令编码成字节流，以及如何从 TCP 读取到的原始字节中还原出完整的响应帧。
 * 具体实现可以是文本协议（如 CRLF）或长度字段协议（如二进制帧）。
 */
public interface Protocol {
  /**
   * 将命令对象编码成可直接写入 socket 的字节数组。
   * 这是发送请求前的核心步骤，必须与远端协议格式完全一致。
   */
  byte[] encode(Command command);

  /**
   * 将接收到的字节流交给协议解码器处理。
   * 一次读取可能产生 0 到 N 个完整帧，因此返回值是列表而不是单一响应。
   */
  List<Response> decode(byte[] data, int offset, int length);

  /**
   * 返回当前协议使用的字符集，便于对响应文本和命令编码统一处理。
   */
  Charset charset();
}
