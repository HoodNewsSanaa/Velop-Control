package com.velopcontrol.app;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds the remembered primary Velop on the local network, even after its address changes.
 *
 * A candidate matches only when it presents the exact certificate the user approved for the primary
 * AND an unauthenticated identity read over a connection pinned to that certificate reports the
 * remembered serial number. Probes are certificate-only TLS handshakes; no credentials or HTTP
 * requests are sent to hosts that do not present the approved certificate.
 */
public final class PrimaryLocator {
    /** Larger subnets are narrowed to the phone's own /24 to keep the search bounded. */
    public static final int MAX_SCAN_HOSTS = 1022;
    static final int PRIORITY_TIMEOUT_MS = 1500, SCAN_TIMEOUT_MS = 700;

    public interface Probe {
        /** Certificate-only TLS inspection; returns the formatted SHA-256 certificate fingerprint. */
        String fingerprint(String host, int timeoutMs) throws Exception;
        /** Serial number from an unauthenticated identity read pinned to {@code fingerprint}. */
        String serial(String host, String fingerprint) throws Exception;
    }

    /** What the app remembers about the last successfully authenticated primary. */
    public static final class Remembered {
        public final String host, fingerprint, serial;
        public Remembered(String host, String fingerprint, String serial) {
            this.host = host == null ? "" : host; this.fingerprint = fingerprint == null ? "" : fingerprint; this.serial = serial == null ? "" : serial;
        }
        public boolean valid() { return !fingerprint.isEmpty() && !serial.isEmpty(); }
    }

    /** The phone's current Wi-Fi/Ethernet network, as reported by Android. Empty values are unknown. */
    public static final class LocalNetwork {
        public static final LocalNetwork NONE = new LocalNetwork("", "", 0);
        public final String gateway, address; public final int prefix;
        public LocalNetwork(String gateway, String address, int prefix) {
            this.gateway = privateOrEmpty(gateway); this.address = privateOrEmpty(address); this.prefix = prefix;
        }
    }

    /** Hosts to check first (last known address, then gateway), followed by the subnet sweep. */
    public static final class Plan {
        public final List<String> priority, scan;
        Plan(List<String> priority, List<String> scan) { this.priority = Collections.unmodifiableList(priority); this.scan = Collections.unmodifiableList(scan); }
        public int size() { return priority.size() + scan.size(); }
    }

    public static Plan plan(String lastHost, LocalNetwork network) {
        LinkedHashSet<String> priority = new LinkedHashSet<>();
        String last = privateOrEmpty(lastHost);
        if (!last.isEmpty()) priority.add(last);
        if (!network.gateway.isEmpty()) priority.add(network.gateway);
        List<String> scan = new ArrayList<>();
        if (!network.address.isEmpty() && network.prefix >= 1 && network.prefix <= 30) {
            int prefix = network.prefix;
            if ((1L << (32 - prefix)) - 2 > MAX_SCAN_HOSTS) prefix = 24;
            long mask = (0xffffffffL << (32 - prefix)) & 0xffffffffL;
            long self = SettingsValidation.ipv4(network.address), base = self & mask, broadcast = base | (~mask & 0xffffffffL);
            for (long ip = base + 1; ip < broadcast; ip++) {
                if (ip == self) continue;
                String host = privateOrEmpty(format(ip));
                if (!host.isEmpty() && !priority.contains(host)) scan.add(host);
            }
        }
        return new Plan(new ArrayList<>(priority), scan);
    }

    /** Returns the matching host, or null when the remembered primary was not found. */
    public static String locate(Probe probe, Remembered remembered, Plan plan, int threads, long budgetMs, AtomicBoolean cancelled) throws InterruptedException {
        if (!remembered.valid()) return null;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        for (String host : plan.priority) {
            if (cancelled.get() || System.nanoTime() > deadline) return null;
            if (matches(probe, remembered, host, PRIORITY_TIMEOUT_MS)) return host;
        }
        if (plan.scan.isEmpty() || cancelled.get()) return null;
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(threads, plan.scan.size())));
        try {
            CompletionService<String> results = new ExecutorCompletionService<>(pool);
            for (String host : plan.scan) results.submit(() -> !cancelled.get() && matches(probe, remembered, host, SCAN_TIMEOUT_MS) ? host : null);
            for (int done = 0; done < plan.scan.size(); done++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || cancelled.get()) return null;
                Future<String> next = results.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(250)), TimeUnit.NANOSECONDS);
                if (next == null) { done--; continue; }
                try { String host = next.get(); if (host != null) return host; } catch (ExecutionException ignored) {}
            }
            return null;
        } finally { pool.shutdownNow(); }
    }

    static boolean matches(Probe probe, Remembered remembered, String host, int timeoutMs) {
        try {
            if (!remembered.fingerprint.equals(probe.fingerprint(host, timeoutMs))) return false;
            // A shared firmware certificate alone could match other Linksys nodes; the serial decides.
            return remembered.serial.equals(probe.serial(host, remembered.fingerprint));
        } catch (Exception e) { return false; }
    }

    private static String privateOrEmpty(String host) {
        if (host == null || host.isEmpty()) return "";
        try { return ReadPolicy.privateAddress(host); } catch (IllegalArgumentException e) { return ""; }
    }
    private static String format(long ip) { return ((ip >> 24) & 255) + "." + ((ip >> 16) & 255) + "." + ((ip >> 8) & 255) + "." + (ip & 255); }
    private PrimaryLocator() {}
}
