package com.g.netty_20.protocol.response;

import static com.g.netty_20.protocol.command.Command.GROUP_MESSAGE_RESPONSE;

import lombok.Data;
import com.g.netty_20.protocol.Packet;
import com.g.netty_20.session.Session;

@Data
public class GroupMessageResponsePacket extends Packet {

    private String fromGroupId;

    private Session fromUser;

    private String message;

    @Override
    public Byte getCommand() {

        return GROUP_MESSAGE_RESPONSE;
    }
}
