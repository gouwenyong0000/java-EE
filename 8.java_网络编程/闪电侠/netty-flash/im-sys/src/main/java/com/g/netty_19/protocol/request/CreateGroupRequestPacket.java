package com.g.netty_19.protocol.request;

import static com.g.netty_19.protocol.command.Command.CREATE_GROUP_REQUEST;

import java.util.List;
import lombok.Data;
import com.g.netty_19.protocol.Packet;

@Data
public class CreateGroupRequestPacket extends Packet {

    private List<String> userIdList;

    @Override
    public Byte getCommand() {

        return CREATE_GROUP_REQUEST;
    }
}
