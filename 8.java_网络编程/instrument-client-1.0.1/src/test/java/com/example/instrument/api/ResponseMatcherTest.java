package com.example.instrument.api;

import static org.junit.jupiter.api.Assertions.*;

import com.example.instrument.core.model.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

@DisplayName("ResponseMatcher 匹配器测试")
class ResponseMatcherTest {

    private static Response resp(String text) {
        byte[] frame = (text + "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        return new Response(frame, body);
    }

    @Nested
    @DisplayName("内置工厂方法")
    class FactoryMethods {

        @Test
        @DisplayName("any() 匹配任意响应")
        void anyMatchesEverything() {
            ResponseMatcher m = ResponseMatcher.any();
            assertTrue(m.matches(resp("hello")));
            assertTrue(m.matches(resp("")));
            assertTrue(m.matches(resp("123")));
        }

        @Test
        @DisplayName("contains() 检查子串")
        void containsMatchesSubstring() {
            ResponseMatcher m = ResponseMatcher.contains("OK");
            assertTrue(m.matches(resp("OK")));
            assertTrue(m.matches(resp("OK,123")));
            assertTrue(m.matches(resp("DATA OK END")));
            assertFalse(m.matches(resp("FAIL")));
            assertFalse(m.matches(resp("")));
        }

        @Test
        @DisplayName("equalsText() 完全匹配")
        void equalsTextExact() {
            ResponseMatcher m = ResponseMatcher.equalsText("IDN");
            assertTrue(m.matches(resp("IDN")));
            assertFalse(m.matches(resp("IDN_OK")));
            assertFalse(m.matches(resp("idn")));
        }

        @Test
        @DisplayName("regex() 正则匹配")
        void regexMatching() {
            ResponseMatcher m = ResponseMatcher.regex("^\\d+$");
            assertTrue(m.matches(resp("12345")));
            assertFalse(m.matches(resp("abc")));
            assertFalse(m.matches(resp("123abc")));
        }

        @Test
        @DisplayName("regex() DOTALL 模式使 . 匹配换行")
        void regexDotallMatchesNewlines() {
            Response r = new Response(
                "line1\nline2\r\n".getBytes(StandardCharsets.UTF_8),
                "line1\nline2".getBytes(StandardCharsets.UTF_8)
            );
            ResponseMatcher m = ResponseMatcher.regex("line1.line2");
            assertTrue(m.matches(r));
        }

        @Test
        @DisplayName("never() 永不匹配")
        void neverMatchesNothing() {
            ResponseMatcher m = ResponseMatcher.never();
            assertFalse(m.matches(resp("anything")));
            assertFalse(m.matches(resp("")));
        }

        @Test
        @DisplayName("predicate() 自定义条件")
        void customPredicate() {
            ResponseMatcher m = ResponseMatcher.predicate(
                r -> r.text(StandardCharsets.UTF_8).length() > 3
            );
            assertTrue(m.matches(resp("long")));
            assertTrue(m.matches(resp("abcdef")));
            assertFalse(m.matches(resp("ab")));
        }
    }

    @Nested
    @DisplayName("组合器")
    class Combinators {

        @Test
        @DisplayName("and() 与逻辑")
        void andCombination() {
            ResponseMatcher m = ResponseMatcher.contains("IDN")
                .and(ResponseMatcher.contains("OK"));

            assertTrue(m.matches(resp("IDN,OK")));
            assertFalse(m.matches(resp("IDN,FAIL")));
            assertFalse(m.matches(resp("OK")));
        }

        @Test
        @DisplayName("or() 或逻辑")
        void orCombination() {
            ResponseMatcher m = ResponseMatcher.equalsText("OK")
                .or(ResponseMatcher.equalsText("YES"));

            assertTrue(m.matches(resp("OK")));
            assertTrue(m.matches(resp("YES")));
            assertFalse(m.matches(resp("NO")));
            assertFalse(m.matches(resp("")));
        }

        @Test
        @DisplayName("negate() 取反")
        void negateCombination() {
            ResponseMatcher m = ResponseMatcher.never().negate();

            assertTrue(m.matches(resp("anything")));
            assertTrue(m.matches(resp("")));

            ResponseMatcher notOK = ResponseMatcher.equalsText("OK").negate();
            assertFalse(notOK.matches(resp("OK")));
            assertTrue(notOK.matches(resp("FAIL")));
        }

        @Test
        @DisplayName("多重组合")
        void chainedCombinators() {
            ResponseMatcher containsIdn = ResponseMatcher.contains("IDN");
            ResponseMatcher containsOk = ResponseMatcher.contains("OK");
            ResponseMatcher notError = ResponseMatcher.contains("ERR").negate();

            ResponseMatcher complex = containsIdn.and(containsOk).and(notError);

            assertTrue(complex.matches(resp("IDN,OK")));
            assertFalse(complex.matches(resp("IDN,OK,ERR")));
            assertFalse(complex.matches(resp("IDN")));
            assertFalse(complex.matches(resp("OK")));
        }

        @Test
        @DisplayName("and() 空参数抛出异常")
        void andNullParamThrows() {
            assertThrows(NullPointerException.class, () ->
                ResponseMatcher.any().and(null));
        }

        @Test
        @DisplayName("or() 空参数抛出异常")
        void orNullParamThrows() {
            assertThrows(NullPointerException.class, () ->
                ResponseMatcher.any().or(null));
        }
    }

    @Nested
    @DisplayName("equalsText 不同字符集")
    class Encoding {

        @Test
        @DisplayName("equalsText 使用默认 UTF-8")
        void equalsTextDefaultCharset() {
            ResponseMatcher m = ResponseMatcher.equalsText("你好");
            assertTrue(m.matches(resp("你好")));
        }

        @Test
        @DisplayName("contains 使用指定字符集")
        void containsWithCharset() {
            ResponseMatcher m = ResponseMatcher.contains("hello", StandardCharsets.US_ASCII);
            assertTrue(m.matches(resp("hello")));
            assertFalse(m.matches(resp("world")));
        }
    }
}