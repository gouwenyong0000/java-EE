package com.atguigu.kaiser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;

public class Util {

	/**
	 * 打印字节数组
	 * @param bytes
	 */
	public static void print(byte[] bytes) {
		StringBuffer sb = new StringBuffer();
		for (int i = 0; i < bytes.length; i++) {
			sb.append(bytes[i]).append(" ");
		}
		System.out.println(sb);
	}

	/**
	 *  文件转字符串
	 * @param path
	 * @return
	 * @throws IOException
	 */
	public static String file2String(String path) throws IOException {
		FileReader reader = new FileReader(new File(path));
		char[] buffer = new char[1024];
		int len = -1;
		StringBuffer sb = new StringBuffer();
		while ((len = reader.read(buffer)) != -1) {
			sb.append(buffer, 0, len);
		}
		return sb.toString();
	}

	/**
	 *  字符串转文件
	 * @param data
	 * @param path
	 */
	public static void string2File(String data, String path){
		FileWriter writer = null;
		try {
			writer = new FileWriter(new File(path));
			writer.write(data);
		} catch (Exception e) {
			e.printStackTrace();
		}finally {
			if (writer != null) {
				try {
					writer.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

	}

	/**
	 * 输入流转字符串
	 * @param in
	 * @return
	 * @throws IOException
	 */
	public static String inputStream2String(InputStream in) throws IOException {
		int len = -1;
		byte[] buffer = new byte[1024];
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		while((len = in.read(buffer)) != -1) {
			baos.write(buffer, 0, len);
		}
		baos.close();
		
		return baos.toString("UTF-8");
	}
	
	
}
