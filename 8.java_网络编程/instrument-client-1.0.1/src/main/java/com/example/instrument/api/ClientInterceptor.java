package com.example.instrument.api;

import com.example.instrument.model.Command;
import com.example.instrument.model.CommandIdempotency;
import com.example.instrument.model.Response;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 客户端拦截器接口，允许在请求发送前后和响应接收前后执行自定义逻辑。
 *
 * <p>拦截器链可用于：</p>
 * <ul>
 *   <li>请求/响应日志记录</li>
 *   <li>性能监控和指标收集</li>
 *   <li>命令转换或增强</li>
 *   <li>请求审计</li>
 *   <li>故障注入（测试场景）</li>
 * </ul>
 *
 * <p>执行顺序：</p>
 * <pre>
 * beforeRequest() → [发送命令] → [等待响应] → afterResponse() / afterFailure()
 * </pre>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * ClientInterceptor loggingInterceptor = new ClientInterceptor() {
 *     @Override
 *     public void beforeRequest(Command command, CommandIdempotency idempotency) {
 *         System.out.println("Sending: " + command.text());
 *     }
 *
 *     @Override
 *     public void afterResponse(Command command, Response response, Duration latency) {
 *         System.out.println("Received: " + response.text() + " in " + latency.toMillis() + "ms");
 *     }
 * };
 * }</pre>
 *
 * @see InstrumentClient
 * @see Command
 * @see Response
 */
public interface ClientInterceptor {

    /**
     * 在请求发送前调用。
     *
     * @param command     要发送的命令
     * @param idempotency 命令的幂等性
     */
    default void beforeRequest(Command command, CommandIdempotency idempotency) {}

    /**
     * 在成功收到响应后调用。
     *
     * @param command  发送的命令
     * @param response 收到的响应
     * @param latency  从发送到接收的延迟时间
     */
    default void afterResponse(Command command, Response response, Duration latency) {}

    /**
     * 在请求失败后调用。
     *
     * @param command   发送的命令
     * @param throwable 失败原因
     */
    default void afterFailure(Command command, Throwable throwable) {}

    /**
     * 在异步请求发送前调用。
     *
     * @param command     要发送的命令
     * @param idempotency 命令的幂等性
     * @return 可以修改后的 CompletableFuture，默认返回 null 表示不干预
     */
    default CompletableFuture<Response> beforeAsyncRequest(Command command, CommandIdempotency idempotency) {
        return null;
    }
}