package com.g.netty_20.protocol.response;

import static com.g.netty_20.protocol.command.Command.HEARTBEAT_RESPONSE;

import com.g.netty_20.protocol.Packet;

public class HeartBeatResponsePacket extends Packet {
    @Override
    public Byte getCommand() {
        return HEARTBEAT_RESPONSE;
    }
}
