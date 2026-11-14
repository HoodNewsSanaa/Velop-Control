package com.velopcontrol.app;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class NeighborSignalsTest {
    /** P primary, W wired child of P, C wireless child of P (uplink rssi given), K wireless child of C. */
    private static Topology mesh(String cRssi) {
        Topology t = new Topology();
        String[][] rows = {{"P", null, "Primary", "", "AA:00:00:00:01:10"}, {"W", "P", "Wired child", "Wired", "AA:00:00:00:02:10"},
            {"C", "P", "Problem node", "Wireless", "AA:00:00:00:03:10"}, {"K", "C", "Kid", "Wireless", "AA:00:00:00:04:10"}};
        for (String[] r : rows) { Topology.Node n = new Topology.Node(); n.id = r[0]; n.parentId = r[1]; n.name = r[2]; n.link = r[3]; n.primary = r[0].equals("P"); n.macs.add(r[4]); n.rssi = r[0].equals("C") ? cRssi : r[0].equals("K") ? "-55" : ""; t.nodes.add(n); }
        return t;
    }
    private static JSONObject report(String node, Object... heard) throws Exception {
        JSONArray list = new JSONArray();
        for (int i = 0; i < heard.length; i += 3) list.put(new JSONObject().put("macAddress", heard[i]).put("channel", heard[i + 1]).put("rssi", heard[i + 2]));
        JSONArray devices = new JSONArray().put(new JSONObject().put("deviceUUID", node).put("nodeMode", "Slave").put("neighborNodes", list));
        return new JSONObject().put("result", "OK").put("output", new JSONObject().put("nodeNeighborDevices", devices));
    }
    private static NeighborSignals.Report only(Topology t, JSONObject r) { List<NeighborSignals.Report> l = NeighborSignals.parse(r, t); assertEquals(1, l.size()); return l.get(0); }

    @Test public void closerWiredNodeMoreThanTenDbStrongerWins() throws Exception {
        NeighborSignals.Report r = only(mesh("-72"), report("C", "AA:00:00:00:01:11", 149, -72, "AA:00:00:00:02:11", 149, -58));
        assertEquals("W", r.wouldMoveTo.id); assertTrue(r.verdict.contains("14 dB stronger"));
        assertFalse("Matched by nearby BSSID, not an exact MAC", r.heard.get(0).exact); assertEquals("W", r.heard.get(0).node.id);
    }
    @Test public void tenDbOrLessKeepsTheNodeOnItsParent() throws Exception {
        NeighborSignals.Report r = only(mesh("-72"), report("C", "AA:00:00:00:01:11", 149, -72, "AA:00:00:00:02:11", 149, -62));
        assertNull(r.wouldMoveTo); assertTrue(r.verdict.contains("only 10 dB stronger")); assertTrue(r.verdict.contains("stays on Primary"));
    }
    @Test public void adequateUplinkIsNeverSteered() throws Exception {
        NeighborSignals.Report r = only(mesh("-60"), report("C", "AA:00:00:00:02:11", 149, -40));
        assertNull(r.wouldMoveTo); assertTrue(r.verdict.contains("at or above -60 dBm"));
    }
    @Test public void childrenAndTwoPointFourGhzAreIgnored() throws Exception {
        NeighborSignals.Report r = only(mesh("-75"), report("C", "AA:00:00:00:04:11", 149, -40, "AA:00:00:00:02:12", 6, -45, "AA:00:00:00:01:11", 149, -74));
        assertNull(r.wouldMoveTo); assertTrue(r.verdict, r.verdict.contains("already the strongest"));
        assertTrue(r.heard.stream().anyMatch(NeighborSignals.Heard::ignoredBand));
    }
    @Test public void exactMacMatchAndUnknownRadiosAreReported() throws Exception {
        NeighborSignals.Report r = only(mesh("-72"), report("C", "AA:00:00:00:02:10", 149, -50, "BB:11:22:33:44:55", 149, -30, "AA:00:00:00:03:11", 149, -20));
        assertTrue(r.heard.stream().anyMatch(h -> h.exact && h.node.id.equals("W")));
        assertTrue(r.heard.stream().anyMatch(h -> h.node == null));
        assertTrue("Its own radios are dropped", r.heard.stream().noneMatch(h -> h.node != null && h.node.id.equals("C")));
        assertEquals("W", r.wouldMoveTo.id);
    }
    @Test public void ambiguousNearbyAddressesAreNotGuessed() {
        Topology t = mesh("-70"); t.nodes.get(1).macs.add("AA:00:00:00:01:14");
        assertNull(NeighborSignals.nearby(t, "AA:00:00:00:01:12"));
        assertEquals("P", NeighborSignals.nearby(t, "AE:00:00:00:01:10").id);
    }
    @Test public void wiredAndPrimaryNodesExplainThemselves() throws Exception {
        assertTrue(only(mesh("-70"), report("W")).verdict.startsWith("Wired uplink"));
        assertTrue(only(mesh("-70"), report("P")).verdict.startsWith("Primary node"));
        try { NeighborSignals.parse(new JSONObject("{\"result\":\"ErrorDeviceNotInMasterMode\"}"), mesh("-70")); fail(); } catch (IllegalArgumentException expected) {}
    }
    @Test public void topologyRecordsNodeMacsFromEverySource() throws Exception {
        Map<String,JSONObject> raw = new HashMap<>();
        raw.put(ReadPolicy.DEVICES, new JSONObject("{\"result\":\"OK\",\"output\":{\"devices\":[{\"deviceID\":\"A\",\"isAuthority\":true,\"connections\":[{\"ipAddress\":\"192.168.1.10\",\"macAddress\":\"aa-00-00-00-01-10\"}]},{\"deviceID\":\"B\",\"nodeType\":\"Slave\",\"knownInterfaces\":[{\"macAddress\":\"AA:00:00:00:02:10\"}]}]}}"));
        raw.put(ReadPolicy.BACKHAUL, new JSONObject("{\"result\":\"OK\",\"output\":{\"backhaulDevices\":[{\"deviceUUID\":\"B\",\"ipAddress\":\"192.168.1.11\",\"parentIPAddress\":\"192.168.1.10\",\"connectionType\":\"Wireless\",\"wirelessConnectionInfo\":{\"stationBSSID\":\"AA:00:00:00:02:13\",\"apBSSID\":\"AA:00:00:00:01:13\"}}]}}"));
        Topology t = Topology.parse(raw);
        Map<String,Topology.Node> n = new HashMap<>(); for (Topology.Node x : t.nodes) n.put(x.id, x);
        assertTrue(n.get("A").macs.containsAll(Arrays.asList("AA:00:00:00:01:10", "AA:00:00:00:01:13")));
        assertTrue(n.get("B").macs.containsAll(Arrays.asList("AA:00:00:00:02:10", "AA:00:00:00:02:13")));
    }
    @Test public void positiveFirmwareValuesAreSignalToNoiseLikeTheFirmwareReadsThem() throws Exception {
        // 37 -> -58 dBm (wired child), 23 -> -72 dBm (primary): the firmware's QCA offset of -95.
        NeighborSignals.Report r = only(mesh("-72"), report("C", "AA:00:00:00:01:11", 149, 23, "AA:00:00:00:02:11", 149, 37));
        assertEquals(-58, r.heard.get(0).rssi); assertEquals(-72, r.heard.get(1).rssi);
        assertEquals("W", r.wouldMoveTo.id);
        assertEquals(-95 + 40, NeighborSignals.toDbm(40)); assertEquals(-61, NeighborSignals.toDbm(-61));
    }
    @Test public void nodesWithoutAReportAreListed() throws Exception {
        Topology t = mesh("-72");
        List<NeighborSignals.Report> reports = NeighborSignals.parse(report("c"), t);
        List<String> silent = new ArrayList<>(); for (Topology.Node n : NeighborSignals.missing(reports, t)) silent.add(n.id);
        assertEquals(Arrays.asList("P", "W", "K"), silent);
    }
}
