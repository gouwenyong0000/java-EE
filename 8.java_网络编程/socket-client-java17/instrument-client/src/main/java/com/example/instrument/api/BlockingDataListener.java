package com.example.instrument.api;

import com.example.instrument.model.Response;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * 阻塞式数据监听接口。
 *
 * <p>适用于需要以同步方式等待下一条响应或事件的场景，典型使用方式是循环调用 take() 或 poll()。
 */
public interface BlockingDataListener {
  /** 阻塞直到有新的响应数据可取。 常用于后台接收线程将响应放入队列后，调用方按顺序消费数据。 */
  Response take() throws InterruptedException;

  /** 在指定超时时间内等待数据；如果超时则抛出 TimeoutException。 适合用来避免长期阻塞导致调用线程卡住。 */
  Response poll(Duration timeout) throws InterruptedException, TimeoutException;
}
