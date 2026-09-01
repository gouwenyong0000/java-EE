package com.example.instrument;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 一个最小可用的测试服务端，用于验证客户端的连接、发送和响应处理流程。
 *
 * <p>它监听本机 127.0.0.1:5025，并根据收到的指令返回固定的文本响应，便于开发阶段快速联调。
 */
public final class TestServer {
  public static final String HOST = "127.0.0.1";
  public static final int PORT = 5025;

  private TestServer() {}

  public static void main(String[] args) throws IOException {
    try (ServerSocket serverSocket = new ServerSocket(PORT, 50, InetAddress.getByName(HOST))) {
      System.out.printf("Test server started on %s:%d%n", HOST, PORT);
      while (true) {
        try (Socket socket = serverSocket.accept();
            BufferedReader reader =
                new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer =
                new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

          System.out.println("Client connected: " + socket.getRemoteSocketAddress());

          String line;
          while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
              continue;
            }
            String response = handleCommand(line);
            writer.write(response);
            writer.flush();
            System.out.printf(
                "Received: %s -> Response: %s%n",
                line,
                response.replace("\r", "\\r").replace("\n", "\\n"));
          }
        } catch (IOException e) {
          System.err.println("Connection error: " + e.getMessage());
        }
      }
    }
  }

  /**
   * 根据输入命令返回模拟仪器的回复。
   *
   * <p>这里内置了几个常用的测试命令，便于验证客户端 sendAndRegex() 等用法。
   */
  private static String handleCommand(String command) {
    String normalized = command.trim();

    switch (normalized.toUpperCase()) {
      case "MEAS:VOLT?":
        return "VOLT:1.234\r\n";
      case "PING":
        return "PONG\r\n";
      case "STATUS?":
        return "READY\r\n";
      default:
        return "OK\r\n";
    }
  }
}
