package com.example.instrument.connection;

import com.example.instrument.config.ClientConfig;
import com.example.instrument.exception.ConnectionException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TCP 连接实现类，使用标准 Socket 实现 TCP 通信。
 *
 * <h2>一、Socket 通信工作原理</h2>
 * <p>Socket（套接字）是操作系统提供的网络通信端点抽象，是应用层与传输层（TCP/UDP）
 * 之间的编程接口。Socket 本质上是一个文件描述符（file descriptor），内核通过它
 * 将网络 I/O 统一为与文件 I/O 相同的读写模型，"一切皆文件"的 Unix 哲学在此充分体现。</p>
 *
 * <h3>1.1 OSI 七层模型与 TCP/IP 四层模型</h3>
 * <pre>
 *  OSI 模型              TCP/IP 模型           本类所处位置
 * ──────────           ────────────          ────────────
 * 应用层 (7)  ┐
 * 表示层 (6)  ├──────── 应用层                上层业务
 * 会话层 (5)  ┘                              (InstrumentClient)
 * ──────────           ────────────          ────────────
 * 传输层 (4) ────────── 传输层 (TCP/UDP)     java.net.Socket
 * ──────────           ────────────          (JDK 封装)
 * 网络层 (3) ────────── 网络层 (IP)          ────────────
 * ──────────           ────────────
 * 链路层 (2) ─┐
 * 物理层 (1) ─┴──────── 网络接口层            操作系统内核
 * </pre>
 * <p>本类处于<b>应用层与传输层的交界处</b>——向上为业务提供字节流的抽象，
 * 向下通过 Socket 系统调用委托操作系统内核完成 TCP 协议的细节。</p>
 *
 * <h3>1.2 TCP 协议核心机制</h3>
 * <p>本类基于 TCP 协议，其通信过程如下：</p>
 * <ol>
 *   <li><b>三次握手建立连接</b>：客户端调用 {@link #connect()} 触发 SYN → SYN+ACK → ACK，
 *       完成后连接进入 ESTABLISHED 状态，双方可进行全双工数据交换。</li>
 *   <li><b>数据传输</b>：TCP 提供面向连接、可靠的字节流服务。
 *       <ul>
 *         <li>发送方通过 {@link OutputStream} 写入数据，数据先进入内核发送缓冲区（SO_SNDBUF），
 *             由 TCP 协议栈负责分段、序号、重传、流量控制。</li>
 *         <li>接收方通过 {@link InputStream} 读取数据，数据从内核接收缓冲区（SO_RCVBUF）拷贝到用户空间。</li>
 *       </ul>
 *   </li>
 *   <li><b>四次挥手关闭连接</b>：调用 {@link #close()} 时发起 FIN 报文，双方独立关闭各自方向的数据流。</li>
 * </ol>
 *
 * <h3>1.3 TCP 粘包/拆包问题</h3>
 * <p>TCP 是<b>字节流</b>协议，没有消息边界概念。一次 write() 写入的数据可能在接收端
 * 合并成一个大包（粘包），也可能被拆分到多次 read() 中（拆包）。本类不处理此问题，
 * 粘包/拆包由上层协议层（{@code ProtocolEncoder/ProtocolDecoder}）通过
 * 长度域、分隔符或固定长度等策略解决。</p>
 *
 * <h2>二、本类的架构模型</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────┐
 * │                    上层业务                          │
 * │        (InstrumentClient / Receiver)                │
 * └──────────────────────┬──────────────────────────────┘
 *                        │ 调用 read()/write()/connect()
 *                        ▼
 * ┌─────────────────────────────────────────────────────┐
 * │              TcpConnection (本类)                    │
 * │  ┌─────────────────────────────────────────────┐    │
 * │  │ 生命周期锁 lifecycleLock (ReentrantLock)     │    │
 * │  │  保护 state / socket / input / output 变更   │    │
 * │  └─────────────────────────────────────────────┘    │
 * │  状态机: NEW → CONNECTING → CONNECTED               │
 * │                 ↓            ↓                      │
 * │           DISCONNECTED → CLOSED                     │
 * └──────────────────────┬──────────────────────────────┘
 *                        │ 封装
 *                        ▼
 * ┌─────────────────────────────────────────────────────┐
 * │            java.net.Socket (JDK)                     │
 * │   InputStream  /  OutputStream  /  SocketOptions    │
 * └──────────────────────┬──────────────────────────────┘
 *                        │ 系统调用 (syscall)
 *                        ▼
 * ┌─────────────────────────────────────────────────────┐
 * │           操作系统内核 TCP/IP 协议栈                  │
 * │  ┌──────┐  ┌──────┐  ┌──────┐  ┌──────────────┐   │
 * │  │ 流控  │  │ 拥塞 │  │ 重传 │  │ 发送/接收    │   │
 * │  │控制   │  │ 控制 │  │ 定时器│  │ 缓冲区管理   │   │
 * │  └──────┘  └──────┘  └──────┘  └──────────────┘   │
 * └──────────────────────┬──────────────────────────────┘
 *                        │
 *                        ▼
 *                   物理网络 (NIC)
 * </pre>
 *
 * <h2>三、I/O 模型与编程范式</h2>
 *
 * <h3>3.1 Java 四种 I/O 模型对比</h3>
 * <table>
 *   <tr><th>模型</th><th>API</th><th>阻塞</th><th>线程模型</th><th>适用场景</th></tr>
 *   <tr><td>BIO</td><td>java.io / java.net.Socket</td><td>是</td><td>1连接1线程</td>
 *       <td>低并发、短连接（本类采用）</td></tr>
 *   <tr><td>NIO</td><td>java.nio.channels</td><td>否</td><td>Selector + 少量线程</td>
 *       <td>高并发、长连接</td></tr>
 *   <tr><td>AIO</td><td>java.nio.channels.Asynchronous</td><td>否</td><td>回调驱动</td>
 *       <td>高吞吐文件 I/O（Linux 下不推荐）</td></tr>
 *   <tr><td>Netty</td><td>第三方框架</td><td>否</td><td>Reactor 多线程</td>
 *       <td>工业级高并发网络应用</td></tr>
 * </table>
 *
 * <h3>3.2 本类的 I/O 范式</h3>
 * <p>本类采用<b>阻塞式同步 I/O（BIO）</b>范式：</p>
 * <ul>
 *   <li>{@link #connect()} 阻塞直到连接建立或超时。</li>
 *   <li>{@link #read(byte[])} 阻塞直到有数据可读、流结束或抛出异常。</li>
 *   <li>{@link #write(byte[])} 将数据写入内核缓冲区后返回，底层异步发送。</li>
 * </ul>
 * <p>该范式适合仪器通信这类"请求-响应"型、并发度低的场景。上层通过独立的
 * {@code Receiver} 线程专门负责读取，避免阻塞业务线程，从而实现"单连接 + 读写分离"模型。</p>
 *
 * <h2>四、Socket 选项完整说明</h2>
 * <p>本类通过 {@link Socket} 的 setter 方法配置以下选项。这些选项底层对应
 * setsockopt() 系统调用，在内核层面影响 TCP 协议栈的行为：</p>
 * <ul>
 *   <li><b>TCP_NODELAY</b>：禁用 Nagle 算法。Nagle 算法会将小数据包合并发送以减少网络开销，
 *       但会增加延迟。仪器通信通常报文短小且要求实时响应，故默认开启。底层对应 TCP_NODELAY。</li>
 *   <li><b>SO_KEEPALIVE</b>：启用 TCP keepalive。操作系统会周期性发送探测报文
 *       （默认间隔约 2 小时），检测对端是否存活，避免连接"假死"。在 Windows 上可通过
 *       注册表调整间隔；Linux 上可通过 tcp_keepalive_time 等 sysctl 参数配置。</li>
 *   <li><b>SO_RCVBUF</b>：接收缓冲区大小（字节）。内核为每个 Socket 分配的接收内存空间。
 *       过小会影响吞吐，过大浪费内存。操作系统通常会在设置值的基础上翻倍。</li>
 *   <li><b>SO_TIMEOUT</b>：读超时时间（毫秒）。{@code read()} 阻塞超过此时长抛出
 *       {@link java.net.SocketTimeoutException}，0 表示无限等待。注意：此超时仅影响
 *       read()，不影响 write()。</li>
 *   <li><b>SO_REUSEADDR</b>（推荐添加）：允许 TIME_WAIT 状态的端口被立即重用，
 *       对频繁断开重连的客户端很有价值。</li>
 *   <li><b>SO_LINGER</b>（可选）：控制 close() 时底层 Socket 的行为。
 *       <ul>
 *         <li>默认（linger = -1）：close() 立即返回，内核继续发送缓冲区中的残留数据。</li>
 *         <li>linger = 0：close() 立即返回并丢弃缓冲区数据，发送 RST 而非 FIN。</li>
 *         <li>linger &gt; 0：close() 阻塞最多 linger 秒，等待数据发送完毕。</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <h2>五、状态机详细说明</h2>
 * <p>连接生命周期通过 {@link ConnectionState} 枚举管理，状态转换由
 * {@link #lifecycleLock} 保护：</p>
 * <pre>
 *                    ┌── CONNECTING ──┐
 *                    │  (connect())   │
 *                    ▼                ▼
 *   NEW ──► CONNECTING ──成功──► CONNECTED
 *    │          │                    │
 *    │      失败/断开           异常/断开
 *    │          ▼                    ▼
 *    │      DISCONNECTED ◄─── DISCONNECTED
 *    │          │
 *    │       close()
 *    │          ▼
 *    │      CLOSING ──► CLOSED (终态，不可逆)
 *    │
 *    └──── close() ──► CLOSING ──► CLOSED
 * </pre>
 * <ul>
 *   <li><b>NEW</b>：对象刚创建，尚未建立任何连接。该状态非常短暂，通常构造后立即调用 connect()。</li>
 *   <li><b>CONNECTING</b>：正在执行 TCP 三次握手。如果并发调用 connect() 会抛出异常。</li>
 *   <li><b>CONNECTED</b>：连接已建立，可以进行数据读写。isConnected() 同时检查
 *       Socket.isConnected() 和 !Socket.isClosed()，因为仅靠状态标志不足以反映真实网络状态。</li>
 *   <li><b>DISCONNECTED</b>：连接已断开但对象未被销毁，可通过 connect() 重新连接。
 *       与 CLOSED 的区别在于：DISCONNECTED 允许重连，CLOSED 是终态。</li>
 *   <li><b>CLOSING</b>：正在执行清理操作，确保幂等关闭。</li>
 *   <li><b>CLOSED</b>：终态，所有资源已释放，任何操作均抛出异常。</li>
 * </ul>
 *
 * <h2>六、线程安全约定</h2>
 * <ul>
 *   <li>生命周期方法（connect/close/disconnect）由 {@code lifecycleLock} 串行化，
 *       保证状态转换的原子性。</li>
 *   <li>{@code socket/input/output/state} 声明为 {@code volatile}，保证读写线程间的
 *       可见性（happens-before 语义）。读线程通过 volatile 读获取写线程最近的赋值。</li>
 *   <li>由于 TCP 流本身不支持并发读/并发写，上层应保证：同一时刻只有一个线程读、
 *       一个线程写（读写可并行）。本类不对此做额外同步。</li>
 * </ul>
 *
 * <h2>七、Java Socket 相关设计模式</h2>
 * <ul>
 *   <li><b>Decorator（装饰器）</b>：{@code Socket.getInputStream()} 返回的流可以通过
 *       {@code BufferedInputStream/DataInputStream} 等层层装饰，增加缓冲、类型化读取等能力。</li>
 *   <li><b>Factory Method（工厂方法）</b>：{@code new Socket()} 和
 *       {@code SocketChannel.open().socket()} 是创建 Socket 的两种工厂路径。</li>
 *   <li><b>Facade（外观）</b>：本类本身是 Facade——将复杂的 Socket API、
 *       状态管理和锁协调封装为简洁的 Connection 接口。</li>
 *   <li><b>State（状态）</b>：通过 ConnectionState 枚举实现有限状态机，控制合法转换，
 *       避免非法操作（如 CLOSED 状态下调用 connect()）。</li>
 * </ul>
 *
 * @see Connection
 * @see ClientConfig
 * @see ConnectionState
 * @see java.net.Socket
 * @see java.net.SocketOptions
 */
public final class TcpConnection implements Connection {

    private static final Logger log = LoggerFactory.getLogger(TcpConnection.class);

    private final InetSocketAddress address;
    private final ClientConfig config;
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    private volatile Socket socket;
    private volatile InputStream input;
    private volatile OutputStream output;
    private volatile ConnectionState state = ConnectionState.NEW;

    /**
     * 构造函数，创建 TCP 连接实例。
     *
     * @param address 服务器地址，不能为空
     * @param config  客户端配置，不能为空
     * @throws NullPointerException 如果任何参数为空
     */
    public TcpConnection(InetSocketAddress address, ClientConfig config) {
        this.address = Objects.requireNonNull(address);
        this.config = Objects.requireNonNull(config);
    }

    /**
     * 建立 TCP 连接。
     *
     * <h3>底层流程</h3>
     * <ol>
     *   <li>创建 {@link Socket} 实例——仅分配内核数据结构，尚未发起网络通信。</li>
     *   <li>配置 Socket 选项（必须在 connect 前完成，因为部分选项影响握手行为）。</li>
     *   <li>调用 {@link Socket#connect(java.net.SocketAddress, int)} 触发 TCP 三次握手。
     *       若超时时间内未完成握手，抛出 SocketTimeoutException。</li>
     *   <li>连接成功后，缓存 {@link InputStream} 和 {@link OutputStream}，
     *       后续 read/write 直接使用缓存引用，避免每次调用 getInputStream/getOutputStream
     *       的同步开销。</li>
     * </ol>
     *
     * <h3>状态转换</h3>
     * <pre>
     * 调用前               调用后
     * NEW           →     CONNECTING → CONNECTED（成功）
     * DISCONNECTED  →     CONNECTING → CONNECTED（成功，重连）
     * CONNECTED     →     直接返回（幂等）
     * CONNECTING    →     抛出 ConnectionException（防重入）
     * CLOSED        →     抛出 ConnectionException
     * </pre>
     *
     * @throws ConnectionException 如果连接失败、连接已关闭或正在连接中
     */
    @Override
    public void connect() {
        lifecycleLock.lock();
        try {
            if (state == ConnectionState.CLOSED) {
                throw new ConnectionException("connection is closed");
            }
            if (state == ConnectionState.CONNECTING) {
                throw new ConnectionException("connection is in progress");
            }
            if (state == ConnectionState.CONNECTED) {
                log.debug("already connected to {}", address);
                return;
            }

            state = ConnectionState.CONNECTING;
            log.debug("connecting to {}...", address);

            Socket s = new Socket();
            try {
                s.setTcpNoDelay(config.tcpNoDelay());
                s.setKeepAlive(config.keepAlive());
                s.setReceiveBufferSize(config.receiveBufferSize());
                s.setSoTimeout(config.socketReadTimeoutMillis());
                s.setReuseAddress(true);

                long timeoutMillis = Math.min((long) Integer.MAX_VALUE, config.connectTimeout().toMillis());
                s.connect(address, (int) timeoutMillis);

                input = s.getInputStream();
                output = s.getOutputStream();
                socket = s;
                state = ConnectionState.CONNECTED;
                log.info("connected to {} (tcpNoDelay={}, keepAlive={}, readTimeout={}ms, rcvBuf={})",
                    address, config.tcpNoDelay(), config.keepAlive(),
                    config.socketReadTimeoutMillis(), config.receiveBufferSize());
            } catch (IOException e) {
                closeQuietly(s);
                state = ConnectionState.DISCONNECTED;
                log.warn("connect to {} failed: {}", address, e.getMessage());
                throw new ConnectionException("connect failed: " + address, e);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 从连接读取数据。
     *
     * <h3>阻塞语义</h3>
     * <p>此方法会<b>阻塞</b>当前线程直到以下任一条件满足：</p>
     * <ol>
     *   <li>至少 1 字节数据到达 → 返回实际读取的字节数（1 到 buffer.length）。</li>
     *   <li>对端关闭连接（发送 FIN） → 返回 -1。</li>
     *   <li>读超时（SO_TIMEOUT > 0 且无数据到达达到超时） → 抛出
     *       {@link java.net.SocketTimeoutException}。</li>
     *   <li>发生其他 I/O 错误（连接重置、网络不可达等） → 抛出 IOException。</li>
     * </ol>
     *
     * <h3>与 POSIX read() 的区别</h3>
     * <p>底层对应 POSIX {@code read(fd, buf, count)} 系统调用。关键差别：</p>
     * <ul>
     *   <li>POSIX read() 的返回值受 TCP 窗口、内核缓冲区等因素影响，
     *       <b>不保证一次读完所有可用数据</b>。例如发送方 write(100字节)，
     *       接收方一次 read() 可能只读到 30 字节。</li>
     *   <li>返回 0 仅在底层 Socket 设置了 SO_TIMEOUT 且超时时发生，
     *       但 Java 的 Socket 实现会将超时转为 SocketTimeoutException，
     *       实际场景中几乎不会返回 0。</li>
     *   <li>返回 -1 表示对端已调用 shutdownOutput() 或 close()。<b>注意</b>：此时
     *       本地仍可写入数据（TCP 半关闭状态），直到本地也调用 close()。</li>
     * </ul>
     *
     * <h3>线程安全说明</h3>
     * <p>通过局部变量 {@code in = input} 捕获流引用，避免与 close() 的竞态：
     * 即使 close() 将 input 置为 null，本方法仍然持有之前获取的有效引用，
     * 实际 I/O 异常由底层 Socket 的关闭语义保证。</p>
     *
     * @param buffer 存储读取数据的缓冲区，长度必须大于 0
     * @return 读取的字节数，-1 表示到达流的末尾（对端关闭）
     * @throws java.net.SocketTimeoutException 如果读超时（可重试）
     * @throws IOException 如果读取失败或未连接
     */
    @Override
    public int read(byte[] buffer) throws IOException {
        InputStream in = input;
        if (in == null || !isConnected()) {
            throw new IOException("not connected");
        }
        return in.read(buffer);
    }

    /**
     * 将数据写入连接并刷新。
     *
     * <h3>写入语义</h3>
     * <p>调用 {@link OutputStream#write(byte[])} 将数据从用户空间拷贝到内核的
     * <b>Socket 发送缓冲区（SO_SNDBUF）</b>，随后调用 {@link OutputStream#flush()}
     * 尝试立即触发发送。实际数据何时到达网络由 TCP 协议栈决定，write() 返回
     * 仅表示数据已进入内核缓冲区。</p>
     *
     * <h3>write 阻塞条件</h3>
     * <p>Socket 输出流通常<b>不设置超时</b>（SO_TIMEOUT 仅影响 read），write 在以下
     * 情况会阻塞：</p>
     * <ul>
     *   <li>发送缓冲区已满（接收方窗口为 0 或确认缓慢）→ 阻塞直到有空间。</li>
     *   <li>TCP 拥塞窗口已满 → 阻塞等待 ACK。</li>
     * </ul>
     *
     * <h3>为何自动 flush</h3>
     * <p>仪器通信协议通常报文短小，延迟敏感。write+flush 合并确保指令
     * 立即发出，避免数据滞留在应用层缓冲区。如果未来需要批量写入优化，
     * 可在 Connection 接口层面添加 writeWithoutFlush() 方法。</p>
     *
     * @param data 要写入的数据，不能为空
     * @throws IOException 如果写入失败或未连接
     */
    @Override
    public void write(byte[] data) throws IOException {
        OutputStream out = output;
        if (out == null || !isConnected()) {
            throw new IOException("not connected");
        }
        out.write(data);
        out.flush();
    }

    /**
     * 检查连接是否处于已连接状态。
     *
     * <h3>多层校验</h3>
     * <p>仅靠逻辑状态标志（{@code state == CONNECTED}）<b>不足以</b>反映真实网络连接状态。
     * 例如：对端异常断网但未发送 FIN（拔网线、断电），TCP 层面可能等待数分钟才会
     * 发现（取决于 SO_KEEPALIVE 和 TCP 重传超时）。</p>
     *
     * <p>因此本方法进行了多层检查：</p>
     * <ol>
     *   <li>{@code state == CONNECTED} — 逻辑状态（最快，但可能滞后于真实网络状态）。</li>
     *   <li>{@code s != null} — Socket 对象是否存在。</li>
     *   <li>{@code s.isConnected()} — Socket 是否执行过 connect 且未 close。</li>
     *   <li>{@code !s.isClosed()} — 本地 Socket 是否已关闭。</li>
     *   <li>{@code input != null && output != null} — 双工通道流引用是否存在。</li>
     * </ol>
     *
     * <h3>双工通道（Full-Duplex）</h3>
     * <p>TCP 是<b>全双工</b>协议，读写通道可独立关闭（半关闭，TCP Half-Close）。
     * 本方法通过 {@code input != null && output != null} 确保读写流引用未被
     * disconnect/close 清理（此时流为 null），同时保证双工通信能力完整。</p>
     *
     * <h3>局限性</h3>
     * <p>即使全部检查通过，连接仍可能在下一次 read/write 时发现已断开。
     * TCP 是无状态检测协议——"连接已断开"只有通过实际 I/O 操作才能确认，
     * 这是 TCP 协议本身的固有特性。</p>
     *
     * @return 如果本地视角连接有效返回 true，不代表对端一定可达
     */
    @Override
    public boolean isConnected() {
        Socket s = socket;
        return state == ConnectionState.CONNECTED
            && s != null
            && s.isConnected()
            && !s.isClosed()
            && input != null
            && output != null;
    }

    /**
     * 获取当前连接状态。
     *
     * @return 连接状态枚举值
     */
    @Override
    public ConnectionState state() {
        return state;
    }

    /**
     * 彻底关闭连接并释放所有资源。
     *
     * <h3>TCP 四次挥手</h3>
     * <p>调用 {@link Socket#close()} 触发 TCP 连接终止的四次挥手过程：</p>
     * <ol>
     *   <li>主动关闭方发送 FIN，进入 FIN_WAIT_1。</li>
     *   <li>对端回复 ACK，主动关闭方进入 FIN_WAIT_2。</li>
     *   <li>对端发送 FIN，主动关闭方回复 ACK，进入 TIME_WAIT（约 2MSL）。</li>
     *   <li>TIME_WAIT 超时后连接彻底关闭。</li>
     * </ol>
     * <p><b>TIME_WAIT 的作用</b>：确保最后的 ACK 被对端收到（重传场景），
     * 同时让网络中延迟的旧报文自然消亡，避免被新连接误收。</p>
     *
     * <h3>与 disconnect() 的区别</h3>
     * <ul>
     *   <li>close() → CLOSED 终态，不可再 connect()。用于客户端整体销毁。</li>
     *   <li>disconnect() → DISCONNECTED，可重连。用于临时断开后重试。</li>
     * </ul>
     *
     * <h3>幂等性</h3>
     * <p>重复调用 close() 是安全的：已处于 CLOSED 状态时直接返回，不做任何操作。</p>
     */
    @Override
    public void close() {
        lifecycleLock.lock();
        try {
            if (state == ConnectionState.CLOSED) {
                return;
            }
            log.info("closing connection to {}...", address);
            state = ConnectionState.CLOSING;
            closeQuietly(socket);
            socket = null;
            input = null;
            output = null;
            state = ConnectionState.CLOSED;
            log.info("connection to {} closed", address);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 断开连接（但不释放资源，允许重连）。
     *
     * 与 close() 不同，disconnect() 之后可以再次调用 connect() 重连。
     */
    public void disconnect() {
        lifecycleLock.lock();
        try {
            if (state == ConnectionState.CLOSED) {
                return;
            }
            log.debug("disconnecting from {}...", address);
            closeQuietly(socket);
            socket = null;
            input = null;
            output = null;
            state = ConnectionState.DISCONNECTED;
            log.debug("disconnected from {}", address);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 安全关闭 Socket，忽略可能的异常。
     *
     * @param s 要关闭的 Socket
     */
    private static void closeQuietly(Socket s) {
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * 返回连接的文字描述。
     *
     * @return 包含地址信息的字符串
     */
    @Override
    public String toString() {
        return "TcpConnection[" + address + "]";
    }
}