package com.velopcontrol.app;

import java.util.Set;

/** An exact allowlist, never a prefix check or caller-supplied action URL. */
public final class ReadPolicy {
    public static final String INFO = "core/GetDeviceInfo";
    public static final String DEVICES = "devicelist/GetDevices3";
    public static final String BACKHAUL = "nodes/diagnostics/GetBackhaulInfo";
    public static final String CONNECTIONS = "networkconnections/GetNetworkConnections2";
    public static final String NODE_CONNECTIONS = "nodes/networkconnections/GetNodesWirelessNetworkConnections";
    public static final String MODE = "nodes/smartmode/GetDeviceMode";
    public static final Set<String> ACTIONS = java.util.Collections.unmodifiableSet(new java.util.HashSet<>(
        java.util.Arrays.asList(INFO, DEVICES, BACKHAUL, CONNECTIONS, NODE_CONNECTIONS, MODE)));

    public static void requireAllowed(String action) {
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Router action blocked");
    }

    public static String privateAddress(String value) {
        String host = value.trim();
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Enter the primary Velop's private IPv4 address");
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException("Invalid IPv4 address");
            n[i] = Integer.parseInt(parts[i]);
            if (n[i] > 255) throw new IllegalArgumentException("Invalid IPv4 address");
        }
        if (!(n[0] == 10 || n[0] == 172 && n[1] >= 16 && n[1] <= 31 || n[0] == 192 && n[1] == 168))
            throw new IllegalArgumentException("Only private LAN IPv4 addresses are supported");
        return host;
    }
    private ReadPolicy() {}
}
