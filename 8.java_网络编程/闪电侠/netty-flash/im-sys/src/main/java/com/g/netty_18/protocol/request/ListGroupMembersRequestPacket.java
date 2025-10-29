package com.g.netty_18.protocol.request;

import lombok.Data;
import com.g.netty_18.protocol.Packet;

import static com.g.netty_18.protocol.command.Command.LIST_GROUP_MEMBERS_REQUEST;

@Data
public class ListGroupMembersRequestPacket extends Packet {

    private String groupId;

    @Override
    public Byte getCommand() {

        return LIST_GROUP_MEMBERS_REQUEST;
    }
}
