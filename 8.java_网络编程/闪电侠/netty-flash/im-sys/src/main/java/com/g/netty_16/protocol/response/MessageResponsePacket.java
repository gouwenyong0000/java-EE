package com.g.netty_16.protocol.response;

import static com.g.netty_16.protocol.command.Command.MESSAGE_RESPONSE;

import com.g.netty_16.protocol.Packet;
import lombok.Data;

@Data
public class MessageResponsePacket extends Packet {

    private String fromUserId;

    private String fromUserName;

    private String message;

    @Override
    public Byte getCommand() {

        return MESSAGE_RESPONSE;
    }
}
