package com.example.instrument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 多功能测试服务器 —— 模拟真实仪器，支持多端口、多协议、可配置的异常场景。
 *
 * <h3>设计目标</h3>
 * <p>这个服务器不是简单的 echo，而是专门为了测试客户端的**各种边界情况**而设计的：
 * <ul>
 *   <li>✅ 正常的 SCPI 文本请求-响应（LineProtocol，端口 5025）</li>
 *   <li>✅ 二进制 LengthField 帧请求-响应（LengthFieldProtocol，端口 9000）</li>
 *   <li>✅ 粘包场景：服务端一次写多帧，测试客户端能否正确切分</li>
 *   <li>✅ 拆包场景：服务端把一帧拆成多次小 write，测试客户端能否重组</li>
 *   <li>✅ 延迟响应：模拟慢仪器，测试客户端超时/等待机制</li>
 *   <li>✅ 自动推送：服务端主动发数据，测试客户端异步监听</li>
 *   <li>✅ 断连模拟：服务端处理完一个命令后主动关 socket，测试客户端重连</li>
 *   <li>✅ 同时多端口：配合 SocketConnectionManager 测试多连接管理</li>
 * </ul>
 *
 * <h3>启动方式</h3>
 * <pre>
 *   // 启动全部端口（推荐）
 *   TestServer.startAll();
 *
 *   // 或者单独启动
 *   TestServer.startLineProtocol(5025);
 *   TestServer.startLengthField(9000);
 *
 *   // 应用退出时关闭所有
 *   TestServer.shutdownAll();
 * </pre>
 *
 * <h3>测试命令速查（LineProtocol 端口 5025）</h3>
 * <pre>
 *   MEAS:VOLT?        → 正常返回 "VOLT:1.234\r\n"
 *   MEAS:VOLT? #delay → 延迟 3s 后返回（测试 responseTimeout）
 *   PING              → "PONG\r\n"
 *   PUSH              → 服务端主动推送 3 条异步数据（测试 addListener）
 *   STICKY            → 服务端一次写 3 帧粘在一起（测试粘包处理）
 *   FRAG              → 服务端把一帧拆成 5 次小 write（测试拆包处理）
 *   DISCONNECT        → 处理完后主动关闭 socket（测试自动重连）
 *   BYE               → 服务端关闭（只影响当前连接）
 * </pre>
 */
public final class TestServer {

  // ====================================================================
  //  端口常量（默认端口，可被覆盖）
  // ====================================================================

  /** LineProtocol（CRLF 文本 SCPI）默认端口。 */
  public static final int LINE_PORT = 5025;
  /** LengthFieldProtocol（二进制帧）默认端口。 */
  public static final int BINARY_PORT = 9000;

  // ====================================================================
  //  全局注册表 —— 跟踪所有活跃的 ServerSocket，支持一次性 shutdownAll
  // ====================================================================

  private static final Map<Integer, ServerSocket> REGISTRY = new LinkedHashMap<>();
  private static final List<Thread> WORKER_THREADS = new ArrayList<>();
  private static final AtomicBoolean GLOBAL_RUNNING = new AtomicBoolean(true);

  private TestServer() {}

  // ====================================================================
  //  启动入口
  // ====================================================================

  /**
   * 启动所有协议端口（5025 文本 + 9000 二进制），阻塞式。
   * 适合 main 方法直接跑，或测试里启动后通过 shutdownAll 关闭。
   */
  public static void startAll() throws IOException {
    startLineProtocol(LINE_PORT);
    startLengthField(BINARY_PORT);
    System.out.println("[TestServer] All ports started.");
    System.out.println("[TestServer]   LineProtocol    → 127.0.0.1:" + LINE_PORT);
    System.out.println("[TestServer]   LengthField     → 127.0.0.1:" + BINARY_PORT);
    System.out.println("[TestServer]   Type 'EXIT' to stop...");

    // 阻塞主线程等退出指令
    try {
      byte[] buf = new byte[16];
      while (GLOBAL_RUNNING.get()) {
        Thread.sleep(500);
      }
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    } finally {
      shutdownAll();
    }
  }

  /** 启动 LineProtocol CRLF 文本服务端，非阻塞（后台线程）。 */
  public static void startLineProtocol(int port) throws IOException {
    ServerSocket ss = createServerSocket(port);
    Thread t = new Thread(() -> runLineLoop(ss), "TestServer-Line-" + port);
    t.setDaemon(true);
    t.start();
    WORKER_THREADS.add(t);
  }

  /** 启动 LengthFieldProtocol 二进制服务端，非阻塞（后台线程）。 */
  public static void startLengthField(int port) throws IOException {
    ServerSocket ss = createServerSocket(port);
    Thread t = new Thread(() -> runBinaryLoop(ss), "TestServer-Binary-" + port);
    t.setDaemon(true);
    t.start();
    WORKER_THREADS.add(t);
  }

  /**
   * 关闭所有已启动的服务端口。
   * 会遍历注册表关闭所有 ServerSocket，接受循环会抛 SocketException 并退出。
   */
  public static void shutdownAll() {
    GLOBAL_RUNNING.set(false);
    synchronized (REGISTRY) {
      for (ServerSocket ss : REGISTRY.values()) {
        try { ss.close(); } catch (IOException ignored) { /* NOP */ }
      }
      REGISTRY.clear();
    }
    System.out.println("[TestServer] All ports shutdown.");
  }

  // ====================================================================
  //  LineProtocol 处理 —— 逐行读文本命令 → 返回 CRLF 结尾文本响应
  // ====================================================================

  private static void runLineLoop(ServerSocket serverSocket) {
    while (!serverSocket.isClosed() && GLOBAL_RUNNING.get()) {
      try {
        Socket client = serverSocket.accept();
        client.setTcpNoDelay(true);
        System.out.println("[Line ] Connected: " + client.getRemoteSocketAddress());
        new Thread(() -> handleLineClient(client),
            "LineClient-" + client.getRemoteSocketAddress()).start();
      } catch (IOException e) {
        if (!serverSocket.isClosed())
          System.err.println("[Line ] Accept error: " + e.getMessage());
      }
    }
  }

  private static void handleLineClient(Socket client) {
    try (InputStream in = client.getInputStream();
         OutputStream out = client.getOutputStream()) {

      // 小缓冲：逐字符积累直到遇到 \n，保证正确切分 CRLF 帧
      ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
      byte[] readBuf = new byte[1024];
      boolean shouldDisconnectAfterResponse = false;

      while (!client.isClosed() && GLOBAL_RUNNING.get()) {
        int n = in.read(readBuf);
        if (n < 0) break;  // 对端关闭

        // 把收到的字节追加到行缓冲
        for (int i = 0; i < n; i++) {
          byte b = readBuf[i];
          lineBuf.write(b);

          if (b == '\n') {
            // 凑齐一帧（CRLF 结尾）
            String cmd = lineBuf.toString(StandardCharsets.UTF_8).trim();
            lineBuf.reset();

            boolean keepAlive = handleLineCommand(cmd, out);
            if (!keepAlive) {
              shouldDisconnectAfterResponse = true;
              break;
            }
          }
        }

        if (shouldDisconnectAfterResponse) break;
      }
    } catch (IOException | InterruptedException  e) {
      System.err.println("[Line ] Client error: " + e.getMessage());
    } finally {
      try { client.close(); } catch (IOException ignored) { /* NOP */ }
      System.out.println("[Line ] Disconnected: " + client.getRemoteSocketAddress());
    }
  }

  /**
   * 处理一条 LineProtocol 命令。
   *
   * @return true 表示保持连接，false 表示命令要求断连
   */
  private static boolean handleLineCommand(String cmd, OutputStream out) throws IOException, InterruptedException {
    System.out.println("[Line ] CMD: " + cmd);

    // ---------- 正常命令 ----------
    if (cmd.equalsIgnoreCase("MEAS:VOLT?")) {
      writeAll(out, "VOLT:1.234\r\n".getBytes(StandardCharsets.UTF_8));
      return true;
    }

    // 带延迟版本 —— 模拟慢仪器
    if (cmd.startsWith("MEAS:VOLT?")) {
      // 格式 "MEAS:VOLT? #3" 或 "MEAS:VOLT? #3000"
      String[] parts = cmd.split("#", 2);
      if (parts.length == 2) {
        try {
          int delayMs = Integer.parseInt(parts[1].trim());
          Thread.sleep(Math.min(delayMs, 60_000));  // 最多等 60s 防 hang
        } catch (NumberFormatException | InterruptedException ignored) { /* 格式不对就不加延迟 */ }
      }
      writeAll(out, "VOLT:2.567\r\n".getBytes(StandardCharsets.UTF_8));
      return true;
    }

    if (cmd.equalsIgnoreCase("PING")) {
      writeAll(out, "PONG\r\n".getBytes(StandardCharsets.UTF_8));
      return true;
    }

    if (cmd.equalsIgnoreCase("STATUS?")) {
      writeAll(out, "READY\r\n".getBytes(StandardCharsets.UTF_8));
      return true;
    }

    // ---------- 测试场景命令 ----------

    // PUSH → 服务端主动推 3 条异步数据
    if (cmd.equalsIgnoreCase("PUSH")) {
      String[] pushes = {"ASYNC:DATA1\r\n", "ASYNC:DATA2\r\n", "ASYNC:DATA3\r\n"};
      for (String p : pushes) {
        writeAll(out, p.getBytes(StandardCharsets.UTF_8));
        Thread.sleep(200);  // 间隔让客户端有机会分别收到
      }
      return true;
    }

    // STICKY → 一次 write 粘 3 帧（测试粘包处理）
    if (cmd.equalsIgnoreCase("STICKY")) {
      byte[] sticky = (
          "STICKY:FRAME1\r\n" +
          "STICKY:FRAME2\r\n" +
          "STICKY:FRAME3\r\n"
      ).getBytes(StandardCharsets.UTF_8);
      writeAll(out, sticky);
      return true;
    }

    // FRAG → 把一帧拆成 5 次小 write（测试拆包处理）
    if (cmd.equalsIgnoreCase("FRAG")) {
      String full = "FRAG:COMPLETE_RESPONSE\r\n";
      byte[] bytes = full.getBytes(StandardCharsets.UTF_8);
      // 故意按 2 字节一段拆开
      for (int i = 0; i < bytes.length; i += 2) {
        int end = Math.min(i + 2, bytes.length);
        out.write(bytes, i, end - i);
        out.flush();
        Thread.sleep(30);  // 每次间隔 30ms，模拟网络分片
      }
      return true;
    }

    // DISCONNECT → 处理完后主动关 socket（测试客户端自动重连）
    if (cmd.equalsIgnoreCase("DISCONNECT")) {
      writeAll(out, "OK, disconnecting...\r\n".getBytes(StandardCharsets.UTF_8));
      return false;  // 返回 false → 外层循环 break → socket 关闭
    }

    // BYE → 正常断开
    if (cmd.equalsIgnoreCase("BYE")) {
      writeAll(out, "BYE\r\n".getBytes(StandardCharsets.UTF_8));
      return false;
    }

    // EXIT → 关闭整个 TestServer（调试用）
    if (cmd.equalsIgnoreCase("EXIT")) {
      writeAll(out, "SHUTTING_DOWN\r\n".getBytes(StandardCharsets.UTF_8));
      GLOBAL_RUNNING.set(false);
      return false;
    }

    // ---------- 默认 ----------
    writeAll(out, ("OK\r\n").getBytes(StandardCharsets.UTF_8));
    return true;
  }

  // ====================================================================
  //  LengthFieldProtocol 处理 —— 解析 AA LEN_H LEN_L PAYLOAD CRC 二进制帧
  // ====================================================================

  private static final byte BIN_STX = (byte) 0xAA;

  private static void runBinaryLoop(ServerSocket serverSocket) {
    while (!serverSocket.isClosed() && GLOBAL_RUNNING.get()) {
      try {
        Socket client = serverSocket.accept();
        client.setTcpNoDelay(true);
        System.out.println("[Bin  ] Connected: " + client.getRemoteSocketAddress());
        new Thread(() -> handleBinaryClient(client),
            "BinClient-" + client.getRemoteSocketAddress()).start();
      } catch (IOException e) {
        if (!serverSocket.isClosed())
          System.err.println("[Bin  ] Accept error: " + e.getMessage());
      }
    }
  }

  private static void handleBinaryClient(Socket client) {
    try (InputStream in = client.getInputStream();
         OutputStream out = client.getOutputStream()) {

      ByteArrayOutputStream frameBuf = new ByteArrayOutputStream();
      byte[] readBuf = new byte[2048];

      while (!client.isClosed() && GLOBAL_RUNNING.get()) {
        int n = in.read(readBuf);
        if (n < 0) break;

        // 追加到跨帧缓存
        frameBuf.write(readBuf, 0, n);

        // 从缓存里切出完整二进制帧
        byte[] all = frameBuf.toByteArray();
        int cursor = 0;
        List<byte[]> frames = new ArrayList<>();

        while (cursor < all.length) {
          // 找 STX
          while (cursor < all.length && all[cursor] != BIN_STX) cursor++;
          if (cursor + 4 > all.length) break;  // STX + LEN_H + LEN_L 都没凑齐

          int payloadLen = ((all[cursor + 1] & 0xFF) << 8) | (all[cursor + 2] & 0xFF);
          int frameLen = 1 + 2 + payloadLen + 1;
          if (cursor + frameLen > all.length) break;  // 整帧没凑齐

          byte expected = xorChecksum(all, cursor, frameLen - 1);
          byte actual = all[cursor + frameLen - 1];
          if (expected != actual) {
            cursor++;  // CRC 不对 → resync，找下一个 STX
            continue;
          }

          frames.add(java.util.Arrays.copyOfRange(all, cursor, cursor + frameLen));
          cursor += frameLen;
        }

        // 残余写回 frameBuf
        frameBuf.reset();
        if (cursor < all.length)
          frameBuf.writeBytes(java.util.Arrays.copyOfRange(all, cursor, all.length));

        // 处理每个完整帧
        for (byte[] frame : frames) {
          handleBinaryFrame(frame, out);
        }
      }
    } catch (IOException e) {
      System.err.println("[Bin  ] Client error: " + e.getMessage());
    } finally {
      try { client.close(); } catch (IOException ignored) { /* NOP */ }
      System.out.println("[Bin  ] Disconnected: " + client.getRemoteSocketAddress());
    }
  }

  /**
   * 处理一帧二进制请求 → 构造响应帧写回。
   *
   * <p>响应帧格式和请求帧相同：AA LEN_H LEN_L PAYLOAD CRC。
   * PAYLOAD 内容直接 echo 回 + 一个字节的状态码前缀（可选）。
   */
  private static void handleBinaryFrame(byte[] requestFrame, OutputStream out) throws IOException {
    // 解析 request payload（跳过 STX 1 + LEN 2）
    int payloadLen = ((requestFrame[1] & 0xFF) << 8) | (requestFrame[2] & 0xFF);
    byte[] reqPayload = java.util.Arrays.copyOfRange(requestFrame, 3, 3 + payloadLen);

    String cmd = new String(reqPayload, StandardCharsets.UTF_8).trim();
    System.out.println("[Bin  ] CMD: " + cmd);

    // 根据命令决定响应 payload
    byte[] respPayload;
    if (cmd.equalsIgnoreCase("MEAS:VOLT?")) {
      respPayload = "VOLT:1.234".getBytes(StandardCharsets.UTF_8);
    } else if (cmd.equalsIgnoreCase("PING")) {
      respPayload = "PONG".getBytes(StandardCharsets.UTF_8);
    } else {
      respPayload = ("OK:" + cmd).getBytes(StandardCharsets.UTF_8);
    }

    // 构造响应帧
    int len = respPayload.length;
    byte[] response = new byte[1 + 2 + len + 1];
    response[0] = BIN_STX;
    response[1] = (byte) (len >>> 8);
    response[2] = (byte) len;
    System.arraycopy(respPayload, 0, response, 3, len);
    response[response.length - 1] = xorChecksum(response, 0, response.length - 1);

    writeAll(out, response);
  }

  // ====================================================================
  //  工具方法
  // ====================================================================

  /** 创建并注册一个 ServerSocket（线程安全）。 */
  private static ServerSocket createServerSocket(int port) throws IOException {
    ServerSocket ss = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
    synchronized (REGISTRY) {
      REGISTRY.put(port, ss);
    }
    System.out.println("[TestServer] Listening on 127.0.0.1:" + port);
    return ss;
  }

  /**
   * 完整写出所有字节到 OutputStream 并 flush。
   *
   * <p>为什么不循环？因为 {@link OutputStream#write(byte[], int, int)} 的契约是
   * **要么写入全部 len 字节，要么抛 IOException**，不存在"写了一半返回"的 short write 场景。
   * 底层的 {@code SocketOutputStream}（TCP socket 包装）继承自 {@code FileOutputStream}，
   * write 会阻塞直到内核发送缓冲区腾出空间并写完指定字节数。
   *
   * <p>flush 的作用：确保数据从用户态缓冲立即拷到内核 TCP 缓冲区，让对端能尽快收到。
   * 对于 {@link SocketOutputStream} 来说 flush 是**空操作**（它没有用户态缓冲），
   * 但保持调用是个好习惯 —— 如果未来换成带缓冲的 OutputStream（如 BufferedOutputStream），
   * 这里仍然能保证数据立即发出。
   */
  private static void writeAll(OutputStream out, byte[] data) throws IOException {
    out.write(data);
    out.flush();
  }

  /** LengthFieldProtocol 使用的异或校验（和客户端 checksum 一致）。 */
  private static byte xorChecksum(byte[] a, int off, int len) {
    byte x = 0;
    for (int i = off; i < off + len; i++) x ^= a[i];
    return x;
  }

  // ====================================================================
  //  独立 main 入口（可选：单独跑这个服务器）
  // ====================================================================

  public static void main(String[] args) throws IOException {
    // 注册 JVM 关闭钩子，保证 Ctrl+C 时端口被释放
    Runtime.getRuntime().addShutdownHook(new Thread(TestServer::shutdownAll, "TestServer-Shutdown"));
    startAll();
  }

  // ====================================================================
  //  暴露给外部的"让主线程挂起直到 shutdown"的工具
  // ====================================================================

  /**
   * 阻塞调用线程直到 {@link #shutdownAll()} 被调用。
   * 测试代码里可以这样用：
   * <pre>
   *   TestServer.startLineProtocol(5025);
   *   TestServer.startLengthField(9000);
   *   // ... 跑测试 ...
   *   TestServer.shutdownAll();
   * </pre>
   */
  public static void awaitShutdown() throws InterruptedException {
    // 这里用 CountDownLatch 更优雅，但保持一致性用 sleep 轮询
    while (GLOBAL_RUNNING.get()) Thread.sleep(200);
  }
}