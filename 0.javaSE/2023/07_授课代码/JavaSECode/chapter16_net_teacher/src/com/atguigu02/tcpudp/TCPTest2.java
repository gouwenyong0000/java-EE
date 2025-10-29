package com.atguigu02.tcpudp;

import org.junit.Test;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * ClassName: TCPTest2
 * Description:
 *      例题2：客户端发送文件给服务端，服务端将文件保存在本地。
 * @Author 尚硅谷-宋红康
 * @Create 10:10
 * @Version 1.0
 */
public class TCPTest2 {
    /*
    * 注意：因为涉及到相关资源的关闭，需要使用try-catch-finally处理异常
    * */
    //客户端
    @Test
    public void client() throws IOException {
        //1. 创建Socket
        //指明对方（即为服务器端）的ip地址和端口号
        InetAddress inetAddress = InetAddress.getByName("127.0.0.1");
        int port = 9090;
        Socket socket = new Socket(inetAddress,port);

        //2. 创建File的实例、FileInputStream的实例
        File file = new File("pic.jpg");
        FileInputStream fis = new FileInputStream(file);
        //3. 通过Socket，获取输出流
        OutputStream os = socket.getOutputStream();

        //4. 读写数据
        byte[] buffer = new byte[1024];
        int len;
        while((len = fis.read(buffer)) != -1){
            os.write(buffer,0,len);
        }
        System.out.println("数据发送完毕");

        //5. 关闭Socket和相关的流
        os.close();
        fis.close();
        socket.close();

    }

    //服务端
    @Test
    public void server() throws IOException {
        //1. 创建ServerSocket
        int port = 9090;
        ServerSocket serverSocket = new ServerSocket(port);
        //2. 接收来自于客户端的socket:accept()
        Socket socket = serverSocket.accept();

        //3. 通过Socket获取一个输入流
        InputStream is = socket.getInputStream();

        //4. 创建File类的实例、FileOutputStream的实例
        File file = new File("pic_copy.jpg");
        FileOutputStream fos = new FileOutputStream(file);

        //5. 读写过程
        byte[] buffer = new byte[1024];
        int len;
        while((len = is.read(buffer)) != -1){
            fos.write(buffer,0,len);
        }

        System.out.println("数据接收完毕");

        //6. 关闭相关的Socket和流
        fos.close();
        is.close();
        socket.close();
        serverSocket.close();
    }
}
