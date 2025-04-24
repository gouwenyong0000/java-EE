package socket;

import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class SocketClient {
    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private Thread receiveThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final String host;
    private final int port;
    private final ByteArrayOutputStream receivedData = new ByteArrayOutputStream();

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
                System.out.print("Enter message: ");
                String message = scanner.nextLine();
                if (message.equals("bye")) {
                    break;
                }
                String response = client.sendMessageAndWaitForResponse(message.getBytes(), "gwy", 50000);
                System.out.println("Received response: " + response);
            }
        }
    }

    /**
     * 连接到服务器。
     *
     * @return 如果连接成功返回true，否则返回false
     */
    public boolean connect() {
        try {
            socket = new Socket(host, port);
            socket.setSoTimeout(5000); // 设置超时时间为5秒
            out = socket.getOutputStream();
            in = socket.getInputStream();
            startReceiveThread();
            return true;
        } catch (IOException e) {
            System.err.println("Connection failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 启动接收线程，用于异步接收服务器消息。
     */
    private void startReceiveThread() {
        running.set(true);
        receiveThread = new Thread(new MessageReceiver(in, running, receivedData));
        receiveThread.start();
    }

    /**
     * 发送消息到服务器。
     *
     * @param message 要发送的消息字节数组
     */
    public void sendMessage(byte[] message) {
        // 连接状态检查
        if (socket == null || !isConnected() && !reconnect()) {
            System.err.println("Failed to connect to the server, cannot send message.");
            return;
        }

        // 清空之前接收的数据
        synchronized (receivedData) {
            receivedData.reset();
        }

        try {
            // 发送消息
            out.write(message);
            out.flush();
        } catch (IOException e) {
            System.err.println("Error occurred while sending message: " + e.getMessage());
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
        // 连接状态检查
        if (socket == null || !isConnected() && !reconnect()) {
            System.err.println("Failed to connect to the server, cannot send message.");
            return null;
        }

        // 清空之前接收的数据
        synchronized (receivedData) {
            receivedData.reset();
        }

        try {
            // 发送消息
            out.write(message);
            out.flush();

            // 等待响应
            long startTime = System.currentTimeMillis();
            Pattern pattern = Pattern.compile(regexPattern);
            while (System.currentTimeMillis() - startTime < timeoutMillis) {
                synchronized (receivedData) {
                    String response = receivedData.toString();
                    if (pattern.matcher(response).find()) {
                        return response;
                    }
                }
                Thread.sleep(100); // 间隔检查
            }

            System.err.println("Timeout waiting for response matching the pattern.");
            return null;
        } catch (IOException | InterruptedException e) {
            System.err.println("Error occurred while sending message or waiting for response: " + e.getMessage());
            return null;
        }
    }

    /**
     * 尝试重新连接到服务器。
     *
     * @return 如果重新连接成功返回true，否则返回false
     */
    private boolean reconnect() {
        disconnect();
        System.out.println("Attempting to reconnect...");
        return connect();
    }

    /**
     * 检查当前连接是否有效。
     *
     * @return 如果连接有效返回true，否则返回false
     */
    public boolean isConnected() {
        // 增强连接状态检查逻辑
        return socket != null && !socket.isClosed() && socket.isConnected()
                && !socket.isInputShutdown() && !socket.isOutputShutdown();
    }

    /**
     * 断开与服务器的连接。
     */
    public void disconnect() {
        running.set(false);
        try {
            if (receiveThread != null) {
                receiveThread.interrupt(); // 中断接收线程
                receiveThread.join();
            }
            if (out != null) out.close();
            if (in != null) in.close();
            if (socket != null) socket.close();
        } catch (IOException | InterruptedException e) {
            System.err.println("Disconnect error: " + e.getMessage());
        }
    }

    /**
     * 发送心跳消息以保持连接活跃。
     */
    private void sendHeartbeat() {
        if (socket == null || !isConnected()) {
            System.err.println("Socket is not connected, cannot send heartbeat.");
            return;
        }
        try {
            out.write("HEARTBEAT".getBytes());
            out.flush();
        } catch (IOException e) {
            System.err.println("Heartbeat failed: " + e.getMessage());
            reconnect();
        }
    }
}

/**
 * 消息接收器，用于异步接收服务器消息。
 */
class MessageReceiver implements Runnable {
    private final InputStream in;
    private final AtomicBoolean running;
    private final ByteArrayOutputStream receivedData;

    /**
     * 构造函数，初始化接收器。
     *
     * @param in           输入流
     * @param running      运行状态标志
     * @param receivedData 接收到的数据缓冲区
     */
    public MessageReceiver(InputStream in, AtomicBoolean running, ByteArrayOutputStream receivedData) {
        this.in = in;
        this.running = running;
        this.receivedData = receivedData;
    }

    /**
     * 接收线程的运行逻辑，持续读取消息并存储到缓冲区。
     */
    @Override
    public void run() {
        byte[] buffer = new byte[1024];
        try {
            while (running.get()) {
                int bytesRead = in.read(buffer);
                if (bytesRead != -1) {
                    receivedData.write(buffer, 0, bytesRead);
                }
                Thread.sleep(100); // 间隔读取
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Receive error: " + e.getMessage());
        }
    }
}