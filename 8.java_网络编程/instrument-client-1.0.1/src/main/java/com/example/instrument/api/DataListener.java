package com.example.instrument.api;

import com.example.instrument.model.Response;

/**
 * 数据监听器接口，用于接收无法匹配任何 pending 请求的响应数据。
 * 
 * 当客户端收到响应但没有对应的请求在等待时（即响应无法匹配任何已注册的 ResponseMatcher），
 * 这些响应会被传递给数据监听器。这种情况通常发生在：
 * 
 * <ul>
 *   <li>服务器主动推送的数据</li>
 *   <li>之前超时的请求现在收到了响应</li>
 *   <li>多服务器场景下的广播响应</li>
 * </ul>
 * 
 * <p>使用示例：</p>
 * <pre>{@code
 * client.addDataListener(response -> {
 *     System.out.println("Received async data: " + response.text());
 * });
 * }</pre>
 * 
 * @see Response
 * @see com.example.instrument.api.InstrumentClient#addDataListener
 * @see BlockingDataListener
 */
@FunctionalInterface
public interface DataListener {
    
    /**
     * 当收到无法匹配请求的响应数据时调用。
     *
     * @param response 收到的响应数据，不能为空
     */
    void onData(Response response);
}