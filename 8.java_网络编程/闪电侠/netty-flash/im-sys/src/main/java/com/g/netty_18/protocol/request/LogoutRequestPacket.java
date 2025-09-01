package com.g.netty_18.protocol.request;

import lombok.Data;
import com.g.netty_18.protocol.Packet;

import static com.g.netty_18.protocol.command.Command.LOGOUT_REQUEST;

@Data
public class LogoutRequestPacket extends Packet {
    @Override
    public Byte getCommand() {

        return LOGOUT_REQUEST;
    }
}
