package com.g.netty_12.protocol.request;


import com.g.netty_12.protocol.Packet;
import lombok.Data;

import static com.g.netty_12.protocol.command.Command.LOGIN_REQUEST;


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
