package com.velopcontrol.app;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import static org.junit.Assert.*;

public class PrimaryLocatorTest {
    private static final String PIN="AA:BB", SERIAL="ABC123", LINKSYS="CN=linksyssmartwifi.com,OU=Belkin International\\, Inc.,O=Belkin International\\, Inc.,ST=California,C=US";
    /** Fake LAN: host → {fingerprint, serial}. Records every host probed and whether a serial was requested. */
    private static final class Lan implements PrimaryLocator.Probe {
        final Map<String,String[]> hosts=new HashMap<>();final Set<String> probed=ConcurrentHashMap.newKeySet(),serialRequests=ConcurrentHashMap.newKeySet();
        Lan add(String host,String pin,String serial){return add(host,pin,serial,LINKSYS);}
        Lan add(String host,String pin,String serial,String subject){hosts.put(host,new String[]{pin,serial,subject});return this;}
        public PrimaryLocator.Certificate certificate(String host,int timeout) throws Exception {probed.add(host);String[] h=hosts.get(host);if(h==null)throw new java.net.SocketTimeoutException();return new PrimaryLocator.Certificate(h[0],h[2]);}
        public String serial(String host,String pin){serialRequests.add(host);assertEquals("Identity reads are pinned to the presented certificate",hosts.get(host)[0],pin);return hosts.get(host)[1];}
    }
    private static PrimaryLocator.Remembered remembered(String host){return new PrimaryLocator.Remembered(host,PIN,SERIAL);}
    private static PrimaryLocator.Match match(Lan lan,String last,PrimaryLocator.LocalNetwork net) throws Exception {
        return PrimaryLocator.locate(lan,remembered(last),PrimaryLocator.plan(last,net),8,10000,new AtomicBoolean());
    }
    private static String locate(Lan lan,String last,PrimaryLocator.LocalNetwork net) throws Exception {
        PrimaryLocator.Match m=match(lan,last,net);return m==null?null:m.host;
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
        Lan lan=new Lan().add("192.168.1.1","OPENWRT",null,"CN=OpenWrt").add("192.168.1.77",PIN,SERIAL);
        PrimaryLocator.Match m=match(lan,"192.168.1.20",new PrimaryLocator.LocalNetwork("192.168.1.1","192.168.1.50",24));
        assertEquals("192.168.1.77",m.host);assertFalse(m.certificateChanged);
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
    @Test public void firmwareUpdatedCertificateIsFoundBySerialAndFlagged() throws Exception {
        Lan lan=new Lan().add("192.168.1.30","NEW-BUILD","CHILD-NODE").add("192.168.1.77","NEW-BUILD",SERIAL);
        PrimaryLocator.Match m=match(lan,"192.168.1.20",new PrimaryLocator.LocalNetwork("","192.168.1.50",24));
        assertEquals("192.168.1.77",m.host);assertTrue(m.certificateChanged);assertEquals("NEW-BUILD",m.fingerprint);
    }
    @Test public void nonLinksysCertificatesNeverReceiveIdentityReads() throws Exception {
        Lan lan=new Lan().add("192.168.1.9","PRINTER",SERIAL,"CN=printer.local").add("192.168.1.10","NAS",SERIAL,"CN=evil,CN=linksyssmartwifi.com\\,x");
        assertNull(locate(lan,"192.168.1.20",new PrimaryLocator.LocalNetwork("","192.168.1.50",24)));
        assertTrue(lan.serialRequests.isEmpty());
    }
    @Test public void linksysSubjectAndTrustTextAreExplicit(){
        assertTrue(PrimaryLocator.linksys(LINKSYS));assertFalse(PrimaryLocator.linksys("O=CN=linksyssmartwifi.com"));assertFalse(PrimaryLocator.linksys(null));
        String changed=PrimaryLocator.trustNotice(true,true,true);
        assertTrue(changed.contains("firmware updates replace this certificate"));assertTrue(changed.contains("same serial number"));assertTrue(changed.contains("cannot prove which device answered"));
        assertTrue(PrimaryLocator.trustNotice(true,true,false).contains("DIFFERENT serial number"));
        String first=PrimaryLocator.trustNotice(false,false,null);assertFalse(first.contains("firmware updates"));assertTrue(first.contains("Credentials have not been sent"));
    }
    @Test public void fingerprintRoundTrips() throws Exception {
        byte[] bytes=new byte[32];for(int i=0;i<32;i++)bytes[i]=(byte)(i*7);
        StringBuilder b=new StringBuilder();for(byte v:bytes){if(b.length()>0)b.append(':');b.append(String.format("%02X",v&255));}
        assertArrayEquals(bytes,JnapClient.Identity.parse(b.toString()));
        try{JnapClient.Identity.parse("AA:BB");fail();}catch(IllegalArgumentException expected){}
    }
}
