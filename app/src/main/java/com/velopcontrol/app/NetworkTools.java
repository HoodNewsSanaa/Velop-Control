package com.velopcontrol.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/**
 * Parsers for the stock firmware's active tools: Channel Finder, Speed Check (Ookla health check),
 * traceroute, WPS push-button pairing and router logs. No network access here.
 */
public final class NetworkTools {
    public static final String CHANNELS = "nodes/setup/GetSelectedChannels", CHANNEL_START = "nodes/setup/StartAutoChannelSelection";
    public static final String SPEED_RUN = "healthcheck/RunHealthCheck", SPEED_STATUS = "healthcheck/GetHealthCheckStatus",
        SPEED_RESULTS = "healthcheck/GetHealthCheckResults", SPEED_STOP = "healthcheck/StopHealthCheck";
    public static final String TRACE_START = "diagnostics/StartTraceroute", TRACE_STATUS = "diagnostics/GetTracerouteStatus", TRACE_STOP = "diagnostics/StopTraceroute";
    public static final String WPS_AVAILABLE = "wirelessap/IsWPSServerAvailable", WPS_START = "wirelessap/StartWPSServerSession",
        WPS_STATUS = "wirelessap/GetWPSServerSessionStatus", WPS_STOP = "wirelessap/StopWPSServerSession";
    public static final String FIRMWARE_NOW = "nodes/firmwareupdate/UpdateFirmwareNow", FIRMWARE_STATUS = "nodes/firmwareupdate/GetFirmwareUpdateStatus";
    public static final String DELETE_DEVICE = "devicelist/DeleteDevice", LOG_DELETE = "routerlog/DeleteLogEntries";
    /** Log kind label → read action. */
    public static final Map<String, String> LOGS;
    static {
        Map<String, String> logs = new LinkedHashMap<>();
        logs.put("Incoming traffic", "routerlog/GetIncomingLogEntries");
        logs.put("Outgoing traffic", "routerlog/GetOutgoingLogEntries");
        logs.put("Security (admin sign-ins)", "routerlog/GetSecurityLogEntries");
        logs.put("DHCP clients", "routerlog/GetDHCPLogEntries");
        LOGS = Collections.unmodifiableMap(logs);
    }
    public static final List<String> READS = Collections.unmodifiableList(Arrays.asList(SPEED_STATUS, SPEED_RESULTS, TRACE_STATUS, WPS_AVAILABLE, WPS_STATUS, FIRMWARE_STATUS,
        "routerlog/GetIncomingLogEntries", "routerlog/GetOutgoingLogEntries", "routerlog/GetSecurityLogEntries", "routerlog/GetDHCPLogEntries"));
    public static final List<String> WRITES = Collections.unmodifiableList(Arrays.asList(SPEED_RUN, SPEED_STOP, TRACE_START, TRACE_STOP, WPS_START, WPS_STOP,
        FIRMWARE_NOW, DELETE_DEVICE, LOG_DELETE));

    private static JSONObject output(JSONObject response) {
        SettingsRepository.requireOK(response);
        JSONObject o = response.optJSONObject("output");
        return o == null ? new JSONObject() : o;
    }

    // ---- Channel Finder -------------------------------------------------------------------------
    public static final class Radio {
        public final String deviceId, node, radioId, band; public final int channel;
        Radio(String deviceId, String node, String radioId, String band, int channel) { this.deviceId = deviceId; this.node = node; this.radioId = radioId; this.band = band; this.channel = channel; }
        public String key() { return deviceId.toUpperCase(Locale.ROOT) + "|" + radioId; }
        /** "5 GHz · high band" when the firmware names the band, "5 GHz · radio 2" for a second radio, otherwise the band. */
        public String label() {
            String named = Topology.radioLabel(radioId);
            if (named.contains("band")) return named;
            String b = band.isEmpty() ? named : band.replace("GHz", " GHz").replace("  ", " ");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("_(\\d+)$").matcher(radioId);
            return m.find() ? b + " · radio " + m.group(1) : b;
        }
        /** 5 GHz channels 52–144 require radar detection (DFS) and may be vacated suddenly. */
        public boolean dfs() { return channel >= 52 && channel <= 144 && !band.contains("2.4") && !band.toUpperCase(Locale.ROOT).contains("6G"); }
    }
    public static final class Channels {
        public final boolean running; public final List<Radio> radios;
        Channels(boolean running, List<Radio> radios) { this.running = running; this.radios = Collections.unmodifiableList(radios); }
    }
    public static Channels channels(JSONObject response, Topology topology) {
        JSONObject o = output(response);
        List<Radio> radios = new ArrayList<>();
        for (JSONObject node : Topology.entries(o, "selectedChannels")) {
            String id = node.optString("deviceID"), name = id;
            for (Topology.Node n : topology.nodes) if (n.id.equalsIgnoreCase(id)) name = n.name;
            for (JSONObject r : Topology.entries(node, "channels"))
                radios.add(new Radio(id, name, r.optString("radioID"), r.optString("band"), r.optInt("channel", 0)));
        }
        radios.sort(Comparator.comparing((Radio r) -> r.node).thenComparing(r -> r.band).thenComparing(r -> r.radioId));
        return new Channels(o.optBoolean("isRunning"), radios);
    }
    /** Previous channel for the same node radio, or null if it was not reported before. */
    public static Integer previous(Channels before, Radio now) {
        if (before == null) return null;
        for (Radio r : before.radios) if (r.key().equals(now.key())) return r.channel;
        return null;
    }

    // ---- Speed Check -----------------------------------------------------------------------------
    public static final class Speed {
        public final String state, server, timestamp; public final long downKbps, upKbps, latencyMs; public final boolean running;
        Speed(String state, String server, String timestamp, long down, long up, long latency, boolean running) { this.state = state; this.server = server; this.timestamp = timestamp; downKbps = down; upKbps = up; latencyMs = latency; this.running = running; }
        public String down() { return mbps(downKbps); }
        public String up() { return mbps(upKbps); }
        public boolean succeeded() { return "Success".equals(state); }
    }
    /** The firmware stores the Ookla client's integer speeds; shown as Mbps assuming kbit/s. */
    static String mbps(long kbps) { return kbps <= 0 ? "—" : String.format(Locale.ROOT, "%.1f Mbps", kbps / 1000.0); }
    private static Speed speed(JSONObject r, String timestamp, boolean running) {
        String state = SettingsRepository.safeCode(r.optString("exitCode", "Unavailable"));
        String server = r.optString("serverID").matches("[A-Za-z0-9._-]{1,40}") ? r.optString("serverID") : "";
        return new Speed(state, server, timestamp, Math.max(0, r.optLong("downloadBandwidth")), Math.max(0, r.optLong("uploadBandwidth")), Math.max(0, r.optLong("latency")), running);
    }
    /** Current run, or null when no test is in progress or pending collection. */
    public static Speed speedStatus(JSONObject response) {
        JSONObject o = output(response);
        JSONObject r = o.optJSONObject("speedTestResult");
        if (r == null) return null;
        return speed(r, "", "Unavailable".equals(r.optString("exitCode", "Unavailable")));
    }
    public static List<Speed> speedHistory(JSONObject response) {
        List<Speed> out = new ArrayList<>();
        for (JSONObject h : Topology.entries(output(response), "healthCheckResults")) {
            JSONObject r = h.optJSONObject("speedTestResult");
            if (r != null) out.add(speed(r, h.optString("timestamp").matches("[0-9T:.Z+-]{1,40}") ? h.optString("timestamp") : "", false));
        }
        out.sort((a, b) -> b.timestamp.compareTo(a.timestamp)); // ISO-8601 UTC: newest first
        return out;
    }
    public static String describe(String state) {
        switch (state) {
            case "Success": return "Completed";
            case "Unavailable": return "Running…";
            case "SpeedTestLicenseError": return "The router's Speedtest licence was rejected";
            case "SpeedTestExecutionError": return "The test could not run (no internet, or the test server was unreachable)";
            case "DBError": return "The router could not store the result";
            default: return state;
        }
    }

    // ---- Traceroute / WPS / logs -----------------------------------------------------------------
    public static final class Trace { public final boolean running; public final String log; Trace(boolean running, String log) { this.running = running; this.log = log; } }
    public static Trace trace(JSONObject response) {
        JSONObject o = output(response);
        Object log = o.opt("tracerouteLog");
        StringBuilder b = new StringBuilder();
        if (log instanceof JSONArray) { JSONArray a = (JSONArray) log; for (int i = 0; i < a.length() && i < 60; i++) b.append(printable(a.optString(i))).append('\n'); }
        else if (log != null && log != JSONObject.NULL) { int n = 0; for (String line : String.valueOf(log).split("\n")) { if (n++ >= 60) break; b.append(printable(line)).append('\n'); } }
        return new Trace(o.optBoolean("isRunning"), b.toString().trim());
    }
    public static final class Wps { public final boolean inProgress; public final String result; Wps(boolean inProgress, String result) { this.inProgress = inProgress; this.result = result; } }
    public static Wps wps(JSONObject response) {
        JSONObject o = output(response);
        String result = o.optString("lastResult");
        return new Wps(o.optBoolean("isWPSSessionInProgress"), result.matches("Succeeded|Failed") ? result : "");
    }
    public static List<String> logEntries(JSONObject response) {
        List<String> out = new ArrayList<>();
        for (JSONObject e : Topology.entries(output(response), "entries")) {
            List<String> parts = new ArrayList<>();
            for (String k : Json.keys(e)) { Object v = e.opt(k); if (v != null && v != JSONObject.NULL && !(v instanceof JSONObject) && !(v instanceof JSONArray)) parts.add(Json.label(k) + ": " + printable(String.valueOf(v))); }
            if (!parts.isEmpty()) out.add(String.join(" · ", parts));
            if (out.size() >= 200) break;
        }
        return out;
    }
    public static boolean validHost(String value) { return value.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}"); }
    static String printable(String s) { String t = s.replaceAll("[^\\x20-\\x7E]", " ").trim(); return t.length() > 200 ? t.substring(0, 200) + "…" : t; }

    /** Demo channel report for the demo mesh. */
    public static JSONObject demoChannels(Topology t, boolean optimized) {
        try {
            JSONArray nodes = new JSONArray();
            for (int i = 0; i < t.nodes.size(); i++) {
                JSONArray radios = new JSONArray()
                    .put(new JSONObject().put("radioID", "RADIO_2.4GHz").put("band", "2.4GHz").put("channel", optimized ? (i % 2 == 0 ? 1 : 6) : 6))
                    .put(new JSONObject().put("radioID", "RADIO_5GHz").put("band", "5GHz").put("channel", optimized ? 44 : 100))
                    .put(new JSONObject().put("radioID", "RADIO_5GHz_2").put("band", "5GHz").put("channel", 149));
                nodes.put(new JSONObject().put("deviceID", t.nodes.get(i).id).put("channels", radios));
            }
            return new JSONObject().put("result", "OK").put("output", new JSONObject().put("isRunning", false).put("selectedChannels", nodes));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private NetworkTools() {}
}
