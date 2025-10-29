package com.g.netty_19.protocol.response;

import static com.g.netty_19.protocol.command.Command.LOGOUT_RESPONSE;

import lombok.Data;
import com.g.netty_19.protocol.Packet;

@Data
public class LogoutResponsePacket extends Packet {

    private boolean success;

    private String reason;


    @Override
    public Byte getCommand() {

        return LOGOUT_RESPONSE;
    }
}
