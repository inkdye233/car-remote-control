package com.embedded.dualcarcontroller;

import java.util.Locale;
import java.util.UUID;

public final class CarProtocol {
    public static final UUID SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    public static final UUID PREFERRED_WRITE_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    public static final UUID FALLBACK_WRITE_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");

    public static final byte[] TURN_RIGHT = bytes(0x55, 0x0a, 0x01, 0xfa, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0xff, 0xf5);
    public static final byte[] TURN_LEFT = bytes(0x55, 0x0a, 0xfd, 0xfb, 0xff, 0xff, 0x00, 0x00, 0x00, 0x00, 0x99, 0xff);
    public static final byte[] ACCELERATE = bytes(0x55, 0x0a, 0x00, 0x52, 0x01, 0xfd, 0x00, 0x00, 0x00, 0x00, 0x02, 0x36);
    public static final byte[] REVERSE = bytes(0x55, 0x0a, 0xff, 0xff, 0xff, 0xff, 0x02, 0x00, 0x00, 0x00, 0x5c, 0x5e);
    public static final byte[] HOLD_AND_STOP = bytes(0x55, 0x0a, 0xff, 0xff, 0xff, 0xff, 0x00, 0x00, 0x00, 0x00, 0x5d, 0xe6);
    public static final byte[] PUSH = bytes(0x55, 0x0a, 0xff, 0xff, 0xff, 0xfe, 0x10, 0x00, 0x00, 0x00, 0x64, 0xe6);

    private CarProtocol() {}

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    public static String toHex(byte[] packet) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < packet.length; index++) {
            if (index > 0) builder.append(' ');
            builder.append(String.format(Locale.US, "%02X", packet[index] & 0xff));
        }
        return builder.toString();
    }
}
