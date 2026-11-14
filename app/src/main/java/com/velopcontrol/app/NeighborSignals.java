package com.velopcontrol.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/**
 * What each node hears of the other nodes (nodes/diagnostics/GetNodeNeighborInfo, primary only), and
 * what the stock steering engine would do about it.
 *
 * The verdict mirrors the firmware's tess_steer_local_decision_eng with its default settings: a
 * wireless uplink at or above -60 dBm is left alone; otherwise a node is told to reconsider when
 * another node (excluding its own children and 2.4 GHz radios) is more than 10 dB stronger, or
 * when the strongest is the primary. Settings changed on a router would change the outcome.
 */
public final class NeighborSignals {
    public static final String ACTION = "nodes/diagnostics/GetNodeNeighborInfo";
    public static final int ADEQUATE_DBM = -60, STEER_MARGIN_DB = 10;

    public static final class Heard {
        public final String bssid; public final int channel, rssi; public final Topology.Node node; public final boolean exact;
        Heard(String bssid, int channel, int rssi, Topology.Node node, boolean exact) { this.bssid = bssid; this.channel = channel; this.rssi = rssi; this.node = node; this.exact = exact; }
        public boolean ignoredBand() { return channel > 0 && channel <= 14; }
        public String band() { return channel <= 0 ? "Channel not reported" : channel <= 14 ? "2.4 GHz · channel " + channel : "5 GHz · channel " + channel; }
    }

    public static final class Report {
        public final Topology.Node node; public final String deviceId; public final List<Heard> heard;
        public final String verdict; public final Topology.Node wouldMoveTo; public final boolean attention;
        Report(Topology.Node node, String deviceId, List<Heard> heard, String verdict, Topology.Node wouldMoveTo, boolean attention) {
            this.node = node; this.deviceId = deviceId; this.heard = Collections.unmodifiableList(heard); this.verdict = verdict; this.wouldMoveTo = wouldMoveTo; this.attention = attention;
        }
    }

    public static List<Report> parse(JSONObject response, Topology topology) {
        SettingsRepository.requireOK(response);
        JSONObject output = response.optJSONObject("output");
        JSONArray devices = output == null ? null : output.optJSONArray("nodeNeighborDevices");
        if (devices == null) throw new IllegalArgumentException("This firmware returned no neighbor report");
        List<Report> reports = new ArrayList<>();
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i); if (d == null) continue;
            String id = d.optString("deviceUUID");
            Topology.Node node = byId(topology, id);
            List<Heard> heard = new ArrayList<>();
            JSONArray list = d.optJSONArray("neighborNodes");
            for (int j = 0; list != null && j < list.length(); j++) {
                JSONObject n = list.optJSONObject(j); if (n == null) continue;
                String bssid = Topology.mac(n.optString("macAddress"));
                Object rssi = n.opt("rssi");
                if (!bssid.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}") || !(rssi instanceof Number)) continue;
                int dbm = ((Number) rssi).intValue();
                if (dbm >= 0 || dbm < -120) continue;
                Topology.Node exact = exact(topology, bssid);
                Topology.Node match = exact != null ? exact : nearby(topology, bssid);
                if (match != null && node != null && match.id.equals(node.id)) continue;
                heard.add(new Heard(bssid, n.optInt("channel", 0), dbm, match, exact != null));
            }
            heard.sort(Comparator.comparingInt((Heard h) -> -h.rssi));
            reports.add(assess(node, id, heard, topology));
        }
        reports.sort(Comparator.comparing((Report r) -> r.node == null || !r.node.primary).thenComparing(r -> r.node == null ? "~" + r.deviceId : r.node.name));
        return reports;
    }

    static Report assess(Topology.Node node, String id, List<Heard> heard, Topology topology) {
        if (node == null) return new Report(null, id, heard, "This node is not in the current inventory. Refresh the mesh and try again.", null, false);
        if (node.primary) return new Report(node, id, heard, "Primary node: it has no uplink to steer.", null, false);
        if ("Wired".equalsIgnoreCase(node.link)) return new Report(node, id, heard, "Wired uplink: Wi-Fi steering does not apply.", null, false);
        Integer current = dbm(node.rssi);
        if (current == null) return new Report(node, id, heard, "Its current uplink signal is not reported, so the firmware's decision cannot be predicted.", null, false);
        if (current >= ADEQUATE_DBM) return new Report(node, id, heard, "Uplink " + current + " dBm is at or above " + ADEQUATE_DBM + " dBm, so the firmware leaves it on " + parentName(node, topology) + ".", null, false);
        // Strongest reading per node, excluding itself, its own children and 2.4 GHz radios.
        Map<String, Heard> best = new LinkedHashMap<>();
        for (Heard h : heard) {
            if (h.node == null || h.ignoredBand() || node.id.equals(h.node.parentId)) continue;
            Heard seen = best.get(h.node.id);
            if (seen == null || h.rssi > seen.rssi) best.put(h.node.id, h);
        }
        Heard top = null;
        for (Heard h : best.values()) if (top == null || h.rssi > top.rssi) top = h;
        if (top == null) return new Report(node, id, heard, "Weak uplink (" + current + " dBm), but it hears no other identified node on 5 GHz, so there is nowhere to steer it.", null, true);
        if (top.node.id.equals(node.parentId)) return new Report(node, id, heard, "Weak uplink (" + current + " dBm), but its current parent is already the strongest node it hears.", null, true);
        int margin = top.rssi - current;
        Topology.Node parent = byId(topology, node.parentId);
        boolean bothPrimary = top.node.primary && parent != null && parent.primary;
        if (margin > STEER_MARGIN_DB && !bothPrimary)
            return new Report(node, id, heard, top.node.name + " is " + margin + " dB stronger (" + top.rssi + " vs " + current + " dBm), so the firmware should move it there at its next check (about hourly, or after a restart).", top.node, true);
        if (top.node.primary && !bothPrimary)
            return new Report(node, id, heard, "The strongest node it hears is the primary (" + top.rssi + " dBm), so the firmware should move it to the primary at its next check.", top.node, true);
        return new Report(node, id, heard, top.node.name + " is only " + Math.max(0, margin) + " dB stronger (" + top.rssi + " vs " + current + " dBm). The firmware needs more than " + STEER_MARGIN_DB + " dB before it moves a node, so it stays on " + parentName(node, topology) + ".", null, true);
    }

    private static String parentName(Topology.Node node, Topology topology) {
        Topology.Node parent = byId(topology, node.parentId);
        return parent == null ? "its current parent" : parent.name;
    }
    private static Integer dbm(String value) {
        try { double d = Double.parseDouble(value); return Double.isFinite(d) ? (int) Math.round(d) : null; } catch (Exception e) { return null; }
    }
    private static Topology.Node byId(Topology t, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Topology.Node n : t.nodes) if (n.id.equalsIgnoreCase(id)) return n;
        return null;
    }
    private static Topology.Node exact(Topology t, String bssid) {
        for (Topology.Node n : t.nodes) if (n.macs.contains(bssid)) return n;
        return null;
    }

    /**
     * Linksys nodes allocate their radio BSSIDs in a small block around their base MAC (the firmware
     * itself treats BSSIDs one apart as the same node), and Qualcomm virtual APs vary the first octet.
     * Accept a match only when exactly one node is that close.
     */
    static Topology.Node nearby(Topology t, String bssid) {
        long target = value(bssid);
        Topology.Node found = null;
        for (Topology.Node n : t.nodes) {
            boolean close = false;
            for (String m : n.macs) {
                long v = value(m);
                boolean sameLow40 = (v & 0xFFFFFFFFFFL) == (target & 0xFFFFFFFFFFL);
                boolean sameHigh40 = (v >>> 8) == (target >>> 8) && Math.abs((v & 0xFF) - (target & 0xFF)) <= 8;
                if (sameLow40 || sameHigh40) { close = true; break; }
            }
            if (close) { if (found != null && found != n) return null; found = n; }
        }
        return found;
    }
    private static long value(String mac) { return Long.parseLong(mac.replace(":", ""), 16); }

    /** Synthetic report for the demo mesh: same shape as the firmware response. */
    public static JSONObject demo(Topology t) {
        try {
            JSONArray devices = new JSONArray();
            for (int i = 0; i < t.nodes.size(); i++) {
                JSONArray neighbors = new JSONArray();
                for (int j = 0; j < t.nodes.size(); j++) {
                    if (i == j) continue;
                    int rssi = -45 - 7 * Math.abs(i - j) - (i == 4 && j == 0 ? 6 : 0) + (i == 4 && j == 1 ? 9 : 0);
                    neighbors.put(new JSONObject().put("macAddress", String.format(Locale.ROOT, "02:00:00:00:%02X:11", j)).put("channel", 149).put("rssi", rssi));
                }
                devices.put(new JSONObject().put("deviceUUID", t.nodes.get(i).id).put("nodeMode", i == 0 ? "Master" : "Slave").put("neighborNodes", neighbors));
            }
            return new JSONObject().put("result", "OK").put("output", new JSONObject().put("nodeNeighborDevices", devices));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private NeighborSignals() {}
}
