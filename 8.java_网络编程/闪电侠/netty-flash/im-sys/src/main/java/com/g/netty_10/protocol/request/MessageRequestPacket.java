package com.g.netty_10.protocol.request;



import com.g.netty_10.protocol.Packet;
import lombok.Data;

import static com.g.netty_10.protocol.command.Command.MESSAGE_REQUEST;


@Data
public class MessageRequestPacket extends Packet {

    private String message;

    @Override
    public Byte getCommand() {
        return MESSAGE_REQUEST;
    }
}
