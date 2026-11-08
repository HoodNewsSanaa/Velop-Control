package com.velopcontrol.app;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import static org.junit.Assert.*;

public class PrimaryLocatorTest {
    private static final String PIN="AA:BB", SERIAL="ABC123";
    /** Fake LAN: host → {fingerprint, serial}. Records every host probed and whether a serial was requested. */
    private static final class Lan implements PrimaryLocator.Probe {
        final Map<String,String[]> hosts=new HashMap<>();final Set<String> probed=ConcurrentHashMap.newKeySet(),serialRequests=ConcurrentHashMap.newKeySet();
        Lan add(String host,String pin,String serial){hosts.put(host,new String[]{pin,serial});return this;}
        public String fingerprint(String host,int timeout) throws Exception {probed.add(host);String[] h=hosts.get(host);if(h==null)throw new java.net.SocketTimeoutException();return h[0];}
        public String serial(String host,String pin){serialRequests.add(host);return hosts.get(host)[1];}
    }
    private static PrimaryLocator.Remembered remembered(String host){return new PrimaryLocator.Remembered(host,PIN,SERIAL);}
    private static String locate(Lan lan,String last,PrimaryLocator.LocalNetwork net) throws Exception {
        return PrimaryLocator.locate(lan,remembered(last),PrimaryLocator.plan(last,net),8,10000,new AtomicBoolean());
    }

    @Test public void planChecksLastAddressThenGatewayThenSubnet(){
        PrimaryLocator.Plan plan=PrimaryLocator.plan("192.168.1.20",new PrimaryLocator.LocalNetwork("192.168.1.1","192.168.1.50",24));
        assertEquals(Arrays.asList("192.168.1.20","192.168.1.1"),plan.priority);
        assertEquals(251,plan.scan.size());
        assertFalse(plan.scan.contains("192.168.1.50"));assertFalse(plan.scan.contains("192.168.1.0"));assertFalse(plan.scan.contains("192.168.1.255"));assertFalse(plan.scan.contains("192.168.1.1"));
    }
    @Test public void largeSubnetsAreNarrowedAndPublicNetworksAreNeverScanned(){
        assertEquals(253,PrimaryLocator.plan("",new PrimaryLocator.LocalNetwork("10.0.0.1","10.0.7.9",16)).scan.size());
        assertEquals(1021,PrimaryLocator.plan("",new PrimaryLocator.LocalNetwork("","10.0.4.9",22)).scan.size());
        PrimaryLocator.Plan open=PrimaryLocator.plan("8.8.8.8",new PrimaryLocator.LocalNetwork("100.64.0.1","100.64.3.2",24));
        assertTrue(open.priority.isEmpty());assertTrue(open.scan.isEmpty());
        assertTrue(PrimaryLocator.plan("",new PrimaryLocator.LocalNetwork("","192.168.1.5",32)).scan.isEmpty());
    }
    @Test public void knownAddressIsConfirmedWithoutScanning() throws Exception {
        Lan lan=new Lan().add("192.168.1.20",PIN,SERIAL).add("192.168.1.30",PIN,SERIAL);
        assertEquals("192.168.1.20",locate(lan,"192.168.1.20",new PrimaryLocator.LocalNetwork("192.168.1.1","192.168.1.50",24)));
        assertEquals(Collections.singleton("192.168.1.20"),lan.probed);
    }
    @Test public void movedPrimaryIsFoundByCertificateAndSerial() throws Exception {
        Lan lan=new Lan().add("192.168.1.1","OPENWRT",null).add("192.168.1.77",PIN,SERIAL);
        assertEquals("192.168.1.77",locate(lan,"192.168.1.20",new PrimaryLocator.LocalNetwork("192.168.1.1","192.168.1.50",24)));
        assertFalse("Hosts with other certificates never receive an identity request",lan.serialRequests.contains("192.168.1.1"));
    }
    @Test public void sharedCertificateOnAnotherNodeDoesNotMatch() throws Exception {
        Lan lan=new Lan().add("192.168.1.30",PIN,"CHILD-NODE");
        assertNull(locate(lan,"192.168.1.20",new PrimaryLocator.LocalNetwork("","192.168.1.50",24)));
        assertTrue(lan.serialRequests.contains("192.168.1.30"));
    }
    @Test public void missingIdentityOrCancellationFindsNothing() throws Exception {
        Lan lan=new Lan().add("192.168.1.20",PIN,SERIAL);
        PrimaryLocator.Plan plan=PrimaryLocator.plan("192.168.1.20",PrimaryLocator.LocalNetwork.NONE);
        assertNull(PrimaryLocator.locate(lan,new PrimaryLocator.Remembered("192.168.1.20",PIN,""),plan,4,1000,new AtomicBoolean()));
        assertNull(PrimaryLocator.locate(lan,remembered("192.168.1.20"),plan,4,1000,new AtomicBoolean(true)));
        assertTrue(lan.probed.isEmpty());
    }
    @Test public void fingerprintRoundTrips() throws Exception {
        byte[] bytes=new byte[32];for(int i=0;i<32;i++)bytes[i]=(byte)(i*7);
        StringBuilder b=new StringBuilder();for(byte v:bytes){if(b.length()>0)b.append(':');b.append(String.format("%02X",v&255));}
        assertArrayEquals(bytes,JnapClient.Identity.parse(b.toString()));
        try{JnapClient.Identity.parse("AA:BB");fail();}catch(IllegalArgumentException expected){}
    }
}
