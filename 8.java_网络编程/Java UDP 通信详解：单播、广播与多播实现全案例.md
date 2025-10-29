# 探测局域网内设备

# 一、功能概述

UDP 广播允许一台主机向同一局域网中所有设备发送数据包，用于设备发现、状态同步等。

**实现思路：**

- **发送端**：周期性或按需向局域网广播固定格式的探测报文。
- **接收端（设备端）**：监听指定端口，收到报文后判断协议是否匹配，若匹配则回复设备信息。

## 🧩 功能概述

- **发送线程**：
   持续向广播地址 `129.9.255.255:1500` 发送固定内容的 UDP 报文（`NESOFTECHOOHCETFOSEN`）。
   这相当于局域网设备发现报文（discovery packet），用来让设备响应自己的 IP 和端口。
- **接收线程**：
   监听来自任何设备的 UDP 回复包（例如：设备上报自身信息），打印收到的数据。



# 二、示例代码

## chatGPT版本

### SendUDP（自动广播设备发现）

```java
/**
 * UDP 广播发送端，用于在局域网中探测设备。
 */
public class SendUDP {

  private static final String MESSAGE = "NESOFTECHOOHCETFOSEN"; // 固定协议报文
  private static final int PACKET_LENGTH = 32; // 报文最小长度要求
  private static final int TARGET_PORT = 1500; // 设备监听端口
  private static final AtomicBoolean running = new AtomicBoolean(true); // 控制程序运行状态

  public static void main(String[] args) throws Exception {
    DatagramSocket socket = new DatagramSocket(); // 随机本地端口
    socket.setBroadcast(true);
    socket.setSoTimeout(2000); // 接收超时防止永久阻塞

    InetAddress broadcastAddr = getBroadcastAddress();
    log("Using broadcast address: " + broadcastAddr.getHostAddress() + ":" + TARGET_PORT);

    Thread sendThread = new Thread(() -> sendLoop(socket, broadcastAddr));
    Thread recvThread = new Thread(() -> receiveLoop(socket));

    sendThread.start();
    recvThread.start();

    sendThread.join();
    running.set(false); // 通知接收线程退出
    recvThread.join();

    socket.close();
    log("Socket closed. Program exited.");
  }

  /** 自动获取本机网卡广播地址（优先使用非回环、活动网卡） */
  private static InetAddress getBroadcastAddress() throws SocketException, UnknownHostException {
    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
    while (interfaces.hasMoreElements()) {
      NetworkInterface ni = interfaces.nextElement();
      if (ni.isLoopback() || !ni.isUp()) continue;
      for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
        InetAddress broadcast = ia.getBroadcast();
        if (broadcast != null) return broadcast;
      }
    }
    // 默认广播地址（兜底）
    return InetAddress.getByName("255.255.255.255");
  }

  /** 发送线程逻辑 */
  private static void sendLoop(DatagramSocket socket, InetAddress broadcastAddr) {
    log("Send thread started. Type 'bye' to quit.");
    try (BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in))) {
      while (running.get()) {
        System.out.print("> ");
        String input = stdin.readLine();
        if (input == null || input.trim().equalsIgnoreCase("bye")) {
          running.set(false);
          break;
        }

        // 发送固定协议报文
        byte[] data = MESSAGE.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buff = ByteBuffer.allocate(PACKET_LENGTH);
        buff.put(Arrays.copyOf(data, PACKET_LENGTH));

        DatagramPacket packet =
            new DatagramPacket(buff.array(), PACKET_LENGTH, broadcastAddr, TARGET_PORT);
        socket.send(packet);

        log("Sent packet (" + PACKET_LENGTH + " bytes) to " + broadcastAddr.getHostAddress());
      }
    } catch (IOException e) {
      log("Send thread error: " + e.getMessage());
    }
    log("Send thread exited.");
  }

  /** 接收线程逻辑 */
  private static void receiveLoop(DatagramSocket socket) {
    log("Receive thread started.");
    byte[] buf = new byte[1024];

    while (running.get()) {
      try {
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);

        String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        log(
            "Received from "
                + packet.getAddress().getHostAddress()
                + ":"
                + packet.getPort()
                + " → "
                + msg);

      } catch (SocketTimeoutException e) {
        // 超时重试，不打印
      } catch (IOException e) {
        if (running.get()) log("Receive thread error: " + e.getMessage());
      }
    }
    log("Receive thread exited.");
  }

  /** 带时间戳的日志输出 */
  private static void log(String msg) {
    String time = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
    System.out.println("[" + time + "] " + msg);
  }
}
```

### ReceiveUDP（设备模拟端）

```java
public class ReceiveUDP {

  private static final int LISTEN_PORT = 1500; // 与 SendUDP 中的 TARGET_PORT 一致
  private static final String EXPECTED_MESSAGE = "NESOFTECHOOHCETFOSEN"; // 协议固定内容
  private static final String REPLY_MESSAGE = "DEVICE_OK"; // 模拟设备响应内容

  public static void main(String[] args) {
    log("Device simulator starting... Listening on port " + LISTEN_PORT);
    try (DatagramSocket socket = new DatagramSocket(LISTEN_PORT)) {
      socket.setBroadcast(true);
      byte[] buf = new byte[1024];

      while (true) {
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);

        String received =
            new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
        String senderIp = packet.getAddress().getHostAddress();
        int senderPort = packet.getPort();

        log("Received from " + senderIp + ":" + senderPort + " → " + received);

        if (received.startsWith(EXPECTED_MESSAGE)) {
          // 模拟设备响应：发送本机信息
          String response =
              REPLY_MESSAGE
                  + " | IP="
                  + InetAddress.getLocalHost().getHostAddress()
                  + " | TIME="
                  + new SimpleDateFormat("HH:mm:ss").format(new Date());
          byte[] data = response.getBytes(StandardCharsets.UTF_8);

          DatagramPacket replyPacket =
              new DatagramPacket(data, data.length, packet.getAddress(), senderPort);
          socket.send(replyPacket);

          log("Replied: " + response);
        } else {
          log("Ignored unknown message.");
        }
      }

    } catch (IOException e) {
      log("Error: " + e.getMessage());
    }
  }

  private static void log(String msg) {
    String time = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
    System.out.println("[" + time + "] " + msg);
  }
}

```





## 第一版

### SendUDP.java

```java
public class SendUDP {
  public static void main(String[] args) throws Exception {
    // Use this port to send broadcast packet 使用此端口发送广播数据包
    final DatagramSocket detectSocket = new DatagramSocket(); // 无参构造 port=0 随机绑定本地可用端口
    detectSocket.setBroadcast(true); // 允许广播

    // Send packet thread 向 129.9.255.255:1500 广播，用于发现局域网内的设备， 用于告知被探测设备上报的ip和端口
    new Thread(
            () -> {
              System.out.println("Send thread started.");
              while (true) {
                try {

                  // 获取报文内容
                  BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
                  String outMessage = stdin.readLine();

                  if (outMessage.equals("bye")) break;

                  outMessage = "NESOFTECHOOHCETFOSEN"; // 协议固定消息内容，用于设备识别

                  // Send packet to hostAddress:1500, server that listen
                  // 1500 would reply this packet
                  // fix： 搜不到局域网IP 时排查报文长度必须大于一定值
                  int capacity = 32;
                  ByteBuffer buff = ByteBuffer.allocate(capacity);
                  buff.put(outMessage.getBytes());

                  // Broadcast address 129.9.255.255:1500
                  InetAddress hostAddress = InetAddress.getByName("129.9.255.255");
                  int packetPort = 1500; // 需要与接收端ReceiveUDP监听端口一致
                  DatagramPacket out =
                      new DatagramPacket(buff.array(), capacity, hostAddress, packetPort);
                  detectSocket.send(out); // 发送到 129.9.255.255:1500 路由器内进行广播

                  System.out.println("Send " + outMessage + " to " + hostAddress);
                } catch (IOException e) {
                  e.printStackTrace();
                }
              }
            })
        .start();

    // Receive packet thread 接收来自设备的响应
    new Thread(
            new Runnable() {
              @Override
              public void run() {
                System.out.println("Receive thread started.");
                while (true) {
                  byte[] buf = new byte[1024];
                  DatagramPacket packet = new DatagramPacket(buf, buf.length);
                  try {
                    // detectSocket.setSoTimeout(1000);//设置超时
                    detectSocket.receive(packet);
                    String rcvd =
                        "Received from "
                            + packet.getSocketAddress()
                            + ", Data="
                            + new String(packet.getData(), 0, packet.getLength());
                    System.out.println(rcvd);
                  } catch (IOException e) {
                    e.printStackTrace();
                  }
                }
              }
            })
        .start();
  }
}
```

### ReceiveUDP.java

```java
import java.net.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

//接收端监听1500端口的UDP广播，接收到后判断是否满足协议
//满足后获取发送端ip和端口，单播回ip地址列表
public class ReceiveUDP {
    public static void main(String[] args) throws Exception {

        int listenPort = 1500;
        DatagramSocket responseSocket = new DatagramSocket(listenPort);
        System.out.println("Server  started,  Listen  port:  " + listenPort);

        while (true) {
            byte[] buf = new byte[32];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);//按协议接受32个字节
            responseSocket.receive(packet);//接受广播消息


            SocketAddress srcAddress = packet.getSocketAddress();//获取发送端 ip和端口

            String rcvd = "Received  "+ new String(packet.getData())+ "  from  address:  " + srcAddress;
            System.out.println(rcvd);//打印广播源地址

            //  Send  a  response  packet  to  sender  上报本机ip列表  按协议填充到1024
            String backData = getIpAddress().toString();
            byte[] data = backData.getBytes();
            int receiveLen = 1024;
            ByteBuffer buffer = ByteBuffer.allocate(receiveLen);
            buffer.put(data);
            System.out.println("Send  " + backData + "  to  " + srcAddress);
            DatagramPacket backPacket = new DatagramPacket(buffer.array(), receiveLen, srcAddress);
            responseSocket.send(backPacket);
        }
    }

    public static List<String> getIpAddress() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> allNetInterfaces = NetworkInterface.getNetworkInterfaces();
            InetAddress ip = null;
            while (allNetInterfaces.hasMoreElements()) {
                NetworkInterface netInterface = (NetworkInterface) allNetInterfaces.nextElement();
                if (netInterface.isLoopback() || netInterface.isVirtual() || !netInterface.isUp()) {
                    continue;
                } else {
                    Enumeration<InetAddress> addresses = netInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        ip = addresses.nextElement();
                        if (ip != null && ip instanceof Inet4Address) {
                            ips.add(ip.getHostName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("IP地址获取失败" + e.toString());
        }
        return ips;
    }
}
```



# 三、通信方式概述

| 类型                 | 特点                 | 应用场景                       |
| -------------------- | -------------------- | ------------------------------ |
| **单播 (Unicast)**   | 一对一通信           | 客户端与服务器通信，如网页访问 |
| **广播 (Broadcast)** | 一对所有设备通信     | 局域网设备发现、DHCP、ARP      |
| **多播 (Multicast)** | 一对多通信（指定组） | 视频会议、组消息推送           |

下面是 Java 中 **单播（Unicast）**、**广播（Broadcast）**、**多播（Multicast）** 的完整案例示例，每种通信方式均含 **客户端与服务端** 源码，可直接运行理解差异。

------

## 🧩 一、单播（Unicast）

> 一对一通信 —— 客户端直接与服务器通信。

### ✅ 服务端：`UnicastServer.java`

```java
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class UnicastServer {
    public static void main(String[] args) throws IOException {
        final int PORT = 8888;
        byte[] buf = new byte[1024];
        DatagramSocket socket = new DatagramSocket(PORT);
        System.out.println("单播服务器启动，监听端口 " + PORT);

        while (true) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);
            String msg = new String(packet.getData(), 0, packet.getLength());
            System.out.println("收到来自客户端的消息：" + msg);

            // 回复客户端
            String reply = "服务器已收到：" + msg;
            DatagramPacket response = new DatagramPacket(
                    reply.getBytes(),
                    reply.length(),
                    packet.getAddress(),
                    packet.getPort()
            );
            socket.send(response);
        }
    }
}
```

### ✅ 客户端：`UnicastClient.java`

```java
import java.io.IOException;
import java.net.*;

public class UnicastClient {
    public static void main(String[] args) throws IOException {
        InetAddress serverAddr = InetAddress.getByName("127.0.0.1");
        int serverPort = 8888;

        DatagramSocket socket = new DatagramSocket();
        String message = "Hello Unicast Server";
        DatagramPacket packet = new DatagramPacket(
                message.getBytes(),
                message.length(),
                serverAddr,
                serverPort
        );
        socket.send(packet);
        System.out.println("已发送: " + message);

        byte[] buf = new byte[1024];
        DatagramPacket response = new DatagramPacket(buf, buf.length);
        socket.receive(response);
        System.out.println("收到回复: " + new String(response.getData(), 0, response.getLength()));
        socket.close();
    }
}
```

------

## 🛰️ 二、广播（Broadcast）

> 一对所有设备通信 —— 向局域网广播包，所有监听端口的设备均能收到。

### ✅ 服务端：`BroadcastServer.java`

```java
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class BroadcastServer {
    public static void main(String[] args) throws IOException {
        int port = 1500;
        DatagramSocket socket = new DatagramSocket(port);
        socket.setBroadcast(true);
        byte[] buf = new byte[1024];
        System.out.println("广播服务器启动，监听端口 " + port);

        while (true) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);
            String msg = new String(packet.getData(), 0, packet.getLength());
            System.out.println("收到广播消息：" + msg + " 来自 " + packet.getAddress().getHostAddress());

            String reply = "广播服务器已收到消息";
            DatagramPacket resp = new DatagramPacket(
                    reply.getBytes(),
                    reply.length(),
                    packet.getAddress(),
                    packet.getPort()
            );
            socket.send(resp);
        }
    }
}
```

### ✅ 客户端：`BroadcastClient.java`

```java
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class BroadcastClient {
    public static void main(String[] args) throws IOException {
        int port = 1500;
        DatagramSocket socket = new DatagramSocket();
        socket.setBroadcast(true);

        String message = "Hello Broadcast!";
        InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");
        DatagramPacket packet = new DatagramPacket(
                message.getBytes(StandardCharsets.UTF_8),
                message.length(),
                broadcastAddr,
                port
        );
        socket.send(packet);
        System.out.println("已发送广播: " + message);

        byte[] buf = new byte[1024];
        DatagramPacket response = new DatagramPacket(buf, buf.length);
        socket.receive(response);
        System.out.println("收到回复: " + new String(response.getData(), 0, response.getLength()));
        socket.close();
    }
}
```

> ⚠️ 注意：广播只能在 **同一局域网内** 工作，且部分系统需关闭防火墙或允许 UDP 1500 端口通信。

------

## 🌐 三、多播（Multicast）

> 一对多通信 —— 发送方只向一个“组地址”发送，加入该组的主机都能接收。

### ✅ 服务端：`MulticastServer.java`

```java
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

public class MulticastServer {
    public static void main(String[] args) throws IOException {
        String groupAddr = "239.0.0.255"; // D类组播地址范围：224.0.0.0 ~ 239.255.255.255
        int port = 8899;

        MulticastSocket socket = new MulticastSocket(port);
        InetAddress group = InetAddress.getByName(groupAddr);
        socket.joinGroup(group);
        System.out.println("多播服务器加入组 " + groupAddr + "，端口 " + port);

        byte[] buf = new byte[1024];
        while (true) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);
            String msg = new String(packet.getData(), 0, packet.getLength());
            System.out.println("收到组播消息：" + msg);
        }
    }
}
```

### ✅ 客户端：`MulticastClient.java`

```java
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

public class MulticastClient {
    public static void main(String[] args) throws IOException {
        String groupAddr = "239.0.0.255";
        int port = 8899;
        MulticastSocket socket = new MulticastSocket();
        InetAddress group = InetAddress.getByName(groupAddr);

        String message = "Hello Multicast Group!";
        DatagramPacket packet = new DatagramPacket(
                message.getBytes(),
                message.length(),
                group,
                port
        );
        socket.send(packet);
        System.out.println("已发送组播消息: " + message);
        socket.close();
    }
}
```

> ✅ **组播特性**：
>
> - 需使用 D 类 IP（`224.0.0.0~239.255.255.255`）。
> - 设备需主动 `joinGroup()` 才能接收。
> - 支持跨子网，但需路由器支持 IGMP 协议。

------

## 📘 四、对比总结

| 类型               | 特征         | 地址范围                       | 应用场景              |
| ------------------ | ------------ | ------------------------------ | --------------------- |
| **单播 Unicast**   | 一对一       | 普通 IP                        | 普通客户端-服务器通信 |
| **广播 Broadcast** | 一对所有     | 255.255.255.255 / 子网广播地址 | 局域网设备发现        |
| **多播 Multicast** | 一对多（组） | 224.0.0.0~239.255.255.255      | 流媒体、组数据同步    |

------





# 四、广播基础知识

### 1. 广播地址类型

- **受限广播**：`255.255.255.255`，仅在本地网段生效。
- **定向广播**：如 `192.168.1.255`，仅局域网内设备可接收。

### 2. 计算广播地址

以 IP `192.168.73.88`、子网掩码 `255.255.255.192` 为例：

- 网络地址：`192.168.73.64`
- 广播地址：`192.168.73.127`
- 该网段设备通信范围：`192.168.73.65 ~ 192.168.73.126`

```
IP         192.168.73.88        11000000    10101000    01001001    01011000
子网掩码    255.255.255.192      11111111    11111111    11111111    11000000
网络地址    192.168.73.64        11000000    10101000    01001001    01000000    
广播地址    192.168.73.127       11000000    10101000    01001001    01111111
该网段设备通信范围：192.168.73.65[01000001] ~ 192.168.73.126[01111110]
```





# IP、子网掩码、网络地址、广播地址

------

## 🧩 一、四者的定义与关系

| 名称                              | 定义                                                         | 作用                     |
| --------------------------------- | ------------------------------------------------------------ | ------------------------ |
| **IP 地址**                       | 网络中每台设备的唯一标识，由 32 位二进制组成（如 `192.168.1.6`） | 用于识别具体的主机       |
| **子网掩码（Subnet Mask）**       | 与 IP 一起使用，表示“哪部分是网络号、哪部分是主机号”         | 决定网络范围与广播范围   |
| **网络地址（Network Address）**   | 某网段的起始地址（所有主机号为 0）                           | 表示整个网络本身         |
| **广播地址（Broadcast Address）** | 某网段的结束地址（所有主机号为 1）                           | 向该网段所有设备广播消息 |

------

## 🧠 二、逻辑关系示意图

```
IP 地址:         192.168.1.6          → 二进制: 11000000.10101000.00000001.00000110
子网掩码:        255.255.255.0        → 二进制: 11111111.11111111.11111111.00000000
——————————————————————————————————————————————————————————
网络部分 (前24位): 11000000.10101000.00000001
主机部分 (后8位):                             00000110
```

### 运算：

- 网络地址 = IP地址 **AND** 子网掩码
- 广播地址 = 网络地址 **OR** 子网掩码的反码

------

## 🔢 三、计算示例 1（常见 C 类网段）

| 项目           | 值                                  |
| -------------- | ----------------------------------- |
| IP 地址        | 192.168.1.6                         |
| 子网掩码       | 255.255.255.0                       |
| 二进制子网掩码 | 11111111.11111111.11111111.00000000 |
| 网络地址       | 192.168.1.0                         |
| 广播地址       | 192.168.1.255                       |
| 主机地址范围   | 192.168.1.1 ~ 192.168.1.254         |

👉 **分析：**

- 前三段为网络号（192.168.1）；
- 最后一段为主机号；
- 所有主机号为 0 表示网络地址；
- 所有主机号为 1 表示广播地址。

------

## 🔢 四、计算示例 2（自定义子网掩码）

| 项目           | 值                                  |
| -------------- | ----------------------------------- |
| IP 地址        | 192.168.73.88                       |
| 子网掩码       | 255.255.255.192                     |
| 二进制         | 11111111.11111111.11111111.11000000 |
| 每个子网主机数 | 2^(32-26) - 2 = 62 台               |
| 网络划分       | 4 个网段（0、64、128、192）         |
| 网络地址       | 192.168.73.64                       |
| 广播地址       | 192.168.73.127                      |
| 主机地址范围   | 192.168.73.65 ~ 192.168.73.126      |

👉 **解释：**

- `255.255.255.192` 中的 `192` = `11000000`，表示前 26 位为网络位；
- 每个子网包含 64 个地址，其中 62 个可用；
- 广播地址是该子网最后一个地址（127）。

------

## 🧮 五、计算规律总结

| 计算项目     | 计算方法                     | 结果说明                      |
| ------------ | ---------------------------- | ----------------------------- |
| 网络地址     | IP地址 **AND** 子网掩码      | 网络的起始地址                |
| 广播地址     | 网络地址 **OR** 子网掩码取反 | 网络的最后一个地址            |
| 可用主机数   | 2^(32−掩码位数) − 2          | 减 2 是去掉网络地址和广播地址 |
| 主机地址范围 | 网络地址 + 1 ～ 广播地址 − 1 | 实际可分配范围                |

------

## 🌐 六、常见子网掩码对应主机数

| 子网掩码        | 掩码位 | 每网段主机数 | 网络划分数（C类） |
| --------------- | ------ | ------------ | ----------------- |
| 255.255.255.0   | /24    | 254          | 1 个              |
| 255.255.255.128 | /25    | 126          | 2 个              |
| 255.255.255.192 | /26    | 62           | 4 个              |
| 255.255.255.224 | /27    | 30           | 8 个              |
| 255.255.255.240 | /28    | 14           | 16 个             |
| 255.255.255.248 | /29    | 6            | 32 个             |
| 255.255.255.252 | /30    | 2            | 64 个             |

------

## ⚙️ 七、广播通信特性

1. 广播只能在**同一子网内**传播（路由器一般不会转发广播包）；
2. 不同子网的广播地址 **互不影响**；
3. 若设置了错误的广播地址或子网掩码，将导致：
   - 设备无法发现；
   - 广播包无法发出或无响应。

------

## 📘 八、可视化理解总结

```
+---------------------------------------------------+
| 网络地址: 192.168.1.0                            |
| 可用主机: 192.168.1.1  ~ 192.168.1.254            |
| 广播地址: 192.168.1.255                          |
+---------------------------------------------------+
         ↑              ↑                ↑
       网络号         主机号区间         广播号
```

------

## ✅ 九、总结要点

| 项目       | 含义                             | 关键点                     |
| ---------- | -------------------------------- | -------------------------- |
| 网络地址   | 网段标识                         | 主机号全 0                 |
| 广播地址   | 网段末尾地址                     | 主机号全 1                 |
| 子网掩码   | 划分边界                         | 1 表示网络位，0 表示主机位 |
| 计算方法   | `网络 = IP & 掩码`；`广播 = 网络 | (~掩码)`                   |
| 局域网广播 | 只能在本网段传播                 | 典型如 `192.168.1.255`     |

------

