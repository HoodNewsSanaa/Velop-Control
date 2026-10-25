package com.velopcontrol.app;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReadPolicyTest {
    @Test public void acceptsOnlyPrivateIPv4() {
        for(String ip:new String[]{"192.168.1.10","10.1.2.3","172.16.0.1","172.31.255.254"})
            assertEquals(ip,ReadPolicy.privateAddress(ip));
        for(String ip:new String[]{"8.8.8.8","127.0.0.1","172.32.0.1","192.168.1.999","192.168.01.1","router.local","192.168.1.1/path","192.168.1.1@evil.com"}) {
            try { ReadPolicy.privateAddress(ip); fail(ip); } catch(IllegalArgumentException expected) {}
        }
    }
    @Test public void rejectsWritesAndActionSpoofing() {
        for(String action:ReadPolicy.ACTIONS) ReadPolicy.requireAllowed(action);
        for(String action:new String[]{"core/Reboot","core/Transaction","wirelessap/SetRadioSettings","core/GetDeviceInfo/../Reboot","http://linksys.com/jnap/core/GetDeviceInfo"}) {
            try { ReadPolicy.requireAllowed(action); fail(action); } catch(IllegalArgumentException expected) {}
        }
    }
}
