package com.example.cli.api;

import com.example.cli.model.Response;

/**
 * 响应匹配器。
 *
 * <p>用于判断某条响应是否属于当前待处理请求的返回值，典型用法包括正则校验、状态码检查或字段内容比较。
 */
@FunctionalInterface
public interface ResponseMatcher {
  /**
   * 判断给定响应是否满足当前请求所需的匹配条件。
   */
  boolean matches(Response response);
}
