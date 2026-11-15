package com.velopcontrol.app;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class DropMonitorTest {
    private static final String A = "AA:00:00:00:01:11", B = "AA:00:00:00:02:11";
    private static DropMonitor.Sample ok(long t, String bssid) { return new DropMonitor.Sample(t * 1000, true, bssid, -55, 866, 5180, 3, 4, 20, 25); }

    @Test public void csvRoundTripsAndRejectsHeaders() {
        DropMonitor.Sample s = new DropMonitor.Sample(1700000000000L, true, A, -61, 433, 5745, null, 5, -1, null);
        DropMonitor.Sample back = DropMonitor.parse(s.csv());
        assertEquals(s.csv(), back.csv()); assertNull(back.gatewayMs); assertEquals(Integer.valueOf(-1), back.internetMs);
        assertNull(DropMonitor.parse(DropMonitor.HEADER)); assertNull(DropMonitor.parse("# comment")); assertNull(DropMonitor.parse("1,2"));
    }
    @Test public void problemsAreAttributedToTheLowestFailingLayer() {
        assertEquals("Wi-Fi disconnected", new DropMonitor.Sample(0, false, "", null, null, null, null, null, null, null).problem());
        assertEquals("Local network unreachable while on Wi-Fi", new DropMonitor.Sample(0, true, A, -50, 1, 1, -1, -1, -1, -1).problem());
        assertEquals("Local network unreachable while on Wi-Fi", new DropMonitor.Sample(0, true, A, -50, 1, 1, null, -1, -1, -1).problem());
        assertTrue(new DropMonitor.Sample(0, true, A, -50, 1, 1, 3, -1, 20, 20).problem().startsWith("Primary Velop unreachable"));
        assertTrue(new DropMonitor.Sample(0, true, A, -50, 1, 1, -1, 4, 20, 20).problem().startsWith("Main router"));
        assertTrue(new DropMonitor.Sample(0, true, A, -50, 1, 1, 3, 4, -1, -1).problem().startsWith("Internet unreachable"));
        assertTrue(new DropMonitor.Sample(0, true, A, -50, 1, 1, 3, 4, 20, -1).problem().startsWith("DNS"));
        assertNull(ok(0, A).problem());
    }
    @Test public void outagesRoamsAndPausesAreReported() {
        List<DropMonitor.Sample> s = new ArrayList<>(Arrays.asList(ok(0, A), ok(5, A),
            new DropMonitor.Sample(10_000, false, "", null, null, null, null, null, null, null),
            new DropMonitor.Sample(15_000, true, B, -70, 100, 5745, 3, 4, -1, -1),
            ok(20, B), ok(25, A), ok(200, A),
            new DropMonitor.Sample(205_000, true, A, -50, 1, 1, 3, 4, 20, -1)));
        DropMonitor.Report r = DropMonitor.analyse(s, 5000, bssid -> bssid.equals(A) ? "Office" : null);
        assertEquals(2, r.outages); assertEquals(10, r.downSeconds);
        DropMonitor.Event drop = r.events.stream().filter(e -> e.kind.equals("Drop")).findFirst().get();
        assertTrue(drop.detail, drop.detail.startsWith("Wi-Fi disconnected. Also: Internet unreachable"));
        assertTrue(drop.detail, drop.detail.contains("Before: on Office, -55 dBm, 5 GHz")); assertTrue(drop.detail.contains("Back on radio ··02:11 (switched node)"));
        assertTrue(r.events.stream().anyMatch(e -> e.kind.equals("Switched node") && e.detail.startsWith("radio ··02:11 → Office")));
        assertTrue(r.events.stream().anyMatch(e -> e.kind.equals("Monitoring paused") && !e.problem));
        assertTrue(r.events.stream().anyMatch(e -> e.kind.startsWith("Problem (still ongoing")));
        String summary = DropMonitor.summary(r, 5000);
        assertTrue(summary, summary.contains("2 drops") && summary.contains("1× Wi-Fi disconnected") && summary.contains("1× DNS lookups failing"));
        assertEquals("No drops recorded.", DropMonitor.summary(DropMonitor.analyse(Arrays.asList(ok(0, A), ok(5, A)), 5000, null), 5000).replaceAll("^.*\\. ", ""));
    }
}
