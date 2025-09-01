package com.g.netty_20.protocol.request;

import static com.g.netty_20.protocol.command.Command.HEARTBEAT_REQUEST;

import com.g.netty_20.protocol.Packet;

public class HeartBeatRequestPacket extends Packet {
    @Override
    public Byte getCommand() {
        return HEARTBEAT_REQUEST;
    }
}
