package com.g.netty_10.protocol.response;

import lombok.Data;

import com.g.netty_10.protocol.Packet;

import static com.g.netty_10.protocol.command.Command.LOGIN_RESPONSE;

@Data
public class LoginResponsePacket extends Packet {
    private boolean success;

    private String reason;


    @Override
    public Byte getCommand() {
        return LOGIN_RESPONSE;
    }
}
