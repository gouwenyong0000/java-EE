package com.g.netty_13.protocol.request;

import static com.g.netty_13.protocol.command.Command.MESSAGE_REQUEST;

import com.g.netty_13.protocol.Packet;
import lombok.Data;
import lombok.NoArgsConstructor;

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
