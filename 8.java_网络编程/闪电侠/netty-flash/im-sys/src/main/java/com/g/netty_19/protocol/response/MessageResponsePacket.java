package com.g.netty_19.protocol.response;

import static com.g.netty_19.protocol.command.Command.MESSAGE_RESPONSE;

import lombok.Data;
import com.g.netty_19.protocol.Packet;

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
