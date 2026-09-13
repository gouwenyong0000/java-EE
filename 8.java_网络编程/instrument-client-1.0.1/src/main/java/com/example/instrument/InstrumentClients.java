package com.example.instrument;

import com.example.instrument.api.InstrumentClient;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.core.InstrumentClientImpl;
import com.example.instrument.protocol.Protocol;
import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * InstrumentClient 工厂类，提供便捷的 TCP 客户端创建方法。
 *
 * 该类采用静态工厂模式，负责根据传入的协议和配置创建相应的 InstrumentClient 实例。
 * 所有方法都会对输入参数进行非空校验，确保创建的客户端配置正确。
 *
 * @see InstrumentClient
 * @see ClientConfig
 * @see Protocol
 */
public final class InstrumentClients {

    private InstrumentClients() {}

    /**
     * 根据完整的 Socket 地址、协议和配置创建 TCP 客户端实例。
     *
     * @param address  服务器的 IP 地址和端口号，不能为空
     * @param protocol 通信协议，用于编码请求和解码响应，不能为空
     * @param config   客户端配置，包含超时、重连等参数，不能为空
     * @return 配置好的 InstrumentClient 实例
     * @throws NullPointerException 如果任何一个参数为 null
     */
    public static InstrumentClient tcp(InetSocketAddress address, Protocol protocol, ClientConfig config) {
        return new InstrumentClientImpl(
            Objects.requireNonNull(address),
            Objects.requireNonNull(protocol),
            Objects.requireNonNull(config)
        );
    }

    /**
     * 根据主机名、端口号、协议和配置创建 TCP 客户端实例。
     *
     * 这是创建客户端的便捷方法，适用于只知道主机名和端口号的场景。
     *
     * @param host     服务器主机名或 IP 地址，不能为空
     * @param port     服务器端口号，有效范围 1-65535
     * @param protocol 通信协议，用于编码请求和解码响应，不能为空
     * @param config   客户端配置，包含超时、重连等参数，不能为空
     * @return 配置好的 InstrumentClient 实例
     * @throws NullPointerException 如果 host 或 protocol 或 config 为 null
     */
    public static InstrumentClient tcp(String host, int port, Protocol protocol, ClientConfig config) {
        return tcp(new InetSocketAddress(host, port), protocol, config);
    }
}