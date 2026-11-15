package com.velopcontrol.app;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class NetworkToolsTest {
    private static JSONObject ok(String output) throws Exception { return new JSONObject("{\"result\":\"OK\",\"output\":" + output + "}"); }
    private static Topology mesh() { Topology t = new Topology(); for (String[] r : new String[][]{{"A", "Primary"}, {"B", "Office"}}) { Topology.Node n = new Topology.Node(); n.id = r[0]; n.name = r[1]; t.nodes.add(n); } return t; }

    @Test public void channelFinderParsesRunningStateDfsAndChanges() throws Exception {
        NetworkTools.Channels before = NetworkTools.channels(ok("{\"isRunning\":false,\"selectedChannels\":[{\"deviceID\":\"a\",\"channels\":[{\"radioID\":\"RADIO_5GHz\",\"band\":\"5GHz\",\"channel\":100},{\"radioID\":\"RADIO_2.4GHz\",\"band\":\"2.4GHz\",\"channel\":6}]}]}"), mesh());
        NetworkTools.Channels after = NetworkTools.channels(ok("{\"isRunning\":false,\"selectedChannels\":[{\"deviceID\":\"A\",\"channels\":[{\"radioID\":\"RADIO_5GHz\",\"band\":\"5GHz\",\"channel\":44},{\"radioID\":\"RADIO_2.4GHz\",\"band\":\"2.4GHz\",\"channel\":6}]},{\"deviceID\":\"B\",\"channels\":[{\"radioID\":\"RADIO_5GHz\",\"band\":\"5GHz\",\"channel\":0}]}]}"), mesh());
        NetworkTools.Radio five = after.radios.stream().filter(r -> r.node.equals("Primary") && r.band.equals("5GHz")).findFirst().get();
        assertEquals(Integer.valueOf(100), NetworkTools.previous(before, five));
        assertTrue(before.radios.stream().anyMatch(NetworkTools.Radio::dfs)); assertFalse(five.dfs());
        assertNull("New node radios have no previous channel", NetworkTools.previous(before, after.radios.stream().filter(r -> r.node.equals("Office")).findFirst().get()));
        assertTrue(NetworkTools.channels(ok("{\"isRunning\":true}"), mesh()).running);
        assertEquals("5 GHz", five.label());
        NetworkTools.Channels named = NetworkTools.channels(ok("{\"selectedChannels\":[{\"deviceID\":\"A\",\"channels\":[{\"radioID\":\"RADIO_5GHz_2\",\"band\":\"5GHz\",\"channel\":149},{\"radioID\":\"5GH\",\"band\":\"5GHz\",\"channel\":157}]}]}"), mesh());
        assertTrue(named.radios.stream().anyMatch(r -> r.label().equals("5 GHz · radio 2")));
        assertTrue(named.radios.stream().anyMatch(r -> r.label().equals("5 GHz · high band")));
        try { NetworkTools.channels(new JSONObject("{\"result\":\"ErrorDeviceNotInMasterMode\"}"), mesh()); fail(); } catch (IllegalArgumentException expected) {}
    }
    @Test public void speedCheckStatusAndHistory() throws Exception {
        assertNull("No run pending", NetworkTools.speedStatus(ok("{}")));
        NetworkTools.Speed running = NetworkTools.speedStatus(ok("{\"healthCheckModuleCurrentlyRunning\":\"SpeedTest\",\"speedTestResult\":{\"resultID\":7,\"exitCode\":\"Unavailable\",\"latency\":12,\"downloadBandwidth\":0}}"));
        assertTrue(running.running); assertEquals("Running…", NetworkTools.describe(running.state));
        NetworkTools.Speed done = NetworkTools.speedStatus(ok("{\"speedTestResult\":{\"exitCode\":\"Success\",\"serverID\":\"15751\",\"latency\":11,\"downloadBandwidth\":512345,\"uploadBandwidth\":21000}}"));
        assertFalse(done.running); assertTrue(done.succeeded()); assertEquals("512.3 Mbps", done.down()); assertEquals("21.0 Mbps", done.up()); assertEquals("15751", done.server);
        List<NetworkTools.Speed> h = NetworkTools.speedHistory(ok("{\"healthCheckResults\":[{\"resultID\":1,\"timestamp\":\"2026-11-01T10:00:00Z\",\"speedTestResult\":{\"exitCode\":\"Success\",\"downloadBandwidth\":1000}},{\"resultID\":2,\"timestamp\":\"2026-11-14T10:00:00Z\",\"speedTestResult\":{\"exitCode\":\"SpeedTestExecutionError\"}}]}"));
        assertEquals("Newest first", "2026-11-14T10:00:00Z", h.get(0).timestamp); assertEquals("—", h.get(0).down());
        assertTrue(NetworkTools.describe("SpeedTestLicenseError").contains("licence"));
    }
    @Test public void tracerouteWpsAndLogsAreSanitized() throws Exception {
        assertEquals("1 192.168.1.1\n2 10.0.0.1", NetworkTools.trace(ok("{\"isRunning\":false,\"tracerouteLog\":[\"1 192.168.1.1\",\"2 10.0.0.1\u0007\"]}")).log);
        NetworkTools.Trace text = NetworkTools.trace(ok("{\"isRunning\":true,\"tracerouteLog\":\"a\\nb\"}")); assertTrue(text.running); assertEquals("a\nb", text.log);
        NetworkTools.Wps w = NetworkTools.wps(ok("{\"isWPSSessionInProgress\":false,\"lastResult\":\"Succeeded\",\"serverPIN\":\"12345670\"}")); assertFalse(w.inProgress); assertEquals("Succeeded", w.result);
        assertEquals("", NetworkTools.wps(ok("{\"isWPSSessionInProgress\":true,\"lastResult\":\"<script>\"}")).result);
        List<String> logs = NetworkTools.logEntries(ok("{\"entries\":[{\"source\":\"203.0.113.9\",\"destinationPort\":443}]}"));
        assertEquals(Collections.singletonList("Destination Port: 443 · Source: 203.0.113.9"), logs);
        assertTrue(NetworkTools.validHost("one.one.one.one")); assertFalse(NetworkTools.validHost("-x")); assertFalse(NetworkTools.validHost("a b"));
    }
    @Test public void everyToolActionIsAllowlistedAndNewPagesExist() throws Exception {
        try (java.io.InputStream in = new java.io.FileInputStream("src/main/assets/settings.json")) { SettingsCatalog.load(in); }
        for (String a : NetworkTools.READS) assertTrue(a, SettingsCatalog.allowsRead(a));
        for (String a : NetworkTools.WRITES) assertTrue(a, SettingsCatalog.allowsWrite(a));
        assertTrue(SettingsCatalog.allowsRead(NetworkTools.CHANNELS)); assertTrue(SettingsCatalog.allowsWrite(NetworkTools.CHANNEL_START));
        for (String id : new String[]{"airtime", "expressforwarding", "iptv", "guestclients", "routingtable", "remoteui", "datasharing"}) assertNotNull(SettingsCatalog.find(id));
        SettingsCatalog.Spec dfs = SettingsCatalog.find("dfs"); assertTrue(dfs.editable()); assertTrue(dfs.disruption);
        assertEquals(Collections.singletonList("isDFSEnabled"), dfs.paths);
        assertTrue(SettingsCatalog.find("iptv").impact.contains("192.168.10.1"));
        assertFalse("Destructive firmware actions stay blocked", SettingsCatalog.allowsWrite("core/FactoryReset") || SettingsCatalog.allowsWrite("diagnostics/RestorePreviousFirmware"));
    }
}
