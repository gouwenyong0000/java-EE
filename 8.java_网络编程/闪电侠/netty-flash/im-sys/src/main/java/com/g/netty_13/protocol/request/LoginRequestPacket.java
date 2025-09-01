package com.g.netty_13.protocol.request;

import static com.g.netty_13.protocol.command.Command.LOGIN_REQUEST;

import com.g.netty_13.protocol.Packet;
import lombok.Data;

@Data
public class LoginRequestPacket extends Packet {
    private String userId;

    private String username;

    private String password;

    @Override
    public Byte getCommand() {

        return LOGIN_REQUEST;
    }
}
