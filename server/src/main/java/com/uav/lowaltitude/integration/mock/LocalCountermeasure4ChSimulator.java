package com.uav.lowaltitude.integration.mock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.integration.device.countermeasure.Countermeasure4ChCodec;

/**
 * 仅 local：在 127.0.0.1 应答四通道 0x10–0x13。不是现场射频，也不监听 192.168.0.7。
 */
@Component
@Profile("!production & local")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
public class LocalCountermeasure4ChSimulator implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LocalCountermeasure4ChSimulator.class);

    public static final String HOST = "127.0.0.1";

    private final int preferredPort;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger relays = new AtomicInteger(0);
    private volatile ServerSocket server;
    private volatile Thread thread;
    private volatile int port;

    public LocalCountermeasure4ChSimulator(
            @Value("${app.countermeasure-4ch.sim-port:10006}") int preferredPort) {
        this.preferredPort = preferredPort;
    }

    public int port() { return port; }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        try {
            server = bind();
            port = server.getLocalPort();
            thread = new Thread(this::acceptLoop, "cm4-local-sim");
            thread.setDaemon(true);
            thread.start();
            log.info("local 4ch simulator listening on {}:{} (simulated; not field RF)", HOST, port);
        } catch (IOException ex) {
            running.set(false);
            throw new IllegalStateException("cannot bind local 4ch simulator", ex);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        try {
            if (server != null) server.close();
        } catch (IOException ignored) { }
        if (thread != null) thread.interrupt();
    }

    @Override
    public boolean isRunning() { return running.get(); }

    private ServerSocket bind() throws IOException {
        try {
            return new ServerSocket(preferredPort, 16, InetAddress.getByName(HOST));
        } catch (IOException ex) {
            log.warn("local 4ch simulator port {} in use, using an ephemeral port", preferredPort);
            return new ServerSocket(0, 16, InetAddress.getByName(HOST));
        }
    }

    private void acceptLoop() {
        while (running.get() && server != null && !server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread handler = new Thread(() -> handle(socket), "cm4-local-session");
                handler.setDaemon(true);
                handler.start();
            } catch (SocketException ex) {
                if (running.get()) log.warn("local 4ch simulator accept failed: {}", ex.getMessage());
            } catch (IOException ex) {
                if (running.get()) log.warn("local 4ch simulator accept failed: {}", ex.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(3000);
            byte[] raw = readRequest(socket);
            if (raw.length == 0) return;
            boolean ascii = raw[0] != (byte) Countermeasure4ChCodec.REQUEST_HEADER;
            byte[] logical = ascii
                    ? Countermeasure4ChCodec.decodeWire(raw, Countermeasure4ChCodec.WireEncoding.ASCII_HEX_SPACED)
                    : raw;
            if (logical.length != 8) return;
            int function = Byte.toUnsignedInt(logical[2]);
            int data = Byte.toUnsignedInt(logical[6]);
            int address = Byte.toUnsignedInt(logical[1]);
            if (function == Countermeasure4ChCodec.FUNCTION_ON) relays.updateAndGet(v -> v | (data & 0x0F));
            else if (function == Countermeasure4ChCodec.FUNCTION_OFF) relays.updateAndGet(v -> v & ~(data & 0x0F));
            else if (function == Countermeasure4ChCodec.FUNCTION_SET) relays.set(data & 0x0F);
            byte[] reply = new byte[] { (byte) Countermeasure4ChCodec.RESPONSE_HEADER, (byte) address,
                    (byte) function, 0, 0, 0, (byte) relays.get(), 0 };
            reply[7] = Countermeasure4ChCodec.checksum(reply, 0, 7);
            byte[] wire = ascii
                    ? Countermeasure4ChCodec.encodeWire(reply, Countermeasure4ChCodec.WireEncoding.ASCII_HEX_SPACED)
                    : reply;
            socket.getOutputStream().write(wire);
            socket.getOutputStream().flush();
        } catch (Exception ex) {
            log.debug("local 4ch simulator session ended: {}", ex.getMessage());
        }
    }

    private static byte[] readRequest(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        int first = in.read();
        if (first < 0) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(first);
        if (first == Countermeasure4ChCodec.REQUEST_HEADER) {
            while (out.size() < 8) {
                int value = in.read();
                if (value < 0) break;
                out.write(value);
            }
            return out.toByteArray();
        }
        while (true) {
            int value = in.read();
            if (value < 0 || value == '\n') break;
            if (value != '\r') out.write(value);
        }
        String hex = out.toString(StandardCharsets.US_ASCII).trim().replace(" ", "");
        if (hex.isEmpty()) return new byte[0];
        return (HexFormat.ofDelimiter(" ").withUpperCase().formatHex(HexFormat.of().parseHex(hex)) + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);
    }
}
