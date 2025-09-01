package com.g.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Set;

public class NIOServer {

  public static void main(String[] args) throws IOException {

    Selector serverSelector = Selector.open();
    Selector clientSelector = Selector.open();

    new Thread(
            () -> {
              try {

                // 对应IO编程中的服务端启动
                ServerSocketChannel listenerChannel = ServerSocketChannel.open();
                listenerChannel.socket().bind(new InetSocketAddress(8000));
                listenerChannel.configureBlocking(false);
                listenerChannel.register(serverSelector, SelectionKey.OP_ACCEPT);

                while (true) {

                  // 监测是否有新连接，这里的1指阻塞的时间为 1ms
                  if (serverSelector.select(1) > 0) {

                    Set<SelectionKey> set = serverSelector.selectedKeys();
                    Iterator<SelectionKey> keyIterator = set.iterator();
                    while (keyIterator.hasNext()) {
                      SelectionKey key = keyIterator.next();
                      if (key.isAcceptable()) {
                        try {
                          // (1)每来一个新连接，不需要创建一个线程，而是直接注册到clientSelector
                          SocketChannel clientChannel =
                              ((ServerSocketChannel) key.channel()).accept();
                          clientChannel.configureBlocking(false);
                          clientChannel.register(clientSelector, SelectionKey.OP_READ);

                        } finally {
                          keyIterator.remove();
                        }
                      }
                    }
                  }
                }

              } catch (IOException ignored) {

              }
            })
        .start();

    new Thread(
            () -> {
              try {
                while (true) {
                  // (2)批量轮询哪些连接有数据可读，这里的1指阻塞的时间为 1ms
                  if (clientSelector.select(1) > 0) {

                    Set<SelectionKey> set = clientSelector.selectedKeys();
                    Iterator<SelectionKey> keyIterator = set.iterator();
                    while (keyIterator.hasNext()) {
                      SelectionKey key = keyIterator.next();
                      if (key.isReadable()) {
                        try {
                          SocketChannel clientChannel = (SocketChannel) key.channel();
                          ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
                          // (3)面向Buffer
                          clientChannel.read(byteBuffer);
                          byteBuffer.flip();
                          System.out.println(
                              Charset.defaultCharset().newDecoder().decode(byteBuffer).toString());
                        } finally {
                          keyIterator.remove();
                          /*
                            在 NIO 的多路复用模型中，SelectionKey 负责跟踪通道的注册事件。
                            当一个通道的读事件被触发并处理完成后，为了继续监听后续的读事件，需要再次明确告诉 Selector：“我仍然关心这个通道的读操作”。
                            调用 key.interestOps(SelectionKey.OP_READ) 就是在完成这一注册。
                            为什么需要这么做？
                            在某些情况下（尤其是使用 epoll 模型的系统），如果不清除或重新设置感兴趣的事件，可能会导致事件不会再次被触发。
                            即使不是必须的平台，显式重置感兴趣的事件是一种良好的编程习惯，确保程序具有更好的可移植性和健壮性。
                          */
                          key.interestOps(SelectionKey.OP_READ);
                        }
                      }
                    }
                  }
                }

              } catch (IOException ignored) {

              }
            })
        .start();
  }
}
