package socket;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 消息接收器，用于异步接收服务器消息。
 */
class MessageReceiver implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(MessageReceiver.class);

    private final Socket socket;
    private final InputStream in;
    private final AtomicBoolean running;
    private final ByteArrayOutputStream receivedData;
    private final long timeoutMillis;

    /**
     * 构造函数，初始化接收器。
     *
     * @param socket        套接字连接
     * @param in            输入流
     * @param running       运行状态标志
     * @param receivedData  接收到的数据缓冲区
     * @param timeoutMillis 等待数据的最大超时时间（毫秒）
     */
    public MessageReceiver(Socket socket, InputStream in, AtomicBoolean running, ByteArrayOutputStream receivedData, long timeoutMillis) {
        this.socket = socket;
        this.in = in;
        this.running = running;
        this.receivedData = receivedData;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * 接收线程的运行逻辑，持续读取消息并存储到缓冲区。
     */
    @Override
    public void run() {
        log.debug("MessageReceiver thread started.");
        long start = System.currentTimeMillis();

        byte[] buffer = new byte[1024];
        try {
            // 检测服务器是否挂死 超过 一定时间 断开连接
            socket.setSoTimeout((int) timeoutMillis);

            while (running.get()) {
                // 判断 socket 输入是否关闭或连接是否断开
                if (socket.isClosed() || socket.isInputShutdown() || !socket.isConnected()) {
                    log.warn("Socket input is shutdown or connection is closed. Exiting loop.");
                    break;
                }

                int bytesRead = in.read(buffer);
                if (bytesRead > 0) {
                    //  同步写入数据
                    synchronized (receivedData) {
                        receivedData.write(buffer, 0, bytesRead);
                    }

                    // 打印 dump 十六进制 和 ascii
                    String hexDump = hexDump(buffer, bytesRead);
                    String asciiDump = asciiDump(buffer, bytesRead);
                    log.debug("Received {} bytes of data. Hex Dump: {}", bytesRead, hexDump);
                    log.debug("Received {} bytes of data. ASCII Dump: {}", bytesRead, asciiDump);

                } else if (bytesRead == -1) {
                    log.info("End of stream reached. Stopping receiver.");
                    break;
                }

                // 休眠以减少 CPU 占用
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            log.info("Receive thread interrupted");
            Thread.currentThread().interrupt(); // 恢复中断状态
        } catch (SocketTimeoutException e) {
            log.error("Socket read timed out: {}", e.getMessage());
        } catch (IOException e) {
            log.error("Receive error: {}", e.getMessage());
        } finally {
            log.debug("MessageReceiver thread exiting."); // 新增：记录线程退出日志
            // 确保资源释放
            closeQuietly(socket);
            closeQuietly(in);
            running.set(false);
        }
    }


    /**
     * 生成十六进制表示形式的字符串。
     *
     * @param buffer 字节数组
     * @param length 实际读取的字节数
     * @return 十六进制表示形式的字符串
     */
    private String hexDump(byte[] buffer, int length) {
        StringBuilder hexDump = new StringBuilder();
        for (int i = 0; i < length; i++) {
            hexDump.append(String.format("%02X ", buffer[i]));
        }
        return hexDump.toString().trim();
    }

    /**
     * 生成 ASCII 表示形式的字符串。
     *
     * @param buffer 字节数组
     * @param length 实际读取的字节数
     * @return ASCII 表示形式的字符串
     */
    private String asciiDump(byte[] buffer, int length) {
        StringBuilder asciiDump = new StringBuilder();
        for (int i = 0; i < length; i++) {
            asciiDump.append(Character.isISOControl(buffer[i]) ? "." : (char) buffer[i]);
        }
        return asciiDump.toString();
    }

    /**
     * 安静地关闭资源，忽略关闭过程中抛出的异常。
     *
     * @param closeable 可关闭的对象
     */
    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
                log.debug("Closed resource successfully: {}", closeable.getClass().getSimpleName());
            } catch (Exception e) {
                log.warn("Failed to close resource: {}", e.getMessage());
            }
        }
    }
}
