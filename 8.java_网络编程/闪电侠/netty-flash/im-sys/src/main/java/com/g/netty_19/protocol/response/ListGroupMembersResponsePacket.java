package com.g.netty_19.protocol.response;

import static com.g.netty_19.protocol.command.Command.LIST_GROUP_MEMBERS_RESPONSE;

import java.util.List;
import lombok.Data;
import com.g.netty_19.protocol.Packet;
import com.g.netty_19.session.Session;

@Data
public class ListGroupMembersResponsePacket extends Packet {

    private String groupId;

    private List<Session> sessionList;

    @Override
    public Byte getCommand() {

        return LIST_GROUP_MEMBERS_RESPONSE;
    }
}
