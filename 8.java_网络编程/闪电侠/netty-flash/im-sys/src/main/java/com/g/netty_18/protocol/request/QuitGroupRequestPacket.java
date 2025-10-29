package com.g.netty_18.protocol.request;

import lombok.Data;
import com.g.netty_18.protocol.Packet;

import static com.g.netty_18.protocol.command.Command.QUIT_GROUP_REQUEST;

@Data
public class QuitGroupRequestPacket extends Packet {

    private String groupId;

    @Override
    public Byte getCommand() {

        return QUIT_GROUP_REQUEST;
    }
}
