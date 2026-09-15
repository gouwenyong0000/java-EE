package com.example.instrument.connection;

import com.example.instrument.api.InstrumentClient;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.core.InstrumentClientImpl;
import com.example.instrument.protocol.Protocol;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多连接管理器，统一管理多个 InstrumentClient 实例的生命周期。
 *
 * <p>使用 UUID 作为每个连接的唯一标识符，支持连接的创建、查询、删除和批量关闭。
 * 底层使用 ConcurrentHashMap 保证线程安全，适用于多线程并发访问场景。
 *
 * <p>典型使用场景：
 * <pre>{@code
 * try (ConnectionManager manager = new ConnectionManager()) {
 *     UUID id1 = manager.add(addr1, protocol, config);
 *     UUID id2 = manager.add(addr2, protocol, config);
 *
 *     InstrumentClient client1 = manager.get(id1);
 *     client1.connect();
 *
 *     manager.remove(id2);  // 自动关闭连接
 * }  // try-with-resources 自动关闭所有连接
 * }</pre>
 */
public final class ConnectionManager implements AutoCloseable {

  /** 存储所有客户端的并发映射表，key 为 UUID，value 为 InstrumentClient 实例 */
  private final Map<UUID, InstrumentClient> clients = new ConcurrentHashMap<>();
  private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

  /**
   * 创建新的仪器客户端连接并注册到管理器中。
   *
   * @param address  目标仪器的网络地址（IP + 端口）
   * @param protocol 通信协议（如 LineProtocol、LengthFieldProtocol）
   * @param config   客户端配置（超时、缓冲区、重连策略等）
   * @return 新创建连接的唯一标识符 UUID，用于后续查询和管理
   */
  public UUID add(InetSocketAddress address, Protocol protocol, ClientConfig config) {
    UUID id = UUID.randomUUID();
    clients.put(id, new InstrumentClientImpl(address, protocol, config));
    log.info("added client id={} address={}", id, address);
    return id;
  }

  /**
   * 根据 UUID 获取客户端连接，如果不存在则抛出异常。
   *
   * <p>适用于必须确保连接存在的场景，调用方应捕获 NoSuchElementException 处理异常情况。
   *
   * @param id 连接的唯一标识符
   * @return 对应的 InstrumentClient 实例
   * @throws NoSuchElementException 当指定的 UUID 不存在时抛出
   */
  public InstrumentClient get(UUID id) {
    InstrumentClient c = clients.get(id);
    if (c == null) throw new NoSuchElementException("client not found: " + id);
    return c;
  }

  /**
   * 根据 UUID 查找客户端连接，返回 Optional 包装结果。
   *
   * <p>与 {@link #get(UUID)} 不同，此方法不会抛出异常，适用于连接可能不存在的场景。
   *
   * @param id 连接的唯一标识符
   * @return 包含 InstrumentClient 的 Optional，如果不存在则返回 Optional.empty()
   */
  public Optional<InstrumentClient> find(UUID id) {
    return Optional.ofNullable(clients.get(id));
  }

  /**
   * 移除指定的客户端连接并自动关闭资源。
   *
   * <p>移除操作是原子性的，如果连接不存在则静默忽略。
   * 关闭操作会释放底层 Socket、停止接收线程、取消所有 pending 请求。
   *
   * @param id 要移除的连接的唯一标识符
   */
  public void remove(UUID id) {
    InstrumentClient c = clients.remove(id);
    if (c != null) {
      log.debug("removing client id={}", id);
      c.close();
    }
  }

  /**
   * 获取当前所有连接的唯一标识符集合。
   *
   * <p>返回的是快照副本，不会受到后续添加/删除操作的影响。
   * 适用于遍历、统计或展示当前活跃连接。
   *
   * @return 不可变的 UUID 集合，包含所有已注册连接的标识符
   */
  public Set<UUID> ids() {
    return Set.copyOf(clients.keySet());
  }

  /**
   * 关闭管理器中的所有连接并清空映射表。
   *
   * <p>此方法会遍历所有客户端并调用其 close() 方法，释放所有底层资源。
   * 支持 try-with-resources 语法，确保资源正确释放。
   *
   * <p>注意：单个连接关闭失败不会影响其他连接的关闭操作。
   */
  @Override
  public void close() {
    log.info("closing ConnectionManager with {} client(s)", clients.size());
    clients.values().forEach(InstrumentClient::close);
    clients.clear();
  }
}