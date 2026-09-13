package com.example.instrument;

import com.example.instrument.api.InstrumentClient;
import com.example.instrument.api.ResponseMatcher;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.model.Command;
import com.example.instrument.model.CommandIdempotency;
import com.example.instrument.protocol.LineProtocol;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Minimal usage example. */
public final class Demo {
    private Demo() {}
    public static void main(String[] args) {
        ClientConfig config=ClientConfig.defaults();
        InstrumentClient client=InstrumentClients.tcp(
                new InetSocketAddress("127.0.0.1",5025),
                LineProtocol.crlf(StandardCharsets.UTF_8),config);
        try {
            client.connect();
            var response=client.request(
                    Command.text("*IDN?",StandardCharsets.UTF_8),
                    ResponseMatcher.any(),Duration.ofSeconds(3),CommandIdempotency.IDEMPOTENT);
            System.out.println(response.text(StandardCharsets.UTF_8));
        } finally { client.close(); }
    }
}
