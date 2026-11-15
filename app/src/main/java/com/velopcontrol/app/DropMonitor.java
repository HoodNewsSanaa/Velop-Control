package com.velopcontrol.app;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Function;

/**
 * Connection-drop log: one sample per probe, stored as CSV, analysed into outages, roams and gaps.
 * A probe time of -1 means that check failed; null means it was not checked.
 */
public final class DropMonitor {
    public static final String HEADER = "time_ms,wifi,bssid,rssi_dbm,link_mbps,freq_mhz,gateway_ms,primary_ms,internet_ms,dns_ms";

    public static final class Sample {
        public final long time; public final boolean wifi; public final String bssid; public final Integer rssi, linkMbps, freqMhz, gatewayMs, primaryMs, internetMs, dnsMs;
        public Sample(long time, boolean wifi, String bssid, Integer rssi, Integer linkMbps, Integer freqMhz, Integer gatewayMs, Integer primaryMs, Integer internetMs, Integer dnsMs) {
            this.time = time; this.wifi = wifi; this.bssid = bssid == null ? "" : bssid; this.rssi = rssi; this.linkMbps = linkMbps; this.freqMhz = freqMhz;
            this.gatewayMs = gatewayMs; this.primaryMs = primaryMs; this.internetMs = internetMs; this.dnsMs = dnsMs;
        }
        public String csv() {
            return time + "," + (wifi ? 1 : 0) + "," + bssid + "," + n(rssi) + "," + n(linkMbps) + "," + n(freqMhz) + "," + n(gatewayMs) + "," + n(primaryMs) + "," + n(internetMs) + "," + n(dnsMs);
        }
        private static String n(Integer v) { return v == null ? "" : String.valueOf(v); }
        private static boolean failed(Integer v) { return v != null && v < 0; }

        /** The lowest failing layer, or null when everything checked was fine. */
        public String problem() {
            if (!wifi) return "Wi-Fi disconnected";
            boolean gw = failed(gatewayMs), velop = failed(primaryMs);
            if (gw && (velop || primaryMs == null) || velop && gatewayMs == null) return "Local network unreachable while on Wi-Fi";
            if (velop) return "Primary Velop unreachable (main router reachable)";
            if (gw) return "Main router / gateway unreachable (Velop reachable)";
            if (failed(internetMs)) return "Internet unreachable (local network fine)";
            if (failed(dnsMs)) return "DNS lookups failing (internet reachable)";
            return null;
        }
    }

    public static Sample parse(String line) {
        String[] f = line.split(",", -1);
        if (f.length != 10 || line.startsWith("#") || line.startsWith("time_ms")) return null;
        try {
            return new Sample(Long.parseLong(f[0]), "1".equals(f[1]), f[2].matches("[0-9A-Fa-f:]{17}") ? f[2].toUpperCase(Locale.ROOT) : "",
                i(f[3]), i(f[4]), i(f[5]), i(f[6]), i(f[7]), i(f[8]), i(f[9]));
        } catch (NumberFormatException e) { return null; }
    }
    private static Integer i(String v) { return v.isEmpty() ? null : Integer.valueOf(v); }

    public static final class Event {
        public final long start, end; public final String kind, detail; public final boolean problem;
        Event(long start, long end, String kind, String detail, boolean problem) { this.start = start; this.end = end; this.kind = kind; this.detail = detail; this.problem = problem; }
        public long seconds() { return Math.max(0, (end - start) / 1000); }
    }

    public static final class Report {
        public final List<Event> events; public final int samples, outages; public final long downSeconds, coveredSeconds;
        public final Map<String, Integer> causes;
        Report(List<Event> events, int samples, int outages, long downSeconds, long coveredSeconds, Map<String, Integer> causes) {
            this.events = events; this.samples = samples; this.outages = outages; this.downSeconds = downSeconds; this.coveredSeconds = coveredSeconds; this.causes = causes;
        }
    }

    /**
     * Groups consecutive problem samples into outages, reports node changes (BSSID), and marks gaps
     * longer than three intervals as "monitoring paused" so Android sleep is not mistaken for a drop.
     */
    public static Report analyse(List<Sample> samples, long intervalMs, Function<String, String> nodeName) {
        List<Event> events = new ArrayList<>();
        Map<String, Integer> causes = new LinkedHashMap<>();
        int outages = 0; long down = 0;
        Sample prev = null, outageStart = null, lastGood = null; String firstCause = null; Set<String> seen = new LinkedHashSet<>();
        for (Sample s : samples) {
            if (prev != null && s.time - prev.time > 3 * intervalMs)
                events.add(new Event(prev.time, s.time, "Monitoring paused", "No checks for " + (s.time - prev.time) / 1000 + " s. Android paused the app (screen off without charging, or battery saver); this is not a drop.", false));
            if (prev != null && prev.wifi && s.wifi && !prev.bssid.isEmpty() && !s.bssid.isEmpty() && !prev.bssid.equals(s.bssid))
                events.add(new Event(s.time, s.time, "Switched node", name(prev.bssid, nodeName) + " → " + name(s.bssid, nodeName) + signal(s), false));
            String problem = s.problem();
            if (problem != null) {
                if (outageStart == null) { outageStart = s; firstCause = problem; seen.clear(); }
                seen.add(problem);
            } else if (outageStart != null) {
                events.add(outage(outageStart, s, lastGood, firstCause, seen, nodeName, false));
                outages++; down += (s.time - outageStart.time) / 1000; causes.merge(firstCause, 1, Integer::sum); outageStart = null;
            }
            if (problem == null) lastGood = s;
            prev = s;
        }
        if (outageStart != null && prev != null) {
            events.add(outage(outageStart, prev, lastGood, firstCause, seen, nodeName, true));
            outages++; down += (prev.time - outageStart.time) / 1000; causes.merge(firstCause, 1, Integer::sum);
        }
        events.sort(Comparator.comparingLong(e -> e.start));
        long covered = samples.size() < 2 ? 0 : (samples.get(samples.size() - 1).time - samples.get(0).time) / 1000;
        return new Report(Collections.unmodifiableList(events), samples.size(), outages, down, covered, causes);
    }

    private static Event outage(Sample start, Sample end, Sample before, String cause, Set<String> seen, Function<String, String> nodeName, boolean ongoing) {
        StringBuilder d = new StringBuilder(cause);
        if (seen.size() > 1) { List<String> other = new ArrayList<>(seen); other.remove(cause); d.append(". Also: ").append(String.join("; ", other)); }
        if (before != null && before.wifi) d.append(". Before: ").append(before.bssid.isEmpty() ? "on Wi-Fi" : "on " + name(before.bssid, nodeName)).append(signal(before));
        if (end.wifi && end.problem() == null && before != null && !before.bssid.isEmpty() && !end.bssid.isEmpty() && !before.bssid.equals(end.bssid))
            d.append(". Back on ").append(name(end.bssid, nodeName)).append(" (switched node)");
        return new Event(start.time, end.time, ongoing ? "Problem (still ongoing when monitoring stopped)" : "Drop", d.toString(), true);
    }
    private static String name(String bssid, Function<String, String> nodeName) {
        String n = nodeName == null ? null : nodeName.apply(bssid);
        return n == null ? "radio ··" + bssid.substring(Math.max(0, bssid.length() - 5)) : n;
    }
    private static String signal(Sample s) {
        StringBuilder b = new StringBuilder();
        if (s.rssi != null) b.append(", ").append(s.rssi).append(" dBm");
        if (s.freqMhz != null && s.freqMhz > 0) b.append(", ").append(s.freqMhz < 3000 ? "2.4 GHz" : s.freqMhz < 5925 ? "5 GHz" : "6 GHz");
        return b.toString();
    }

    public static String summary(Report r, long intervalMs) {
        StringBuilder b = new StringBuilder();
        b.append(r.samples).append(" checks every ").append(intervalMs / 1000).append(" s over ").append(duration(r.coveredSeconds)).append(". ");
        if (r.outages == 0) b.append("No drops recorded.");
        else {
            b.append(r.outages).append(r.outages == 1 ? " drop" : " drops").append(", ").append(duration(r.downSeconds)).append(" in total. ");
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : r.causes.entrySet()) parts.add(e.getValue() + "× " + e.getKey());
            b.append("Started as: ").append(String.join("; ", parts)).append('.');
        }
        return b.toString();
    }
    public static String duration(long seconds) {
        if (seconds < 60) return seconds + " s";
        if (seconds < 3600) return (seconds / 60) + " min " + (seconds % 60) + " s";
        return (seconds / 3600) + " h " + (seconds / 60 % 60) + " min";
    }
    public static String time(long ms) { return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(ms)); }
    private DropMonitor() {}
}
