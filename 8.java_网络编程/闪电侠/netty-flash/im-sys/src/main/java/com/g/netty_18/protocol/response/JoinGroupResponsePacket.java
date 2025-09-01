package com.g.netty_18.protocol.response;

import lombok.Data;
import com.g.netty_18.protocol.Packet;

import static com.g.netty_18.protocol.command.Command.JOIN_GROUP_RESPONSE;

@Data
public class JoinGroupResponsePacket extends Packet {
    private String groupId;

    private boolean success;

    private String reason;

    @Override
    public Byte getCommand() {

        return JOIN_GROUP_RESPONSE;
    }
}
