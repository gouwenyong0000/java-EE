package com.g.netty_20.protocol.request;

import static com.g.netty_20.protocol.command.Command.LIST_GROUP_MEMBERS_REQUEST;

import lombok.Data;
import com.g.netty_20.protocol.Packet;

@Data
public class ListGroupMembersRequestPacket extends Packet {

    private String groupId;

    @Override
    public Byte getCommand() {

        return LIST_GROUP_MEMBERS_REQUEST;
    }
}
