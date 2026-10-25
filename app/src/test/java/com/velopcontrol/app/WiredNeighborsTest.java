package com.velopcontrol.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class WiredNeighborsTest {
    private WiredNeighbors parse(String text)throws Exception{return WiredNeighbors.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));}
    private static final String HEADER="========================== LLDP Information ==========================\n";
    @Test public void onlyLldpPortAndIdentityFieldsSurvive()throws Exception{
        WiredNeighbors report=parse("private-password-and-wifi-report\n"+HEADER+"LLDP neighbors:\nInterface: eth0, via: LLDP, RID: 1\n Chassis:\n  ChassisID: mac aa:bb:cc:dd:ee:ff\n  SysName: private-name-not-retained\n  MgmtIP: 192.168.1.30\n Port:\n  PortID: ifname eth2\n**************** End of Sysinfo Output ******************\n");
        assertTrue(report.sectionFound);assertEquals(1,report.neighbors.size());WiredNeighbors.Neighbor n=report.neighbors.get(0);assertEquals("eth0",n.localInterface);assertEquals("AA:BB:CC:DD:EE:FF",n.chassisMac);assertEquals("192.168.1.30",n.managementIp);assertEquals("ifname eth2",n.remotePort);
    }
    @Test public void missingAndEmptySectionsAreDifferent()throws Exception{assertFalse(parse("<html>Login</html>\n").sectionFound);WiredNeighbors empty=parse(HEADER+"LLDP neighbors:\n");assertTrue(empty.sectionFound);assertTrue(empty.neighbors.isEmpty());}
    @Test public void nonLocalAddressesAndControlCharactersAreDiscarded()throws Exception{WiredNeighbors report=parse(HEADER+"Interface: eth0, via: LLDP\nMgmtIP: 8.8.8.8\nPortID: malicious\u0001text\nChassisID: local router-name\n");WiredNeighbors.Neighbor n=report.neighbors.get(0);assertEquals("",n.managementIp);assertEquals("",n.remotePort);assertEquals("",n.chassisMac);}
    @Test public void unrelatedDiagnosticFieldsAreNotParsed()throws Exception{WiredNeighbors report=parse("Interface: eth9\nMgmtIP: 192.168.1.99\n"+HEADER+"Interface: eth0, via: LLDP\nMgmtIP: 192.168.1.30\n===================== Next section =====================\nInterface: eth1\n");assertEquals(1,report.neighbors.size());assertEquals("eth0",report.neighbors.get(0).localInterface);}
    @Test public void inputAndNeighborCountsAreBounded()throws Exception{String row="Interface: eth0, via: LLDP\n";assertEquals(64,parse(HEADER+row.repeat(100)).neighbors.size());assertThrows(IOException.class,()->parse("x".repeat(2*1024*1024+1)));}
}
