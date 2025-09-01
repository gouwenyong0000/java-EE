package com.g.netty_20.protocol.request;

import static com.g.netty_20.protocol.command.Command.GROUP_MESSAGE_REQUEST;

import lombok.Data;
import lombok.NoArgsConstructor;
import com.g.netty_20.protocol.Packet;

@Data
@NoArgsConstructor
public class GroupMessageRequestPacket extends Packet {
    private String toGroupId;
    private String message;

    public GroupMessageRequestPacket(String toGroupId, String message) {
        this.toGroupId = toGroupId;
        this.message = message;
    }

    @Override
    public Byte getCommand() {
        return GROUP_MESSAGE_REQUEST;
    }
}
