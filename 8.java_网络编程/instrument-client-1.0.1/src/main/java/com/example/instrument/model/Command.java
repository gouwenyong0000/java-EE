package com.example.instrument.model;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * 命令类，表示要发送给仪器的命令数据。
 * 
 * Command 是不可变对象，内部存储命令的字节数据。
 * 提供了多种创建方式：从字节数组、字符串等。
 * 
 * <p>设计要点：</p>
 * <ul>
 *   <li>不可变性：创建后内容不可修改，保证线程安全</li>
 *   <li>字节存储：内部使用 byte[] 存储，适应各种协议</li>
 *   <li>克隆保护：返回的字节数组都是克隆副本，防止外部修改影响内部状态</li>
 * </ul>
 * 
 * <p>使用示例：</p>
 * <pre>{@code
 * // 从字符串创建（使用 UTF-8 编码）
 * Command cmd1 = Command.text("GET_DATA");
 * 
 * // 从字符串创建（使用指定编码）
 * Command cmd2 = Command.text("GET_DATA", StandardCharsets.UTF_8);
 * 
 * // 从字节数组创建
 * Command cmd3 = Command.of(new byte[]{0x01, 0x02, 0x03});
 * 
 * // 获取命令内容
 * byte[] bytes = cmd.bytes();
 * String text = cmd.text();
 * }</pre>
 * 
 * @see Response
 * @see com.example.instrument.protocol.Protocol
 */
public final class Command {
    
    private final byte[] payload;

    /**
     * 私有构造函数，从字节数组创建命令。
     *
     * @param payload 命令数据，会被克隆存储
     */
    private Command(byte[] payload) {
        this.payload = payload.clone();
    }

    /**
     * 从字节数组创建命令。
     *
     * @param payload 命令数据，不能为空
     * @return 创建的 Command 实例
     * @throws NullPointerException 如果 payload 为空
     */
    public static Command of(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        return new Command(payload);
    }

    /**
     * 从字符串创建命令（使用 UTF-8 编码）。
     *
     * @param text 命令文本，不能为空
     * @return 创建的 Command 实例
     * @throws NullPointerException 如果 text 为空
     */
    public static Command text(String text) {
        return text(text, StandardCharsets.UTF_8);
    }

    /**
     * 从字符串创建命令（使用指定编码）。
     *
     * @param text    命令文本，不能为空
     * @param charset 字符编码，不能为空
     * @return 创建的 Command 实例
     * @throws NullPointerException 如果 text 或 charset 为空
     */
    public static Command text(String text, Charset charset) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(charset, "charset");
        return new Command(text.getBytes(charset));
    }

    /**
     * 获取命令的字节数据（克隆副本）。
     *
     * @return 命令数据的克隆副本
     */
    public byte[] bytes() { 
        return payload.clone(); 
    }

    /**
     * 获取命令的字节长度。
     *
     * @return 命令数据长度
     */
    public int length() { 
        return payload.length; 
    }

    /**
     * 返回命令的字符串表示（用于日志和调试）。
     *
     * @return 包含命令长度信息的字符串
     */
    @Override 
    public String toString() { 
        return "Command[length=" + payload.length + "]"; 
    }

    /**
     * 判断两个命令是否相等。
     * 
     * 比较基于命令的字节内容。
     *
     * @param o 要比较的对象
     * @return 如果内容相同返回 true
     */
    @Override 
    public boolean equals(Object o) {
        return o instanceof Command c && Arrays.equals(payload, c.payload);
    }

    /**
     * 返回命令的哈希码。
     *
     * @return 基于内容的哈希码
     */
    @Override 
    public int hashCode() { 
        return Arrays.hashCode(payload); 
    }
}