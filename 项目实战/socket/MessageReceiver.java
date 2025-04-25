package socket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 消息接收器，用于异步接收服务器消息。
 */
class MessageReceiver implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(MessageReceiver.class);

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
                if (bytesRead > 0) {
                    receivedData.write(buffer, 0, bytesRead);

                    // 新增：将读取到的字节数组转换为十六进制和 ASCII 表示形式
                    String hexDump = hexDump(buffer, bytesRead);
                    String asciiDump = asciiDump(buffer, bytesRead);

                    // 打印 dump 信息
                    log.debug("Received {} bytes of data. Hex Dump: {}", bytesRead, hexDump);
                    log.debug("Received {} bytes of data. ASCII Dump: {}", bytesRead, asciiDump);
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            log.info("Receive thread interrupted");
            Thread.currentThread().interrupt(); // 恢复中断状态
            running.set(false);
        } catch (IOException e) {
            log.error("Receive error: {}", e.getMessage());
            running.set(false);
        }
    }

    /**
     * 生成十六进制表示形式的字符串。
     *
     * @param buffer   字节数组
     * @param length   实际读取的字节数
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
     * @param buffer   字节数组
     * @param length   实际读取的字节数
     * @return ASCII 表示形式的字符串
     */
    private String asciiDump(byte[] buffer, int length) {
        StringBuilder asciiDump = new StringBuilder();
        for (int i = 0; i < length; i++) {
            asciiDump.append(Character.isISOControl(buffer[i]) ? "." : (char) buffer[i]);
        }
        return asciiDump.toString();
    }

}
