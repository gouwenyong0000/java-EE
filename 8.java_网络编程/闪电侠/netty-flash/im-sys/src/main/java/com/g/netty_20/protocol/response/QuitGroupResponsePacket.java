package com.g.netty_20.protocol.response;

import static com.g.netty_20.protocol.command.Command.QUIT_GROUP_RESPONSE;

import lombok.Data;
import com.g.netty_20.protocol.Packet;

@Data
public class QuitGroupResponsePacket extends Packet {

    private String groupId;

    private boolean success;

    private String reason;

    @Override
    public Byte getCommand() {

        return QUIT_GROUP_RESPONSE;
    }
}
