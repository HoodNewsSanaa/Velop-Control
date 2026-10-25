package com.velopcontrol.app;

import org.json.JSONObject;

/** Pure observation state. This class never sends a reboot or retries a write. */
public final class RestartMonitor {
    public static final long WINDOW_MS = 120_000;
    public final String host, name, serial;
    public final long startedAt, uptimeBefore;
    public final boolean accepted;
    private boolean unreachable, finished, verified;
    private String message;

    public RestartMonitor(String host, String name, String serial, long uptimeBefore, long startedAt, boolean accepted) {
        this.host = host; this.name = name; this.serial = serial;
        this.uptimeBefore = uptimeBefore; this.startedAt = startedAt; this.accepted = accepted;
        message = accepted ? "Request accepted; watching for the node to restart…"
            : "The command reply was lost. It may have reached the node; watching without resending…";
    }

    public static long uptime(JSONObject response) {
        if (response == null || !"OK".equals(response.optString("result"))) return -1;
        JSONObject output = response.optJSONObject("output");
        Object value = output == null ? null : output.opt("uptimeSeconds");
        if (!(value instanceof Number)) return -1;
        double seconds = ((Number) value).doubleValue();
        return Double.isFinite(seconds) && seconds >= 0 && seconds <= Long.MAX_VALUE && seconds == Math.floor(seconds)
            ? ((Number) value).longValue() : -1;
    }

    public void unavailable() {
        if (finished) return;
        unreachable = true;
        message = "Node is unreachable; waiting for it to return…";
    }

    /** True when one final uptime read is useful: after return or at the deadline. */
    public boolean reachable(String reportedSerial, long now) {
        if (finished) return false;
        if (serial.isEmpty() || !serial.equals(reportedSerial)) {
            stop("Node identity could not be matched. Monitoring stopped; reconnect and verify the node.");
            return false;
        }
        if (unreachable || now - startedAt >= WINDOW_MS) return true;
        message = "Node still responds; restart has not been confirmed yet…";
        return false;
    }

    public void complete(long uptimeAfter, long now) {
        if (finished) return;
        long elapsedSeconds = Math.max(0, (now - startedAt) / 1000);
        // Compare boot time rather than just uptime: the old uptime may have been only seconds.
        verified = uptimeBefore >= 0 && uptimeAfter >= 0 &&
            uptimeAfter + 3 < uptimeBefore + elapsedSeconds;
        finished = true;
        if (verified) message = "Restart confirmed by a newer boot time. Node is responding again.";
        else if (unreachable) message = "Node became unreachable and returned. A reboot could not be confirmed from uptime.";
        else message = "Node is responding, but a restart was not confirmed. The command was not repeated.";
    }

    public void expire() { stop("Node did not return within two minutes. Check its power and mesh connection; no command was repeated."); }
    public void stop(String reason) { finished = true; message = reason; }
    public boolean finished() { return finished; }
    public boolean needsFinalRead(long now) { return unreachable || now-startedAt>=WINDOW_MS; }
    public boolean verified() { return verified; }
    public String message() { return message; }
}
