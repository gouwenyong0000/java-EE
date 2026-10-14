package com.example.instrument.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.instrument.core.config.ClientConfig;
import com.example.instrument.core.metrics.ClientMetrics;
import com.example.instrument.protocol.FixedDelimiterProtocol;
import com.example.instrument.transport.tcp.Connection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class ReceiverTest {

    @Test
    @DisplayName("线程启动前调用 stop —— 接收器不会重新进入读取循环")
    void stopBeforeRunPreventsReading() {
        AtomicInteger readCount = new AtomicInteger();
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        Connection connection = new Connection() {
            @Override
            public void connect() {
            }

            @Override
            public int read(byte[] buffer) throws IOException {
                readCount.incrementAndGet();
                throw new IOException("unexpected read");
            }

            @Override
            public void write(byte[] data) {
            }

            @Override
            public boolean isConnected() {
                return true;
            }

            @Override
            public ConnectionState state() {
                return ConnectionState.CONNECTED;
            }

            @Override
            public void close() {
            }
        };
        ResponseDispatcher dispatcher = new ResponseDispatcher(
            new RequestManager(), ClientConfig.defaults(), new ClientMetrics());
        Receiver receiver = new Receiver(connection,
            FixedDelimiterProtocol.crlf(StandardCharsets.UTF_8).newDecoder(),
            dispatcher, 16, reportedError::set);

        receiver.stop();
        receiver.run();

        assertEquals(0, readCount.get());
        assertNull(reportedError.get());
    }
}
