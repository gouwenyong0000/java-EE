package com.g.netty_19.protocol.request;

import static com.g.netty_19.protocol.command.Command.LOGIN_REQUEST;

import lombok.Data;
import com.g.netty_19.protocol.Packet;

@Data
public class LoginRequestPacket extends Packet {
    private String userName;

    private String password;

    @Override
    public Byte getCommand() {

        return LOGIN_REQUEST;
    }
}
