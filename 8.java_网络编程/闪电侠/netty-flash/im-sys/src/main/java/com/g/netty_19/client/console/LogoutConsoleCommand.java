package com.g.netty_19.client.console;

import io.netty.channel.Channel;
import java.util.Scanner;
import com.g.netty_19.protocol.request.LogoutRequestPacket;

public class LogoutConsoleCommand implements ConsoleCommand {
    @Override
    public void exec(Scanner scanner, Channel channel) {
        LogoutRequestPacket logoutRequestPacket = new LogoutRequestPacket();
        channel.writeAndFlush(logoutRequestPacket);
    }
}
