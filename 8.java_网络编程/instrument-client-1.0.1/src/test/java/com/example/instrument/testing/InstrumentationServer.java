package com.example.instrument.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可完全控制的测试服务器，支持各种异常行为模拟。
 *
 * <p>支持的模拟场景：</p>
 * <ul>
 *   <li>正常 request-response</li>
 *   <li>延迟响应（用于测试 timeout）</li>
 *   <li>连接重置（用于测试 connection reset）</li>
 *   <li>写一半关闭（用于测试 write half-failure）</li>
 *   <li>粘包（多帧一次到达）</li>
 *   <li>拆包（分批发送）</li>
 *   <li>发送错误帧（用于测试 decoder malformed）</li>
 *   <li>发送校验和错误帧</li>
 *   <li>发送超大帧</li>
 * </ul>
 *
 * <p>协议格式：使用 CRLF 行协议。</p>
 *
 * <p>控制命令：</p>
 * <pre>
 * ECHO:xxx      → 响应 xxx
 * DELAY:N       → 延迟 N 毫秒后响应 "DELAYED"
 * RESET         → 发送 RST 包重置连接
 * HALFWRITE     → 写一半数据后关闭连接
 * PUSH:data     → 立即推送异步数据
 * PUSHBINARY:len → 推送指定长度的二进制帧
 * STICKY2       → 一次发送两个帧
 * FRAGMENTED    → 分批发送一个帧
 * BADLEN        → 发送长度错误的帧
 * BADCHK        → 发送校验和错误的帧（二进制协议）
 * HUGE          → 发送超大帧（用于测试 max frame）
 * IDN           → 响应测试 IDN
 * </pre>
 */
public final class InstrumentationServer {

    private final int port;
    private volatile ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final AtomicBoolean shouldResetOnConnect = new AtomicBoolean(false);

    public InstrumentationServer(int port) {
        this.port = port;
    }

    /**
     * 启动服务器（在独立线程中）。
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        running.set(true);

        Thread acceptThread = new Thread(() -> {
            while (running.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    connectionCount.incrementAndGet();
                    new Thread(() -> handleClient(clientSocket), "server-connection-" + connectionCount.get()).start();
                } catch (IOException e) {
                    if (running.get()) {
                        e.printStackTrace();
                    }
                }
            }
        }, "test-server-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /**
     * 停止服务器。
     */
    public void stop() throws IOException {
        running.set(false);
        ServerSocket ss = serverSocket;
        if (ss != null && !ss.isClosed()) {
            ss.close();
        }
    }

    /**
     * 获取连接数。
     */
    public int connectionCount() {
        return connectionCount.get();
    }

    /**
     * 让下一个连接在 accept 后立即被 reset。
     */
    public void setResetOnConnect(boolean value) {
        shouldResetOnConnect.set(value);
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(0);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            if (shouldResetOnConnect.getAndSet(false)) {
                resetConnection(socket);
                return;
            }

            ByteArray lineBuffer = new ByteArray();

            while (running.get()) {
                int b = in.read();
                if (b < 0) {
                    break;
                }

                if (b == '\r' || b == '\n') {
                    if (b == '\n') {
                        handleCommand(socket, in, out, lineBuffer.toString());
                        lineBuffer.reset();
                    }
                    continue;
                }

                lineBuffer.append((byte) b);
            }
        } catch (IOException ignored) {
        } finally {
            connectionCount.decrementAndGet();
            closeQuietly(socket);
        }
    }

    private void handleCommand(Socket socket, InputStream in, OutputStream out, String command) throws IOException {
        if (command.isEmpty()) {
            return;
        }

        String[] parts = command.split(":", 2);
        String cmd = parts[0].toUpperCase();
        String arg = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "ECHO" -> {
                writeLine(out, arg);
            }
            case "IDN" -> {
                writeLine(out, "TestServer,Model-1000,SN00000001,1.0.0");
            }
            case "PING" -> {
                writeLine(out, "PONG");
            }
            case "DELAY" -> {
                int millis = parseInt(arg, 1000);
                try { Thread.sleep(millis); } catch (InterruptedException ignored) {}
                writeLine(out, "DELAYED");
            }
            case "RESET" -> {
                resetConnection(socket);
            }
            case "HALFWRITE" -> {
                out.write((byte) 'X');
                out.flush();
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                resetConnection(socket);
            }
            case "STICKY2" -> {
                String combined = "FRAME1\r\nFRAME2\r\n";
                out.write(combined.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            case "FRAGMENTED" -> {
                String full = "FRAGMENTED_RESPONSE\r\n";
                byte[] bytes = full.getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < bytes.length; i++) {
                    out.write(bytes[i]);
                    out.flush();
                    try { Thread.sleep(2); } catch (InterruptedException ignored) {}
                }
            }
            case "MULTIFRAME" -> {
                String response = "OK\r\nDATA1\r\nDATA2\r\n";
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            case "BADLEN" -> {
                sendBadLengthFrame(out, Integer.parseInt(arg));
            }
            case "BADCHK" -> {
                sendBadChecksumFrame(out, arg);
            }
            case "HUGE" -> {
                int size = parseInt(arg, 2_000_000);
                sendHugeFrame(out, size);
            }
            case "CLOSE_AFTER" -> {
                int afterCount = parseInt(arg, 1);
                writeLine(out, "OK_" + afterCount);
                for (int i = 1; i < afterCount; i++) {
                    writeLine(out, "DATA_" + i);
                }
                socket.close();
            }
            default -> {
                writeLine(out, "OK:" + command);
            }
        }
    }

    private void writeLine(OutputStream out, String content) throws IOException {
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.write('\r');
        out.write('\n');
        out.flush();
    }

    private void resetConnection(Socket socket) throws IOException {
        socket.setSoLinger(true, 0);
        socket.close();
    }

    /**
     * 发送错误长度的长度字段帧（用于测试 LengthFieldProtocol 的 bad length 场景）。
     */
    private void sendBadLengthFrame(OutputStream out, int wrongLen) throws IOException {
        byte[] badFrame = new byte[]{
            (byte) 0xAA,
            (byte) (wrongLen >>> 8),
            (byte) wrongLen,
            'B', 'A', 'D',
            0x00
        };
        out.write(badFrame);
        out.flush();
    }

    /**
     * 发送校验和错误的长度字段帧。
     */
    private void sendBadChecksumFrame(OutputStream out, String payload) throws IOException {
        byte[] data = payload.isEmpty() ? "CHECKSUM_BAD".getBytes(StandardCharsets.UTF_8) : payload.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[4 + data.length];
        frame[0] = (byte) 0xAA;
        frame[1] = (byte) (data.length >>> 8);
        frame[2] = (byte) data.length;
        System.arraycopy(data, 0, frame, 3, data.length);

        byte checksum = 0;
        for (int i = 0; i < frame.length; i++) {
            checksum ^= frame[i];
        }
        frame[frame.length - 1] = (byte) (checksum ^ 0xFF);

        out.write(frame);
        out.flush();
    }

    /**
     * 发送超大 CRLF 帧。
     */
    private void sendHugeFrame(OutputStream out, int size) throws IOException {
        out.write(new byte[Math.max(0, size - 2)]);
        out.write('\r');
        out.write('\n');
        out.flush();
    }

    private static int parseInt(String s, int defaultValue) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static final class ByteArray {
        private byte[] data = new byte[64];
        private int pos = 0;

        void append(byte b) {
            if (pos == data.length) {
                byte[] expanded = new byte[data.length * 2];
                System.arraycopy(data, 0, expanded, 0, pos);
                data = expanded;
            }
            data[pos++] = b;
        }

        public String toString() {
            return new String(data, 0, pos, StandardCharsets.UTF_8);
        }

        void reset() {
            pos = 0;
        }
    }
}