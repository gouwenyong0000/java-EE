//package com.atguigu02.tcpudp.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.util.Scanner;

/**
 * �����������ҵ�ʵ�� ���ͻ��ˣ�
 *
 * @author �й��-�κ쿵
 * @create 16:42
 */
public class ChatClientTest {
	public static void main(String[] args)throws Exception {
		//1�����ӷ�����
		Socket socket = new Socket("127.0.0.1",8989);
		
		//2�����������߳�
		//(1)һ���̸߳��𿴱����ģ������շ�����ת������Ϣ
		Receive receive = new Receive(socket);
		receive.start();
		
		//(2)һ���̸߳������Լ��Ļ�
		Send send = new Send(socket);
		send.start();
		
		send.join();//���ҷ����߳̽����ˣ��Ž�����������
		
		socket.close();
	}
}
class Send extends Thread{
	private Socket socket;
	
	public Send(Socket socket) {
		super();
		this.socket = socket;
	}

	public void run(){
		try {
			Scanner input = new Scanner(System.in);

			OutputStream outputStream = socket.getOutputStream();
			//���д�ӡ
			PrintStream ps = new PrintStream(outputStream);
			
			//�Ӽ��̲��ϵ������Լ��Ļ��������������ͣ��ɷ�������������ת��
			while(true){
				System.out.print("�Լ��Ļ���");
				String str = input.nextLine(); //����ʽ�ķ���
				if("bye".equals(str)){
					break;
				}
				ps.println(str);
			}
			
			input.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
}
class Receive extends Thread{
	private Socket socket;
	
	public Receive(Socket socket) {
		super();
		this.socket = socket;
	}
	
	public void run(){
		try {
			InputStream inputStream = socket.getInputStream();
			Scanner input = new Scanner(inputStream);
			
			while(input.hasNextLine()){
				String line = input.nextLine();
				System.out.println(line);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}