package com.g.netty_13.protocol.response;

import static com.g.netty_13.protocol.command.Command.LOGIN_RESPONSE;

import com.g.netty_13.protocol.Packet;
import lombok.Data;

@Data
public class LoginResponsePacket extends Packet {
    private boolean success;

    private String reason;


    @Override
    public Byte getCommand() {
        return LOGIN_RESPONSE;
    }
}
