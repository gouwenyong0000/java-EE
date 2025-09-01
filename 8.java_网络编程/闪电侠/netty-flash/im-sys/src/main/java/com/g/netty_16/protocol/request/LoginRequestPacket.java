package com.g.netty_16.protocol.request;

import static com.g.netty_16.protocol.command.Command.LOGIN_REQUEST;

import com.g.netty_16.protocol.Packet;
import lombok.Data;

@Data
public class LoginRequestPacket extends Packet {
    private String userName;

    private String password;

    @Override
    public Byte getCommand() {

        return LOGIN_REQUEST;
    }
}
