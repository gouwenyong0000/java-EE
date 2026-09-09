package com.example.instrument.engine;

import com.example.instrument.api.BlockingDataListener;
import com.example.instrument.api.ClientITF;
import com.example.instrument.api.DataListener;
import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.model.Command;
import com.example.instrument.model.Response;
import com.example.instrument.protocol.Protocol;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 多连接管理器 —— 用 UUID 标识每个物理连接，上层通过 UUID 即可下发命令。
 *
 * <h3>为什么需要这一层</h3>
 * <p>SocketClientITFImpl 只管**一个** TCP 连接。如果业务场景需要同时连多台仪器（比如
 * 一台信号源 + 一台示波器 + 一台频谱仪），就需要手动管理一堆 ClientITF 实例 ——
 * 创建、存起来、记得哪个对应哪个 UUID、不要漏 disconnect。这个管理器帮你做了。
 *
 * <h3>在四层架构中的位置</h3>
 * <pre>
 *   业务层 Demo / App   ← 你只需要 UUID，不用关心 ClientITF 实例存在哪
 *      │
 *   ┌──┴────────────────────┐
 *   │  本类 SocketConnectionManager  │  ← 新增：多连接编排层
 *   └──┬────────────────────┘
 *      │
 *   SocketClientITFImpl × N     ← 每个 UUID 对应一个底层 ClientITF
 *      │
 *   Protocol / Socket / TCP
 * </pre>
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>{@link ConcurrentHashMap} 存储连接：多线程同时 create/send/disconnect 不会丢数据</li>
 *   <li>shutdown 状态用 {@link AtomicBoolean}：一旦 shutdown，再 createConnection 会抛异常</li>
 *   <li>每个底层 ClientITF 自己也有完整的线程安全实现，所以管理器不需要额外加锁</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>
 *   try (var mgr = new SocketConnectionManager()) {
 *     // 同时连三台仪器
 *     UUID scope   = mgr.createConnection("192.168.1.10", 5025, new LineProtocol());
 *     UUID gen     = mgr.createConnection("192.168.1.20", 5025, new LineProtocol());
 *     UUID binary  = mgr.createConnection("192.168.1.30", 9000, new LengthFieldProtocol());
 *
 *     // 按 UUID 直接发命令（底层自动 init + connect + send）
 *     Response v = mgr.sendAndRegex(scope, "MEAS:VOLT?", "VOLT:[0-9.]+\\r\\n", Duration.ofSeconds(3));
 *     mgr.sendAndRegex(gen, "OUTP:ON", "OK\\r\\n");
 *
 *     // 批量断开：try-with-resources 退出时自动 disconnectAll()
 *   }
 * </pre>
 */
public class SocketConnectionManager implements AutoCloseable {

  /** 所有活跃连接，key = 业务层 UUID，value = 底层 ClientITF。 */
  private final ConcurrentMap<UUID, ClientITF> connections = new ConcurrentHashMap<>();

  /** 全局 shutdown 开关。true 时不再允许创建新连接。 */
  private final AtomicBoolean shutdown = new AtomicBoolean(false);

  // ==================================================================
  //  创建连接
  // ==================================================================

  /**
   * 创建一个新的 TCP 连接，自动 init + connect，并返回唯一标识 UUID。
   *
   * <p>这是最常用的便捷入口 —— 一行代码连一台仪器。
   * 如果 connect 失败（网络不通、服务端没起等），会抛 {@link SocketClientException}，
   * 此时不会有"半成品"连接留在 map 里。
   *
   * @param host     目标主机
   * @param port     目标端口
   * @param protocol 协议实现（LineProtocol / LengthFieldProtocol / 自定义）
   * @return 该连接的唯一 UUID，后续所有操作都用它来定位
   * @throws IllegalStateException  如果管理器已经 shutdown
   * @throws SocketClientException 如果 TCP 连接建立失败
   */
  public UUID createConnection(String host, int port, Protocol protocol) {
    return createConnection(SocketClientConfig.defaults(host, port), protocol);
  }

  /**
   * 创建连接 —— 完整版，支持自定义 config（超时、重连次数、socket 读超时等）。
   *
   * <p>流程：
   * <pre>
   *   ① 检查 shutdown 开关 → 如果已关闭则拒绝创建
   *   ② new SocketClientITFImpl(config, protocol)
   *   ③ client.init()       → 启动后台接收线程
   *   ④ client.connect()    → 建立 TCP 连接（失败则清理，不存进 map）
   *   ⑤ UUID.randomUUID()   → 生成唯一标识
   *   ⑥ connections.put(id, client) → 纳入管理
   * </pre>
   *
   * @param config   连接配置（host、port、超时、重连等）
   * @param protocol 协议实现
   * @return 该连接的唯一 UUID
   */
  public UUID createConnection(SocketClientConfig config, Protocol protocol) {
    if (shutdown.get())
      throw new IllegalStateException("SocketConnectionManager is shutdown");

    ClientITF client = new SocketClientITFImpl(config, protocol);
    try {
      client.init();
      client.connect();
    } catch (RuntimeException e) {
      // 连接失败 → 清理，不要留半成品
      try { client.disconnect(); } catch (RuntimeException ignored) { /* NOP */ }
      throw e;
    }

    UUID id = UUID.randomUUID();
    ClientITF previous = connections.put(id, client);
    // ConcurrentHashMap.put 理论上 UUID 不会重复，但还是防御一下
    if (previous != null) {
      try { previous.disconnect(); } catch (RuntimeException ignored) { /* NOP */ }
    }
    return id;
  }

  // ==================================================================
  //  下发命令（核心入口）
  // ==================================================================

  /**
   * 发送字符串命令 + 正则匹配响应（最常用）。
   *
   * <p>内部会自动检查该 UUID 对应的连接是否还活着，断了会自动重连
   * （重连逻辑在 SocketClientITFImpl.ensureConnected 里）。
   *
   * @param id      连接 UUID
   * @param command 字符串命令（如 "MEAS:VOLT?"）
   * @param regex   期望响应的正则（如 "VOLT:[0-9.]+\\r\\n"）
   * @param timeout 超时；null 时用该连接 config 的 responseTimeout
   * @throws SocketClientException  如果超时、重连失败、正则不匹配等
   */
  public Response sendAndRegex(UUID id, String command, String regex, Duration timeout) {
    ClientITF client = requireConnection(id);
    Pattern compiled = Pattern.compile(regex, Pattern.DOTALL);
    return client.sendAndMatch(
        Command.of(command), resp -> compiled.matcher(resp.text()).matches(), timeout);
  }

  /** 等同于 {@code sendAndRegex(id, command, regex, null)}，使用连接 config 默认超时。 */
  public Response sendAndRegex(UUID id, String command, String regex) {
    return sendAndRegex(id, command, regex, null);
  }

  /**
   * 发送命令 + 自定义 matcher 匹配响应（高级用法）。
   *
   * @param id      连接 UUID
   * @param command 命令对象（支持二进制 payload）
   * @param matcher 自定义匹配逻辑（可以查状态码、字段值、字节长度等）
   * @param timeout 超时；null 时用 config 默认值
   */
  public Response sendAndMatch(UUID id, Command command, ResponseMatcher matcher, Duration timeout) {
    ClientITF client = requireConnection(id);
    return client.sendAndMatch(command, matcher, timeout);
  }

  /** 等同于 {@code sendAndMatch(id, command, matcher, null)}，使用 config 默认超时。 */
  public Response sendAndMatch(UUID id, Command command, ResponseMatcher matcher) {
    return sendAndMatch(id, command, matcher, null);
  }

  // ==================================================================
  //  监听器管理（双模式）
  // ==================================================================

  /** 给指定连接注册一个异步回调监听器。 */
  public void addListener(UUID id, DataListener listener) {
    requireConnection(id).addListener(listener);
  }

  /** 移除指定连接上的某个监听器。 */
  public void removeListener(UUID id, DataListener listener) {
    ClientITF client = connections.get(id);
    if (client != null) client.removeListener(listener);
  }

  /** 获取指定连接的阻塞式监听器（可以 take() 等待下一条数据）。 */
  public BlockingDataListener blockingListener(UUID id) {
    return requireConnection(id).blockingListener();
  }

  // ==================================================================
  //  连接状态 / 查询
  // ==================================================================

  /** 检查指定连接是否还活着（底层 socket.isConnected + 未关闭）。 */
  public boolean isConnected(UUID id) {
    ClientITF client = connections.get(id);
    return client != null && client.isConnected();
  }

  /**
   * 获取指定连接的底层 ClientITF（高级用法）。
   *
   * <p>什么时候需要直接拿到底层对象？比如：
   * <ul>
   *   <li>你想先 connect() 但不 send，等准备好再发</li>
   *   <li>你想手动调 connect() 触发一次重连</li>
   *   <li>你想直接操作 ClientITF 的其他 API</li>
   * </ul>
   *
   * @return 底层 ClientITF；如果该 UUID 不存在返回 null（不抛异常，方便判空）
   */
  public ClientITF getConnection(UUID id) {
    return connections.get(id);
  }

  /** 返回当前所有活跃连接的 UUID 集合（不可变快照）。 */
  public Set<UUID> activeConnections() {
    return Collections.unmodifiableSet(connections.keySet());
  }

  /** 返回当前管理的连接数量。 */
  public int size() {
    return connections.size();
  }

  // ==================================================================
  //  断开 / 清理
  // ==================================================================

  /**
   * 断开并移除指定连接。
   *
   * <p>断开后这个 UUID 就失效了，后续用同一个 UUID 再调用 sendAndRegex 等方法会抛
   * {@link IllegalArgumentException}。如果需要重新连，必须调 {@link #createConnection}
   * 获取一个**新的 UUID**。
   *
   * @param id 目标连接 UUID
   */
  public void disconnect(UUID id) {
    ClientITF removed = connections.remove(id);
    if (removed != null) {
      try { removed.disconnect(); } catch (RuntimeException ignored) { /* NOP */ }
    }
  }

  /**
   * 断开所有连接 + 关闭管理器。
   *
   * <p>调用后 {@link #shutdown} 置 true，再也不能创建新连接。
   * 适合在应用关闭时统一调用，或者通过 try-with-resources 自动触发。
   */
  public void disconnectAll() {
    shutdown.set(true);
    for (UUID id : new java.util.ArrayList<>(connections.keySet())) {
      disconnect(id);
    }
  }

  /** AutoCloseable 实现 —— 等价于 {@link #disconnectAll()}。 */
  @Override
  public void close() {
    disconnectAll();
  }

  // ==================================================================
  //  内部工具
  // ==================================================================

  /**
   * 按 UUID 获取连接，不存在则抛 IllegalArgumentException。
   * 是 sendAndRegex / addListener / blockingListener 等所有需要"连接必须存在"的方法的前置校验。
   */
  private ClientITF requireConnection(UUID id) {
    if (id == null) throw new IllegalArgumentException("connection uuid is null");
    ClientITF client = connections.get(id);
    if (client == null) {
      throw new IllegalArgumentException(
          "no connection registered for uuid=" + id
              + "; call createConnection() first, or maybe disconnect() was already called");
    }
    return client;
  }
}