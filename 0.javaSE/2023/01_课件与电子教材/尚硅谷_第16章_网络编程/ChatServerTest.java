//package com.atguigu02.tcpudp.chat;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;

/**
 * �����������ҵ�ʵ�� ���������ˣ�
 *
 * @author �й��-�κ쿵
 * @create 16:45
 */
public class ChatServerTest {
	//������������洢�������ߵĿͻ���
	static ArrayList<Socket> online = new  ArrayList<Socket>();
	
	public static void main(String[] args)throws Exception {
		//1���������������󶨶˿ں�
		ServerSocket server = new ServerSocket(8989);
		
		//2������n��Ŀͻ���ͬʱ����
		while(true){
			Socket socket = server.accept(); //����ʽ�ķ���
			
			online.add(socket);//�������ӵĿͻ�����ӵ�online�б���

			//��Ҫ�����ȡ��ǰsocket�е����ݣ����ַ�����ǰ�����ҵ����еĿͻ��ˡ�
			MessageHandler mh = new MessageHandler(socket);
			mh.start();//
		}
	}
	
	static class MessageHandler extends Thread{
		private Socket socket;
		private String ip;
		
		public MessageHandler(Socket socket) {
			super();
			this.socket = socket;
		}

		public void run(){
			try {
				ip = socket.getInetAddress().getHostAddress();
				
				//���룺�������ͻ���ת�����������ˡ�
				sendToOther(ip+"������");
				
				//(1)���ոÿͻ��˵ķ��͵���Ϣ
				InputStream input = socket.getInputStream();
				InputStreamReader reader = new InputStreamReader(input);
				BufferedReader br = new BufferedReader(reader);
				
				String str;
				while((str = br.readLine())!=null){
					//(2)���������߿ͻ���ת��
					sendToOther(ip+":"+str);
				}
				
				sendToOther(ip+"������");
			} catch (IOException e) {
				try {
					sendToOther(ip+"������");
				} catch (IOException e1) {
					e1.printStackTrace();
				}
			}finally{
				//��������Ա���Ƴ���
				online.remove(socket);
			}
		}
		
		//��װһ���������������ͻ���ת��xxx��Ϣ
		public void sendToOther(String message) throws IOException{
			//�������е����߿ͻ��ˣ�һһת��
			for (Socket on : online) {
				OutputStream every = on.getOutputStream();
				//Ϊʲô��PrintStream��Ŀ��������println���������д�ӡ
				PrintStream ps = new PrintStream(every);
				
				ps.println(message);
			}
		}
	}
}

