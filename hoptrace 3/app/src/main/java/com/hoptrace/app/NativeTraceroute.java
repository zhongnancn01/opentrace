package com.hoptrace.app;

public final class NativeTraceroute {
    static {
        System.loadLibrary("hoptrace");
    }

    private NativeTraceroute() {}

    /** Executes an unprivileged ICMP ping-socket traceroute. */
    public static native String run(String host, boolean ipv6, int maxHops,
                                    int timeoutMs, int probesPerHop);
}
