package com.example.instrument.model;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * 响应类，表示从仪器接收到的响应数据。
 * 
 * Response 是不可变对象，包含完整的帧数据（包含协议头部和分隔符等）和业务数据体。
 * 主要用于：
 * <ul>
 *   <li>获取完整的原始帧数据（bytes()）</li>
 *   <li>获取业务数据体（body()），不包含协议相关字节</li>
 *   <li>获取文本表示（text()），用于日志和调试</li>
 * </ul>
 * 
 * <p>设计要点：</p>
 * <ul>
 *   <li>不可变性：创建后内容不可修改</li>
 *   <li>双数据存储：同时保存原始帧和业务数据</li>
 *   <li>接收时间戳：记录接收到的时间，用于计算延迟等</li>
 * </ul>
 * 
 * <p>使用示例：</p>
 * <pre>{@code
 * Response response = client.request(command, matcher, timeout);
 * 
 * // 获取原始帧数据
 * byte[] frame = response.bytes();
 * 
 * // 获取业务数据
 * byte[] body = response.body();
 * 
 * // 获取文本表示（UTF-8）
 * String text = response.text();
 * 
 * // 使用指定编码获取文本
 * String text2 = response.text(StandardCharsets.UTF_8);
 * 
 * // 获取接收时间戳
 * long receivedAt = response.receivedAtNanos();
 * }</pre>
 * 
 * @see Command
 * @see com.example.instrument.protocol.Protocol
 */
public final class Response {
    
    private final byte[] frame;
    private final byte[] body;
    private final long receivedAtNanos;

    /**
     * 构造函数，创建响应（使用当前时间戳）。
     *
     * @param frame 完整的原始帧数据，不能为空
     * @param body  业务数据体，不能为空
     */
    public Response(byte[] frame, byte[] body) {
        this(frame, body, System.nanoTime());
    }

    /**
     * 构造函数，创建响应（指定时间戳）。
     *
     * @param frame          完整的原始帧数据，不能为空
     * @param body           业务数据体，不能为空
     * @param receivedAtNanos 接收到响应的时间戳（纳秒）
     */
    public Response(byte[] frame, byte[] body, long receivedAtNanos) {
        this.frame = Objects.requireNonNull(frame).clone();
        this.body = Objects.requireNonNull(body).clone();
        this.receivedAtNanos = receivedAtNanos;
    }

    /**
     * 获取原始帧数据（克隆副本）。
     * 
     * 帧数据包含完整的协议数据，如头部、分隔符、校验和等。
     *
     * @return 原始帧数据的克隆副本
     */
    public byte[] bytes() { 
        return frame.clone(); 
    }

    /**
     * 获取业务数据体（克隆副本）。
     * 
     * 数据体不包含协议相关字节，仅包含实际业务数据。
     *
     * @return 业务数据体的克隆副本
     */
    public byte[] body() { 
        return body.clone(); 
    }

    /**
     * 获取帧数据长度。
     *
     * @return 原始帧长度（字节数）
     */
    public int length() { 
        return frame.length; 
    }

    /**
     * 获取接收到响应的时间戳（纳秒）。
     * 
     * 时间戳使用 System.nanoTime() 生成，可以用于计算延迟。
     *
     * @return 接收到响应的时间戳
     */
    public long receivedAtNanos() { 
        return receivedAtNanos; 
    }

    /**
     * 获取响应文本（使用 UTF-8 编码）。
     *
     * @return 响应文本
     */
    public String text() { 
        return text(StandardCharsets.UTF_8); 
    }

    /**
     * 获取响应文本（使用指定编码）。
     *
     * @param charset 字符编码，不能为空
     * @return 响应文本
     * @throws NullPointerException 如果 charset 为空
     */
    public String text(Charset charset) { 
        return new String(body, Objects.requireNonNull(charset)); 
    }

    /**
     * 返回响应的字符串表示（用于日志和调试）。
     *
     * @return 包含帧长度和数据体长度的字符串
     */
    @Override 
    public String toString() { 
        return "Response[length=" + frame.length + ", bodyLength=" + body.length + "]"; 
    }

    /**
     * 判断两个响应是否相等。
     * 
     * 比较基于帧数据和数据体内容。
     *
     * @param o 要比较的对象
     * @return 如果内容相同返回 true
     */
    @Override 
    public boolean equals(Object o) {
        return o instanceof Response r && Arrays.equals(frame, r.frame) && Arrays.equals(body, r.body);
    }

    /**
     * 返回响应的哈希码。
     *
     * @return 基于内容的哈希码
     */
    @Override 
    public int hashCode() { 
        return 31 * Arrays.hashCode(frame) + Arrays.hashCode(body); 
    }
}