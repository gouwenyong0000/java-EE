package com.g.netty_18.protocol.response;

import lombok.Data;
import com.g.netty_18.protocol.Packet;

import static com.g.netty_18.protocol.command.Command.LOGOUT_RESPONSE;

@Data
public class LogoutResponsePacket extends Packet {

    private boolean success;

    private String reason;


    @Override
    public Byte getCommand() {

        return LOGOUT_RESPONSE;
    }
}
