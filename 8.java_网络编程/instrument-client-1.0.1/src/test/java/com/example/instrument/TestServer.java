package com.example.instrument;

import com.example.instrument.api.BlockingDataListener;
import com.example.instrument.api.ConnectionListener;
import com.example.instrument.api.DataListener;
import com.example.instrument.api.InstrumentClient;
import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.config.ReconnectConfig;
import com.example.instrument.model.Command;
import com.example.instrument.model.CommandIdempotency;
import com.example.instrument.model.Response;
import com.example.instrument.protocol.LineProtocol;
import com.example.instrument.protocol.LengthFieldProtocol;
import com.example.instrument.protocol.Protocol;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class TestServer {
    
    private final int port;
    private volatile boolean running;
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final String delimiter;
    
    public TestServer(int port, boolean useCrlf) {
        this.port = port;
        this.delimiter = useCrlf ? "\r\n" : "\n";
    }
    
    public void start() throws Exception {
        java.net.ServerSocket serverSocket = new java.net.ServerSocket(port);
        serverSocket.setReuseAddress(true);
        running = true;
        
        System.out.println("[TestServer] Started on port " + port + " (delimiter: " + (delimiter.equals("\r\n") ? "CRLF" : "LF") + ")");
        
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                connectionCount.incrementAndGet();
                System.out.println("[TestServer] Client connected, total: " + connectionCount.get());
                
                Thread handler = new Thread(() -> handleClient(clientSocket));
                handler.setDaemon(true);
                handler.start();
            } catch (Exception e) {
                if (running) {
                    System.out.println("[TestServer] Accept error: " + e.getMessage());
                }
            }
        }
        
        serverSocket.close();
        System.out.println("[TestServer] Stopped");
    }
    
    private void handleClient(Socket socket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            
            socket.setSoTimeout(5000);
            String line;
            
            while ((line = reader.readLine()) != null) {
                System.out.println("[TestServer] Received: " + line);
                String response = processCommand(line.trim());
                writer.print(response);
                writer.print(delimiter);
                writer.flush();
                System.out.println("[TestServer] Sent: " + response.replace("\r", "\\r").replace("\n", "\\n"));
            }
        } catch (Exception e) {
            System.out.println("[TestServer] Client handler error: " + e.getMessage());
        } finally {
            connectionCount.decrementAndGet();
            System.out.println("[TestServer] Client disconnected, remaining: " + connectionCount.get());
        }
    }
    
    private String processCommand(String command) {
        if (command.toUpperCase().startsWith("DELAY:")) {
            try {
                int millis = Integer.parseInt(command.substring(6));
                Thread.sleep(millis);
            } catch (Exception ignored) {}
            return "DELAYED";
        }
        return switch (command.toUpperCase()) {
            case "*IDN?" -> "TestServer,Model-1000,SN12345,1.0.0";
            case "PING" -> "PONG";
            case "STATUS?" -> "OK";
            case "ERROR" -> "ERROR:Invalid command";
            case "ECHO:*" -> command.substring(5);
            case "BINARY" -> "BINARY_DATA";
            default -> "OK:" + command;
        };
    }
    
    public void stop() {
        running = false;
    }
    
    public int getConnectionCount() {
        return connectionCount.get();
    }
    
    public static void main(String[] args) throws Exception {
        boolean useCrlf = args.length > 0 && args[0].equals("crlf");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5025;
        
        TestServer server = new TestServer(port, useCrlf);
        
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        
        server.start();
    }
}