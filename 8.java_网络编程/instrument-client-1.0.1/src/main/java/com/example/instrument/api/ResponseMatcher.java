package com.example.instrument.api;

import com.example.instrument.model.Response;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 响应匹配器接口，用于判断收到的响应是否符合预期。
 * 
 * 该接口采用函数式接口设计，可以方便地使用 lambda 表达式或方法引用来定义匹配逻辑。
 * 主要用途是在多个并发请求时，根据响应内容将响应分发给对应的请求。
 * 
 * <p>使用示例：</p>
 * <pre>{@code
 * // 使用内置工厂方法
 * ResponseMatcher matcher = ResponseMatcher.contains("OK");
 * ResponseMatcher matcher2 = ResponseMatcher.equalsText("ERROR");
 * ResponseMatcher matcher3 = ResponseMatcher.regex("^DATA:.*$");
 * 
 * // 使用自定义 predicate
 * ResponseMatcher matcher4 = ResponseMatcher.predicate(r -> r.text().startsWith("INFO"));
 * 
 * // 使用 lambda
 * ResponseMatcher matcher5 = response -> response.text().contains("success");
 * }</pre>
 * 
 * @see Response
 * @see com.example.instrument.api.InstrumentClient#request
 */
@FunctionalInterface
public interface ResponseMatcher {
    
    /**
     * 判断给定响应是否符合匹配条件。
     *
     * @param response 要检查的响应，不能为空
     * @return 如果响应符合匹配条件返回 true，否则返回 false
     */
    boolean matches(Response response);

    /**
     * 创建一个始终返回 true 的匹配器，接受任何响应。
     * 
     * 适用于只有一个请求且关心任何响应结果的场景。
     *
     * @return 匹配任何响应的 ResponseMatcher 实例
     */
    static ResponseMatcher any() { 
        return r -> true; 
    }

    /**
     * 使用自定义 Predicate 创建匹配器。
     *
     * @param predicate 用于判断响应的 predicate，不能为空
     * @return 基于给定 predicate 的 ResponseMatcher 实例
     * @throws NullPointerException 如果 predicate 为空
     */
    static ResponseMatcher predicate(Predicate<Response> predicate) {
        Objects.requireNonNull(predicate);
        return predicate::test;
    }

    /**
     * 创建一个匹配包含指定文本的响应的匹配器。
     * 
     * 使用 UTF-8 编码进行文本比较。
     *
     * @param text 要查找的文本，不能为空
     * @return 匹配包含指定文本的 ResponseMatcher 实例
     * @throws NullPointerException 如果 text 为空
     */
    static ResponseMatcher contains(String text) { 
        return contains(text, StandardCharsets.UTF_8); 
    }

    /**
     * 创建一个匹配包含指定文本的响应的匹配器。
     *
     * @param text    要查找的文本，不能为空
     * @param charset 用于将响应转换为字符串的字符集，不能为空
     * @return 匹配包含指定文本的 ResponseMatcher 实例
     * @throws NullPointerException 如果 text 或 charset 为空
     */
    static ResponseMatcher contains(String text, Charset charset) {
        Objects.requireNonNull(text); 
        Objects.requireNonNull(charset);
        return r -> r.text(charset).contains(text);
    }

    /**
     * 创建一个匹配文本完全相等的响应的匹配器。
     * 
     * 使用 UTF-8 编码进行文本比较。
     *
     * @param text 要匹配的精确文本，不能为空
     * @return 匹配文本完全相等的 ResponseMatcher 实例
     * @throws NullPointerException 如果 text 为空
     */
    static ResponseMatcher equalsText(String text) { 
        return equalsText(text, StandardCharsets.UTF_8); 
    }

    /**
     * 创建一个匹配文本完全相等的响应的匹配器。
     *
     * @param text    要匹配的精确文本，不能为空
     * @param charset 用于将响应转换为字符串的字符集，不能为空
     * @return 匹配文本完全相等的 ResponseMatcher 实例
     * @throws NullPointerException 如果 text 或 charset 为空
     */
    static ResponseMatcher equalsText(String text, Charset charset) {
        Objects.requireNonNull(text); 
        Objects.requireNonNull(charset);
        return r -> r.text(charset).equals(text);
    }

    /**
     * 创建一个使用正则表达式匹配响应文本的匹配器。
     * 
     * 使用 UTF-8 编码，并将正则表达式编译为 DOTALL 模式（使 . 匹配换行符）。
     *
     * @param regex 用于匹配的正则表达式，不能为空
     * @return 匹配正则表达式的 ResponseMatcher 实例
     * @throws NullPointerException 如果 regex 为空
     * @throws java.util.regex.PatternSyntaxException 如果正则表达式语法无效
     */
    static ResponseMatcher regex(String regex) { 
        return regex(regex, StandardCharsets.UTF_8); 
    }

    /**
     * 创建一个使用正则表达式匹配响应文本的匹配器。
     * 
     * 将正则表达式编译为 DOTALL 模式（使 . 匹配换行符）。
     *
     * @param regex   用于匹配的正则表达式，不能为空
     * @param charset 用于将响应转换为字符串的字符集，不能为空
     * @return 匹配正则表达式的 ResponseMatcher 实例
     * @throws NullPointerException 如果 regex 或 charset 为空
     * @throws java.util.regex.PatternSyntaxException 如果正则表达式语法无效
     */
    static ResponseMatcher regex(String regex, Charset charset) {
        Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);
        return r -> pattern.matcher(r.text(charset)).matches();
    }
}