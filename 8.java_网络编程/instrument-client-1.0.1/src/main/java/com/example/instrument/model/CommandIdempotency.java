package com.example.instrument.model;

/**
 * 命令幂等性枚举，定义命令在网络异常时的重试行为。
 * 
 * 幂等性决定了当请求过程中连接丢失时，客户端是否应该自动重试。
 * 正确的幂等性设置可以避免重复操作或遗漏操作。
 * 
 * <p>使用场景：</p>
 * <ul>
 *   <li>IDEMPOTENT: 查询类命令，如读取数据、查询状态，不会产生副作用</li>
 *   <li>NON_IDEMPOTENT: 修改类命令，如设置参数、控制操作，可能产生副作用</li>
 *   <li>UNKNOWN: 不确定的命令，默认不会自动重试</li>
 * </ul>
 * 
 * <p>使用示例：</p>
 * <pre>{@code
 * // 查询命令，可以安全重试
 * Response r1 = client.request(
 *     Command.text("GET_STATUS"),
 *     ResponseMatcher.any(),
 *     Duration.ofSeconds(5),
 *     CommandIdempotency.IDEMPOTENT
 * );
 * 
 * // 控制命令，不应该重试
 * client.request(
 *     Command.text("START"),
 *     ResponseMatcher.contains("OK"),
 *     Duration.ofSeconds(3),
 *     CommandIdempotency.NON_IDEMPOTENT
 * );
 * }</pre>
 * 
 * @see com.example.instrument.api.InstrumentClient#request
 */
public enum CommandIdempotency {
    
    /**
     * 幂等命令，可以安全重试。
     * 
     * 这类命令执行一次和执行多次的效果相同，如：
     * - 查询命令（GET、READ）
     * - 状态读取
     * - 配置查询
     * 
     * 当连接丢失时，客户端会自动重试此类命令。
     */
    IDEMPOTENT,
    
    /**
     * 非幂等命令，不应自动重试。
     * 
     * 这类命令执行多次会产生不同的效果或副作用，如：
     * - 控制命令（START、STOP、RESET）
     * - 状态设置
     * - 数据写入
     * 
     * 当连接丢失时，客户端不会自动重试此类命令。
     */
    NON_IDEMPOTENT,
    
    /**
     * 未知幂等性，默认行为。
     * 
     * 当不确定命令是否为幂等时使用此值。
     * 客户端不会自动重试此类命令。
     */
    UNKNOWN
}