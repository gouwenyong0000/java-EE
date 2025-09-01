package com.g.netty_12.protocol.response;


import com.g.netty_12.protocol.Packet;
import lombok.Data;

import static com.g.netty_12.protocol.command.Command.LOGIN_RESPONSE;

@Data
public class LoginResponsePacket extends Packet {
    private boolean success;

    private String reason;


    @Override
    public Byte getCommand() {
        return LOGIN_RESPONSE;
    }
}
