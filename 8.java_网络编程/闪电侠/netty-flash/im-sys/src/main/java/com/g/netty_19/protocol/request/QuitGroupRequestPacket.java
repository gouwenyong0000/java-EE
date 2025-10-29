package com.g.netty_19.protocol.request;

import static com.g.netty_19.protocol.command.Command.QUIT_GROUP_REQUEST;

import lombok.Data;
import com.g.netty_19.protocol.Packet;

@Data
public class QuitGroupRequestPacket extends Packet {

    private String groupId;

    @Override
    public Byte getCommand() {

        return QUIT_GROUP_REQUEST;
    }
}
