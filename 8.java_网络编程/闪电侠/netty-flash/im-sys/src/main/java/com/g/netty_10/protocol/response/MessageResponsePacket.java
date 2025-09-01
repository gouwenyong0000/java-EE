package com.g.netty_10.protocol.response;


import com.g.netty_10.protocol.Packet;
import lombok.Data;

import static com.g.netty_10.protocol.command.Command.MESSAGE_RESPONSE;

@Data
public class MessageResponsePacket extends Packet {

    private String message;

    @Override
    public Byte getCommand() {

        return MESSAGE_RESPONSE;
    }
}
