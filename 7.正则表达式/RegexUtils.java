package com.example.demo.controller;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class RegexUtils {

    // ========== 基础功能 ==========
    
    public static boolean matches(String input, String pattern) {
        if (input == null || pattern == null) return false;
        return Pattern.matches(pattern, input);
    }

    public static String findFirstMatch(String input, String pattern) {
        if (input == null || pattern == null) return null;
        Matcher matcher = Pattern.compile(pattern).matcher(input);
        return matcher.find() ? matcher.group() : null;
    }

    public static List<String> findAllMatches(String input, String pattern) {
        List<String> matches = new ArrayList<>();
        if (input == null || pattern == null) return matches;

        Matcher matcher = Pattern.compile(pattern).matcher(input);
        while (matcher.find()) {
            matches.add(matcher.group());
        }
        return matches;
    }

    public static String findGroup(String input, String pattern, int group) {
        if (input == null || pattern == null || group < 1) return null;
        Matcher matcher = Pattern.compile(pattern).matcher(input);
        return matcher.find() ? matcher.group(group) : null;
    }

    public static String replaceAll(String input, String pattern, String replacement) {
        if (input == null || pattern == null || replacement == null) return input;
        return input.replaceAll(pattern, replacement);
    }

    // ========== 验证常用格式 ==========
    
    public static boolean isEmail(String email) {
        return matches(email, "^\\w+([-+.]\\w+)*@\\w+([-.]\\w+)*\\.\\w+([-.]\\w+)*$");
    }

    public static boolean isPhoneNumber(String phone) {
        return matches(phone, "^1[3-9]\\d{9}$");
    }

    public static boolean isUrl(String url) {
        return matches(url, "^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]$");
    }

    public static boolean isIpAddress(String ip) {
        return matches(ip, "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$");
    }


}
