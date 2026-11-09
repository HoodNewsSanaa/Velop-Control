package com.velopcontrol.app;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds the remembered primary Velop on the local network, even after its address changes.
 *
 * Probes are certificate-only TLS handshakes. Only hosts presenting either the approved certificate or
 * a Linksys firmware certificate receive one anonymous identity read (pinned to the certificate they
 * presented), and a host matches only if it reports the remembered serial number. No credentials are
 * sent during discovery.
 *
 * Linksys firmware ships one certificate per firmware build: every node on the same build presents the
 * same certificate, and a firmware update replaces it. The serial number distinguishes nodes; a
 * changed certificate is reported so the user approves it through the normal connect flow.
 */
public final class PrimaryLocator {
    /** Larger subnets are narrowed to the phone's own /24 to keep the search bounded. */
    public static final int MAX_SCAN_HOSTS = 1022;
    static final int PRIORITY_TIMEOUT_MS = 1500, SCAN_TIMEOUT_MS = 700;

    /** The certificate a host presented: formatted SHA-256 fingerprint and RFC 2253 subject. */
    public static final class Certificate {
        public final String fingerprint, subject;
        public Certificate(String fingerprint, String subject) { this.fingerprint = fingerprint == null ? "" : fingerprint; this.subject = subject == null ? "" : subject; }
    }

    /** A located primary. {@code certificateChanged} means the serial matched but the certificate is new. */
    public static final class Match {
        public final String host, fingerprint; public final boolean certificateChanged;
        Match(String host, String fingerprint, boolean certificateChanged) { this.host = host; this.fingerprint = fingerprint; this.certificateChanged = certificateChanged; }
    }

    public interface Probe {
        /** Certificate-only TLS inspection. */
        Certificate certificate(String host, int timeoutMs) throws Exception;
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

    /** Returns the located primary, or null when it was not found. */
    public static Match locate(Probe probe, Remembered remembered, Plan plan, int threads, long budgetMs, AtomicBoolean cancelled) throws InterruptedException {
        if (!remembered.valid()) return null;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        for (String host : plan.priority) {
            if (cancelled.get() || System.nanoTime() > deadline) return null;
            Match match = match(probe, remembered, host, PRIORITY_TIMEOUT_MS);
            if (match != null) return match;
        }
        if (plan.scan.isEmpty() || cancelled.get()) return null;
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(threads, plan.scan.size())));
        try {
            CompletionService<Match> results = new ExecutorCompletionService<>(pool);
            for (String host : plan.scan) results.submit(() -> cancelled.get() ? null : match(probe, remembered, host, SCAN_TIMEOUT_MS));
            for (int done = 0; done < plan.scan.size(); done++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || cancelled.get()) return null;
                Future<Match> next = results.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(250)), TimeUnit.NANOSECONDS);
                if (next == null) { done--; continue; }
                try { Match match = next.get(); if (match != null) return match; } catch (ExecutionException ignored) {}
            }
            return null;
        } finally { pool.shutdownNow(); }
    }

    static Match match(Probe probe, Remembered remembered, String host, int timeoutMs) {
        try {
            Certificate presented = probe.certificate(host, timeoutMs);
            boolean approved = remembered.fingerprint.equals(presented.fingerprint);
            // Other devices never receive an HTTP request. Nodes on the same firmware build share a
            // certificate, and updates replace it, so the serial number decides.
            if (!approved && !linksys(presented.subject)) return null;
            if (!remembered.serial.equals(probe.serial(host, presented.fingerprint))) return null;
            return new Match(host, presented.fingerprint, !approved);
        } catch (Exception e) { return null; }
    }

    /** Stock Linksys firmware certificates are issued for linksyssmartwifi.com. */
    public static boolean linksys(String subject) {
        if (subject == null) return false;
        for (String part : subject.split("(?<!\\\\),")) if (part.trim().equalsIgnoreCase("CN=linksyssmartwifi.com")) return true;
        return false;
    }

    /** Text for the certificate approval dialog. {@code sameSerial} is null when no serial was compared. */
    public static String trustNotice(boolean changed, boolean linksys, Boolean sameSerial) {
        StringBuilder b = new StringBuilder();
        if (changed) {
            b.append("Linksys firmware updates replace this certificate, so a change right after an update is expected. A change without an update may mean another device is answering at this address.");
            if (Boolean.TRUE.equals(sameSerial)) b.append(" This device reports the same serial number as your primary Velop.");
            else if (Boolean.FALSE.equals(sameSerial)) b.append(" This device reports a DIFFERENT serial number from your primary Velop.");
            b.append("\n\n");
        }
        if (linksys) b.append("Linksys uses one certificate per firmware build, shared by its nodes, and its private key is included in public firmware downloads. Pinning detects unexpected changes but cannot prove which device answered. ");
        return b.append("Confirm this is your router on a trusted local network. Credentials have not been sent. This certificate will be pinned for future connections.").toString();
    }

    private static String privateOrEmpty(String host) {
        if (host == null || host.isEmpty()) return "";
        try { return ReadPolicy.privateAddress(host); } catch (IllegalArgumentException e) { return ""; }
    }
    private static String format(long ip) { return ((ip >> 24) & 255) + "." + ((ip >> 16) & 255) + "." + ((ip >> 8) & 255) + "." + (ip & 255); }
    private PrimaryLocator() {}
}
