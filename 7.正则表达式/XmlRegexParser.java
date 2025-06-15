package com.example.demo.controller;


import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * XmlParser类用于解析HTML文件中的链接，并将其导出到Excel文件中
 */
public class XmlRegexParser {

    /**
     * 主程序入口
     * 读取HTML文件，提取链接信息，并导出到Excel
     */
    public static void main(String[] args) {
        // HTML文件路径
        String path = "C:\\Users\\gouwe\\Desktop\\bookmarks_2025_6_11.html";
        try {
            // 读取HTML文件内容
            String htmlContent = readHtmlFile(path);
            // 提取HTML内容中的链接
            List<LinkData> links = extractLinks(htmlContent);
            // 将链接信息写入Excel文件
            writeLinksToExcel(links, "links_data.xlsx");
            System.out.println("导出成功！");
        } catch (Exception e) {
            System.err.println("发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 读取HTML文件内容
     *
     * @param path HTML文件的路径
     * @return HTML文件的内容
     * @throws Exception 如果读取文件时发生错误
     */
    private static String readHtmlFile(String path) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(path));
        return String.join("\n", lines);
    }

    /**
     * 从HTML内容中提取链接信息
     *
     * @param htmlContent HTML文件的内容
     * @return 包含所有链接信息的列表
     */
    private static List<LinkData> extractLinks(String htmlContent) throws URISyntaxException {
        List<LinkData> links = new java.util.ArrayList<>();
        // 正则表达式匹配HTML中的<a>标签
        String regex = "<a\\s+[^>]*href=(\"([^\"]*)\"|'([^']*)')?[^>]*>(.*?)</a>";
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);//忽略大小写
        Matcher matcher = pattern.matcher(htmlContent);

        while (matcher.find()) {
            String fullTag = matcher.group(0);
            String href = matcher.group(2) != null ? matcher.group(2) : (matcher.group(3) != null ? matcher.group(3) : "");
            URI uri = new URI(href);
            String domain = uri.getHost();
            int port = uri.getPort();

            String text = matcher.group(4);


            links.add(new LinkData(fullTag, href, text, domain, port));
        }

        return links;
    }

    /**
     * 将链接信息写入Excel文件
     *
     * @param links      包含所有链接信息的列表
     * @param outputPath Excel文件的输出路径
     * @throws Exception 如果写入Excel文件时发生错误
     */
    private static void writeLinksToExcel(List<LinkData> links, String outputPath) throws Exception {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("链接数据");

        Row headerRow = sheet.createRow(0);
        headerRow.createCell(0).setCellValue("完整标签");
        headerRow.createCell(1).setCellValue("链接地址");
        headerRow.createCell(2).setCellValue("显示文本");
        headerRow.createCell(3).setCellValue("域名");
        headerRow.createCell(4).setCellValue("端口");

        int rowNum = 1;
        for (LinkData link : links) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(link.fullTag);
            row.createCell(1).setCellValue(link.href);
            row.createCell(2).setCellValue(link.text);
            row.createCell(3).setCellValue(link.domain);
            row.createCell(4).setCellValue(link.port);
        }

        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
            workbook.write(fos);
        }
    }

    /**
     * LinkData类用于存储链接信息
     */
    static class LinkData {
        String fullTag;
        String href;
        String text;
        String  domain;
        int port;

        /**
         * 构造函数初始化LinkData对象
         *
         * @param fullTag 完整的<a>标签
         * @param href    链接地址
         * @param text    显示文本
         */
        public LinkData(String fullTag, String href, String text,  String domain, int port) {
            this.fullTag = fullTag;
            this.href = href;
            this.text = text;
            this.domain = domain;
            this.port = port;
        }
    }
}
