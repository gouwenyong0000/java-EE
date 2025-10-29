package com.g.netty_19.protocol.response;

import static com.g.netty_19.protocol.command.Command.JOIN_GROUP_RESPONSE;

import lombok.Data;
import com.g.netty_19.protocol.Packet;

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
