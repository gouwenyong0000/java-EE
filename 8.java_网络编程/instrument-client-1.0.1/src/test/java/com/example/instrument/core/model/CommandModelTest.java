package com.example.instrument.core.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

@DisplayName("Command / Response 模型测试")
class CommandModelTest {

    @Nested
    @DisplayName("Command 创建")
    class CommandCreation {

        @Test
        @DisplayName("从字节数组创建")
        void fromBytes() {
            byte[] raw = {0x01, 0x02, 0x03};
            Command cmd = Command.of(raw);
            assertArrayEquals(raw, cmd.bytes());
        }

        @Test
        @DisplayName("bytes() 返回克隆副本")
        void bytesReturnsClone() {
            byte[] raw = {0x01, 0x02, 0x03};
            Command cmd = Command.of(raw);
            byte[] result = cmd.bytes();
            result[0] = 0x7F;
            assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, cmd.bytes(),
                "修改返回的数组不应影响内部状态");
        }

        @Test
        @DisplayName("text() 使用 UTF-8 解码")
        void textUtf8() {
            Command cmd = Command.text("Hello");
            assertEquals("Hello", new String(cmd.bytes(), StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("text() 使用指定字符集")
        void textWithCharset() {
            Command cmd = Command.text("Hello");
            assertEquals("Hello", new String(cmd.bytes(), StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("空字符串创建命令")
        void emptyString() {
            Command cmd = Command.text("");
            assertEquals(0, cmd.bytes().length);
            assertEquals("", new String(cmd.bytes(), StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("null 参数抛出异常")
        void nullParametersThrow() {
            assertThrows(NullPointerException.class, () -> Command.of(null));
            assertThrows(NullPointerException.class, () -> Command.text(null));
        }
    }

    @Nested
    @DisplayName("Response 创建")
    class ResponseCreation {

        @Test
        @DisplayName("完整构造")
        void fullConstructor() {
            byte[] frame = "OK\r\n".getBytes(StandardCharsets.UTF_8);
            byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
            Response resp = new Response(frame, body, 123L);
            assertEquals(123L, resp.receivedAtNanos());
        }

        @Test
        @DisplayName("两参数构造使用当前时间戳")
        void twoArgConstructor() {
            byte[] frame = "OK\r\n".getBytes(StandardCharsets.UTF_8);
            byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
            long before = System.nanoTime();
            Response resp = new Response(frame, body);
            long after = System.nanoTime();
            assertTrue(resp.receivedAtNanos() >= before);
            assertTrue(resp.receivedAtNanos() <= after);
        }

        @Test
        @DisplayName("bytes() 返回克隆副本")
        void bytesReturnsClone() {
            byte[] frame = "OK\r\n".getBytes(StandardCharsets.UTF_8);
            byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
            Response resp = new Response(frame, body);
            byte[] result = resp.bytes();
            result[0] = 'X';
            assertArrayEquals(frame, resp.bytes(),
                "修改返回的数组不应影响内部状态");
        }

        @Test
        @DisplayName("body() 返回克隆副本")
        void bodyReturnsClone() {
            byte[] frame = "OK\r\n".getBytes(StandardCharsets.UTF_8);
            byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
            Response resp = new Response(frame, body);
            byte[] result = resp.body();
            result[0] = 'X';
            assertArrayEquals(body, resp.body(),
                "修改返回的数组不应影响内部状态");
        }

        @Test
        @DisplayName("text() 默认 UTF-8")
        void textDefaultUtf8() {
            byte[] frame = "你好\r\n".getBytes(StandardCharsets.UTF_8);
            byte[] body = "你好".getBytes(StandardCharsets.UTF_8);
            Response resp = new Response(frame, body);
            assertEquals("你好", resp.text());
            assertEquals("你好", resp.text(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("text() 使用指定字符集")
        void textWithCharset() {
            byte[] frame = "DATA\r\n".getBytes(StandardCharsets.US_ASCII);
            byte[] body = "DATA".getBytes(StandardCharsets.US_ASCII);
            Response resp = new Response(frame, body);
            assertEquals("DATA", resp.text(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("null 参数抛出异常")
        void nullParametersThrow() {
            byte[] data = "OK".getBytes(StandardCharsets.UTF_8);
            assertThrows(NullPointerException.class, () -> new Response(null, data));
            assertThrows(NullPointerException.class, () -> new Response(data, null));
        }
    }

    @Nested
    @DisplayName("CommandIdempotency")
    class Idempotency {

        @Test
        @DisplayName("三值枚举完整")
        void threeValuesExist() {
            assertEquals(3, CommandIdempotency.values().length);
            assertNotNull(CommandIdempotency.IDEMPOTENT);
            assertNotNull(CommandIdempotency.NON_IDEMPOTENT);
            assertNotNull(CommandIdempotency.UNKNOWN);
        }
    }
}