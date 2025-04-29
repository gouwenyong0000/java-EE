package socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;

import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * SocketClient 和 MessageReceiver 是一个完整的客户端通信解决方案，前者负责连接管理和消息发送，后者负责异步接收消息。
 * 设计特点：
 * 使用多线程技术实现异步消息接收。
 * 提供了重连机制和心跳机制，确保连接的稳定性。
 * 日志记录详细且灵活，便于监控和维护。
 */
public class SocketClient {

    private static final Logger log = LoggerFactory.getLogger(SocketClient.class);

    // Socket 客户端相关属性和变量
    private Socket socket;
    private OutputStream out;
    private InputStream in;
    // 接收线程
    private Thread receiveThread;
    // 接受线程运行状态
    private final AtomicBoolean running = new AtomicBoolean(false);

    // host 和 port
    private final String host;
    private final int port;

    // 用于存储接收到的数据
    private final ByteArrayOutputStream receivedData = new ByteArrayOutputStream();
    private int readTimeOut;

    /**
     * 构造函数，初始化客户端连接信息。
     *
     * @param host 服务器主机地址
     * @param port 服务器端口号
     */
    public SocketClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * 主方法，用于启动客户端并发送消息。
     *
     * @param args 命令行参数（未使用）
     */
    public static void main(String[] args) {
        SocketClient client = new SocketClient("localhost", 8080);
        if (client.connect()) {
            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.println("Enter message: ");
                String message = scanner.nextLine();
                client.readTimeOut = 300000;
                if (message.equals("bye")) {
                    break;
                }
                String response = client.sendMessageAndWaitForResponse(message.getBytes(), "gwy", 50000);
                System.out.println("Received response: " + response);
            }
            client.disconnect();
        } else {
            log.error("connect failed");
        }
    }

    /**
     * 连接到服务器。
     *
     * @return 如果连接成功返回true，否则返回false
     */
    public boolean connect() {
        try {
            //  socket
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000); // 5秒超时

            // 设置socket选项
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(readTimeOut); // 0表示无限等待，可以设置其他值以设置超时时间

            out = socket.getOutputStream();
            in = socket.getInputStream();

            startReceiveThread();// 启动接收线程

            log.info("Successfully connected to server at {}:{}", host, port); // 新增：记录成功连接的日志
            return true;
        } catch (ConnectException e) {
            log.warn("Connection refused: The server is not available or the port is incorrect. Host: {}, Port: {}", host, port); // 修改：使用 warn 级别并添加上下文信息
        } catch (SocketTimeoutException e) {
            log.error("Connection timed out: The server did not respond within the timeout period. Host: {}, Port: {}", host, port);
        } catch (UnknownHostException e) {
            log.error("Unknown host: Unable to resolve hostname '{}'. Error: {}", host, e.getMessage());
        } catch (SecurityException e) {
            log.error("Security error: Permission denied when connecting to host '{}'. Error: {}", host, e.getMessage());
        } catch (IOException e) {
            log.error("An unexpected I/O error occurred while connecting to server at {}:{}. Error: {}", host, port, e.getMessage()); // 修改：增强日志内容
        }
        return false;
    }

    /**
     * 启动接收线程，用于异步接收服务器消息。
     * 如果接收线程已经存在且正在运行，则不会重复启动。
     */
    private void startReceiveThread() {
        log.debug("Attempting to start receive thread.");
        running.set(true);
        MessageReceiver messageReceiver = new MessageReceiver(socket, in, running, receivedData, readTimeOut);
        receiveThread = new Thread(messageReceiver);
        receiveThread.start();

        log.info("Receive thread started successfully.");
    }


    /**
     * 发送消息到服务器。
     *
     * @param message 要发送的消息字节数组
     * @return 如果消息发送成功返回true，否则返回false
     */
    public boolean sendMessage(byte[] message) {
        // 连接状态检查 条件判断逻辑错误，因运算符优先级导致
        if ((socket == null || !isConnected()) && !reconnect()) {
            log.error("Failed to connect to the server, cannot send message. Host: {}, Port: {}", host, port);
            return false; // 连接失败，返回false
        }

        // 检查接收线程是否存活
        if (receiveThread == null || !receiveThread.isAlive()) {
            log.warn("Receive thread is not alive, attempting to restart...");
            startReceiveThread();
            if (receiveThread == null || !receiveThread.isAlive()) {
                log.error("Failed to restart receive thread, cannot send message.");
                return false; // 接收线程重启失败，返回false
            }
        }

        // 清空之前接收的数据
        synchronized (receivedData) {
            String dataToClear = receivedData.toString();
            log.debug("Clearing received data before sending new message. Host: {}, Port: {}, Data to clear: {}", host, port, dataToClear); // 增加清空的具体内容
            receivedData.reset();
        }

        try {
            // 发送消息
            out.write(message);
            out.flush();
            log.debug("Message sent successfully. Host: {}, Port: {}, Message: {}", host, port, new String(message)); // 记录发送成功的消息内容
            return true; // 消息发送成功，返回true
        } catch (IOException e) {
            log.error("Error occurred while sending message to server at {}:{}. Error: {}", host, port, e.getMessage());
            // 增加重试逻辑
            if (reconnect()) {
                try {
                    out.write(message);
                    out.flush();
                    log.debug("Message sent successfully after retry. Host: {}, Port: {}, Message: {}", host, port, new String(message)); // 记录重试后发送成功的消息内容
                    return true; // 重试后消息发送成功，返回true
                } catch (IOException retryException) {
                    log.error("Retry failed: The server did not accept the message after reconnection. Host: {}, Port: {}. Error: {}", host, port, retryException.getMessage());
                }
            }
            return false; // 消息发送失败，返回false
        }
    }

    /**
     * 发送消息并等待服务器响应。
     *
     * @param message       要发送的消息字节数组
     * @param regexPattern  响应匹配的正则表达式
     * @param timeoutMillis 超时时间（毫秒）
     * @return 匹配的响应字符串，超时或失败时返回null
     */
    public String sendMessageAndWaitForResponse(byte[] message, String regexPattern, long timeoutMillis) {
        // 调用 sendMessage 方法发送消息
        boolean sendMessageResult = sendMessage(message);
        if (!sendMessageResult) {
            log.error("Message sending failed, cannot wait for response.");
            return null; // 如果消息发送失败，直接返回null
        }

        // 设置合理的最大等待时间限制
        long maxWaitTime = Math.min(timeoutMillis, 300000); // 最大限制5分钟
        try {
            socket.setSoTimeout((int) maxWaitTime); // 设置为实际等待时间
        } catch (SocketException e) {
            log.error("Failed to set socket timeout: {}", e.getMessage());
            return null;
        }

        // 等待响应
        long startTime = System.currentTimeMillis();
        Pattern pattern = Pattern.compile(regexPattern);

        synchronized (receivedData) {
            while (System.currentTimeMillis() - startTime < maxWaitTime) {
                try {
                    String currentData = receivedData.toString("UTF-8");
                    if (pattern.matcher(currentData).find()) {
                        return currentData;
                    }

                    receivedData.wait(100); // 避免长时间阻塞
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Waiting interrupted while waiting for response", e);
                    return "等待中断";
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException("UTF-8 encoding not supported", e);
                }
            }
        }

        log.error("Timeout waiting for response matching the pattern: {}. Elapsed: {}ms", regexPattern, (System.currentTimeMillis() - startTime));
        return null;
    }

    /**
     * 尝试重新连接到服务器。
     *
     * @return 如果重新连接成功返回true，否则返回false
     */
    private boolean reconnect() {
        int attempts = 0;
        int totalTimes = 3;
        long initialDelay = 1000; // 初始延迟1秒
        while (attempts < totalTimes) {
            try {
                long delay = (long) Math.pow(2, attempts) * initialDelay; // 指数退避
                Thread.sleep(delay);
                disconnect();
                log.info("Attempting to reconnect {}/{}", attempts + 1, totalTimes);
                if (connect()) {
                    return true;
                }
                attempts++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted during reconnection attempt {}/{}", attempts + 1, totalTimes, e);
                return false;
            }
        }
        log.error("Failed to reconnect after {} attempts.", totalTimes);
        return false;
    }

    /**
     * 检查当前连接是否有效。
     *
     * @return 如果连接有效返回true，否则返回false
     */
    public boolean isConnected() {
        return socket != null
                && !socket.isClosed()
                && socket.isConnected()
                && !socket.isInputShutdown(); // 移除outputShutdown检查，允许单向通信
    }

    /**
     * 断开与服务器的连接。
     * 该方法会确保所有资源被正确关闭，并记录每一步的操作日志。
     */
    public void disconnect() {
        log.info("Attempting to disconnect from server at {}:{}", host, port);
        running.set(false);

        try {
            if (receiveThread != null) {
                log.debug("Interrupting receive thread...");
                receiveThread.interrupt();
                receiveThread.join(5000);
                if (receiveThread.isAlive()) {
                    log.warn("Receive thread did not terminate within the timeout period.");
                } else {
                    log.debug("Receive thread terminated successfully.");
                }
            }
        } catch (InterruptedException e) {
            log.error("Thread interruption occurred while closing resources: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }

        // 显式关闭所有资源，确保正确的关闭顺序
        try {
            if (out != null) {
                out.close();
                log.debug("OutputStream closed successfully");
            }
        } catch (IOException e) {
            log.error("Error closing OutputStream: {}", e.getMessage());
        }

        try {
            if (in != null) {
                in.close();
                log.debug("InputStream closed successfully");
            }
        } catch (IOException e) {
            log.error("Error closing InputStream: {}", e.getMessage());
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                log.debug("Socket closed successfully");
            }
        } catch (IOException e) {
            log.error("Error closing Socket: {}", e.getMessage());
        }

        log.info("Disconnected from server at {}:{}", host, port);
    }

    /**
     * 发送心跳消息以保持连接活跃。
     * 如果心跳发送失败，会尝试重新连接。
     */
    private void sendHeartbeat() {
        log.debug("Attempting to send heartbeat."); // 新增：记录心跳发送的尝试
        if (socket == null || !isConnected()) {
            log.warn("Socket is not connected, cannot send heartbeat.");
            reconnect();
            return;
        }
        try {
            out.write("HEARTBEAT".getBytes());
            out.flush();
            log.debug("Heartbeat sent successfully.");
        } catch (IOException e) {
            log.error("Heartbeat failed: {}", e.getMessage());
            reconnect();
        }
    }
}

