package com.g.netty_19.protocol.response;

import static com.g.netty_19.protocol.command.Command.LOGIN_RESPONSE;

import lombok.Data;
import com.g.netty_19.protocol.Packet;

@Data
public class LoginResponsePacket extends Packet {
    private String userId;

    private String userName;

    private boolean success;

    private String reason;


    @Override
    public Byte getCommand() {

        return LOGIN_RESPONSE;
    }
}
