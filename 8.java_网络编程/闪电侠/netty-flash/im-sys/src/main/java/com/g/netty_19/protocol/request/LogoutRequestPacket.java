package com.g.netty_19.protocol.request;

import static com.g.netty_19.protocol.command.Command.LOGOUT_REQUEST;

import lombok.Data;
import com.g.netty_19.protocol.Packet;

@Data
public class LogoutRequestPacket extends Packet {
    @Override
    public Byte getCommand() {

        return LOGOUT_REQUEST;
    }
}
