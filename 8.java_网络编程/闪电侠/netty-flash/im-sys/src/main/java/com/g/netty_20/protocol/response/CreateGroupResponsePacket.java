package com.g.netty_20.protocol.response;

import static com.g.netty_20.protocol.command.Command.CREATE_GROUP_RESPONSE;

import java.util.List;
import lombok.Data;
import com.g.netty_20.protocol.Packet;

@Data
public class CreateGroupResponsePacket extends Packet {
    private boolean success;

    private String groupId;

    private List<String> userNameList;

    @Override
    public Byte getCommand() {

        return CREATE_GROUP_RESPONSE;
    }
}
