package com.g.netty.tcp_protocol;

/**
 * 协议包
 */
public class MessageProtocol {
    private int len; //当前包的长度
    private byte[] content;

    public int getLen() {
        return len;
    }

    public void setLen(int len) {
        this.len = len;
    }

    public byte[] getContent() {
        return content;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }
}
