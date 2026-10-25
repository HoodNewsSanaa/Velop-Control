package com.velopcontrol.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RestartMonitorTest {
    private RestartMonitor monitor(long before){return new RestartMonitor("192.168.1.20","Bedroom","node-serial",before,1000,true);}
    @Test public void acceptedReplyDoesNotClaimSuccessfulReboot(){RestartMonitor m=monitor(900);assertFalse(m.finished());assertFalse(m.verified());assertFalse(m.reachable("node-serial",6000));assertFalse(m.needsFinalRead(6000));}
    @Test public void offlineAndNewBootTimeConfirmRecovery(){RestartMonitor m=monitor(900);m.unavailable();assertTrue(m.reachable("node-serial",31000));m.complete(12,31000);assertTrue(m.finished());assertTrue(m.verified());}
    @Test public void briefNetworkLossIsNotProofOfReboot(){RestartMonitor m=monitor(900);m.unavailable();assertTrue(m.reachable("node-serial",31000));m.complete(930,31000);assertFalse(m.verified());assertTrue(m.message().contains("could not be confirmed"));}
    @Test public void missedOfflineWindowCanBeVerifiedByBootTime(){RestartMonitor m=monitor(900);assertTrue(m.reachable("node-serial",121000));m.complete(105,121000);assertTrue(m.verified());}
    @Test public void unchangedBootTimeAtDeadlineIsNotSuccess(){RestartMonitor m=monitor(900);assertTrue(m.reachable("node-serial",121000));m.complete(1020,121000);assertFalse(m.verified());assertTrue(m.finished());}
    @Test public void aDifferentNodeAtSameIpStopsMonitoring(){RestartMonitor m=monitor(900);m.unavailable();assertFalse(m.reachable("different-node",31000));assertTrue(m.finished());assertFalse(m.verified());}
    @Test public void timeoutAndCancellationNeverBecomeSuccess(){RestartMonitor m=monitor(900);m.expire();m.complete(1,121000);assertFalse(m.verified());RestartMonitor stopped=monitor(900);stopped.stop("Stopped");stopped.unavailable();assertEquals("Stopped",stopped.message());}
    @Test public void unavailableUptimeIsNotInvented() throws Exception {assertEquals(-1,RestartMonitor.uptime(new JSONObject("{\"result\":\"OK\",\"output\":{\"uptimeSeconds\":\"12\"}}")));assertEquals(12,RestartMonitor.uptime(new JSONObject("{\"result\":\"OK\",\"output\":{\"uptimeSeconds\":12}}")));RestartMonitor m=monitor(-1);m.unavailable();m.reachable("node-serial",31000);m.complete(12,31000);assertFalse(m.verified());}
    @Test public void lostWriteReplyRemainsUncertainUntilObserved(){RestartMonitor m=new RestartMonitor("192.168.1.20","Bedroom","node-serial",900,1000,false);assertFalse(m.accepted);assertTrue(m.message().contains("without resending"));assertFalse(m.verified());}
}
