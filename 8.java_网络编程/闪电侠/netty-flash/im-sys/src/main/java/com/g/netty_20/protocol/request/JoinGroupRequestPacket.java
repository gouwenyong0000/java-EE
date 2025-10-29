package com.g.netty_20.protocol.request;

import static com.g.netty_20.protocol.command.Command.JOIN_GROUP_REQUEST;

import lombok.Data;
import com.g.netty_20.protocol.Packet;

@Data
public class JoinGroupRequestPacket extends Packet {

    private String groupId;

    @Override
    public Byte getCommand() {

        return JOIN_GROUP_REQUEST;
    }
}
