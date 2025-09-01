package com.g.netty_12.protocol.request;


import com.g.netty_12.protocol.Packet;
import lombok.Data;
import lombok.NoArgsConstructor;

import static com.g.netty_12.protocol.command.Command.MESSAGE_REQUEST;


@Data
@NoArgsConstructor
public class MessageRequestPacket extends Packet {

    private String message;

    public MessageRequestPacket(String message) {
        this.message = message;
    }

    @Override
    public Byte getCommand() {
        return MESSAGE_REQUEST;
    }
}
