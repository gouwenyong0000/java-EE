package com.atguigu.kaiser;

/**
 * KaiserDemo 类实现了凯撒加密和解密的功能。
 * 凯撒加密是一种简单的替换加密技术，通过将每个字母替换为字母表中固定位置的另一个字母来实现。
 * 该类提供了两个主要方法：encryptKaiser 和 decryptKaiser，分别用于加密和解密字符串。
 */
public class KaiserDemo1 {

    private static final char UPPER_A = 'A';
    private static final char LOWER_A = 'a';
    private static final int ALPHABET_SIZE = 26;

    public static void main(String[] args) {
        // 测试数据：输入一个包含所有小写字母的字符串
        String input = "abcdefghijklmnopqrstuvwxyz";

        // 调用加密方法，密钥为3
        String s = encryptKaiser(input, 3);
        System.out.println("加密：" + s);

        // 调用解密方法，密钥为3
        String s1 = decryptKaiser(s, 3);
        System.out.println("解密：" + s1);
    }

    /**
     * 使用凯撒加密方式解密数据
     *
     * @param encryptedData : 密文
     * @param key           : 密钥
     * @return : 源数据
     */
    public static String decryptKaiser(String encryptedData, int key) {
        return transform(encryptedData, -key);
    }

    /**
     * 凯撒密码加密函数，仅对英文字母进行偏移，保留大小写
     *
     * @param original : 原文
     * @param key      : 密钥
     * @return : 加密后的数据
     */
    public static String encryptKaiser(String original, int key) {
        return transform(original, key);
    }

    /**
     * 核心转换方法，用于加密或解密
     *
     * @param text 原文或密文
     * @param shift 偏移量（正为加密，负为解密）
     * @return 转换后的文本
     */
    private static String transform(String text, int shift) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        int normalizedShift = Math.floorMod(shift, ALPHABET_SIZE);// 确保偏移量在有效范围内 3 --> 3   -3-->23
        char[] chars = text.toCharArray();
        StringBuilder sb = new StringBuilder(chars.length);

        for (char c : chars) {
            if (Character.isLetter(c)) {
                char base = Character.isUpperCase(c) ? UPPER_A : LOWER_A;
                int offset = (c - base + normalizedShift) % ALPHABET_SIZE;
                sb.append((char) (base + offset));
            } else {
                sb.append(c); // 非字母字符保持不变
            }
        }

        return sb.toString();
    }
}
