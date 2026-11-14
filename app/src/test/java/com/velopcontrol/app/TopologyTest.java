package com.velopcontrol.app;

import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class TopologyTest {
    private Map<String,JSONObject> fixture(String devices, String backhaul) throws Exception {
        Map<String,JSONObject> r=new HashMap<>();
        r.put(ReadPolicy.DEVICES,new JSONObject("{\"result\":\"OK\",\"output\":{\"devices\":"+devices+"}}"));
        r.put(ReadPolicy.BACKHAUL,new JSONObject("{\"result\":\"OK\",\"output\":{\"backhaulDevices\":"+backhaul+"}}"));
        return r;
    }
    @Test public void unknownParentAndClientAreNotAssignedToPrimary() throws Exception {
        Topology t=Topology.parse(fixture("[{\"deviceID\":\"A\",\"isAuthority\":true},{\"deviceID\":\"B\",\"nodeType\":\"Slave\"},{\"deviceID\":\"pc\",\"connections\":[{\"ipAddress\":\"192.168.1.30\"}]}]","[]"));
        assertNull(t.nodes.get(1).parentId);
        assertEquals("",t.clients.get(0).nodeId);
        assertEquals(0,t.nodes.get(0).clients);
        assertFalse(t.nodes.get(1).seen);
    }
    @Test public void parentIpResolvesToNodeWithoutInventingPhysicalTopology() throws Exception {
        Topology t=Topology.parse(fixture("[{\"deviceID\":\"A\",\"isAuthority\":true,\"connections\":[{\"ipAddress\":\"192.168.1.10\"}]}]",
            "[{\"deviceUUID\":\"B\",\"parentIPAddress\":\"192.168.1.10\",\"connectionType\":\"Wired\"}]"));
        Topology.Node b=t.nodes.stream().filter(n->n.id.equals("B")).findFirst().get();
        assertEquals("A",b.parentId); assertEquals("Wired",b.link);
        assertEquals(2,t.nodes.size());
    }
    @Test public void childWirelessAssociationAndMissingMetrics() throws Exception {
        Map<String,JSONObject> raw=fixture("[{\"deviceID\":\"B\",\"nodeType\":\"Slave\"},{\"deviceID\":\"pc\",\"knownInterfaces\":[{\"macAddress\":\"aa-bb-cc-dd-ee-ff\"}]}]","[]");
        raw.put(ReadPolicy.NODE_CONNECTIONS,new JSONObject("{\"result\":\"OK\",\"output\":{\"nodeWirelessConnections\":[{\"deviceID\":\"B\",\"connections\":[{\"macAddress\":\"AA:BB:CC:DD:EE:FF\",\"wireless\":{\"signalDecibels\":-62}}]}]}}"));
        Topology t=Topology.parse(raw);
        assertEquals("B",t.clients.get(0).nodeId); assertTrue(t.clients.get(0).seen);
        assertEquals("-62",t.clients.get(0).rssi); assertEquals("",t.clients.get(0).rate);
        assertEquals(1,t.nodes.get(0).clients);
    }
    @Test public void partialCapabilityFailureDoesNotInventNodes() throws Exception {
        Map<String,JSONObject> raw=fixture("[]","[]");
        raw.put(ReadPolicy.BACKHAUL,new JSONObject("{\"result\":\"ErrorUnsupportedMode\"}"));
        Topology t=Topology.parse(raw); assertTrue(t.nodes.isEmpty());
        assertEquals("ErrorUnsupportedMode",t.capabilities.get(ReadPolicy.BACKHAUL));
    }
    @Test public void demoIsExplicitAndMatchesPlannedParentStructure() {
        Topology t=Topology.demo(); assertTrue(t.demo); assertEquals(5,t.nodes.size());
        assertEquals("demo-1",t.nodes.get(4).parentId); assertEquals(12,t.clients.size());
        assertEquals(11,t.clients.stream().filter(c->c.seen).count());
    }
    @Test public void explicitAssociationWinsAndMissingGroupStaysUnknown() throws Exception {
        Map<String,JSONObject> raw=fixture("[{\"deviceID\":\"A\",\"isAuthority\":true},{\"deviceID\":\"B\",\"nodeType\":\"Slave\"},{\"deviceID\":\"pc\",\"connections\":[{\"macAddress\":\"AA:BB:CC:DD:EE:FF\",\"parentDeviceID\":\"A\"}]}]","[]");
        raw.put(ReadPolicy.NODE_CONNECTIONS,new JSONObject("{\"result\":\"OK\",\"output\":{\"nodeWirelessConnections\":[{\"deviceID\":\"B\",\"connections\":[{\"macAddress\":\"AA:BB:CC:DD:EE:FF\",\"wireless\":{\"signalDecibels\":35}}]}]}}"));
        Topology t=Topology.parse(raw);assertEquals("A",t.clients.get(0).nodeId);assertEquals("",t.clients.get(0).rssi);
        assertFalse(t.nodes.get(0).wirelessGroupReported);assertTrue(t.nodes.get(1).wirelessGroupReported);
    }
    @Test public void backhaulBandChannelAndPeerRateAreDistinctFromThroughput() throws Exception {
        Map<String,JSONObject> raw=fixture("[{\"deviceID\":\"A\",\"isAuthority\":true,\"connections\":[{\"ipAddress\":\"192.168.1.1\"}]}]","[{\"deviceUUID\":\"B\",\"connectionType\":\"Wireless\",\"parentIPAddress\":\"192.168.1.1\",\"speedMbps\":\"85.409\",\"timestamp\":\"123\",\"wirelessConnectionInfo\":{\"radioID\":\"5GH\",\"channel\":161,\"stationBSSID\":\"AA:BB:CC:DD:EE:FF\",\"apBSSID\":\"02:00:00:00:00:01\"}}]");
        raw.put(ReadPolicy.CONNECTIONS,new JSONObject("{\"result\":\"OK\",\"output\":{\"connections\":[{\"macAddress\":\"AA:BB:CC:DD:EE:FF\",\"negotiatedMbps\":867,\"wireless\":{\"bssid\":\"02:00:00:00:00:01\"}}]}}"));
        Topology t=Topology.parse(raw);Topology.Node b=t.nodes.stream().filter(n->n.id.equals("B")).findFirst().get();assertEquals("867",b.linkRate);assertEquals("85.409",b.rate);assertEquals("5 GHz · high band · channel 161",Topology.backhaulRadio(b));assertEquals("",b.linkRateTimestamp);
        // A peer report from another AP must never be attributed to this backhaul.
        raw.get(ReadPolicy.CONNECTIONS).getJSONObject("output").getJSONArray("connections").getJSONObject(0).getJSONObject("wireless").put("bssid","02:00:00:00:00:09");
        assertEquals("",Topology.parse(raw).nodes.stream().filter(n->n.id.equals("B")).findFirst().get().linkRate);
    }
    @Test public void wired1024IsAPlaceholderAndNotAThroughputMeasurement() throws Exception {
        Topology t=Topology.parse(fixture("[]","[{\"deviceUUID\":\"B\",\"connectionType\":\"Wired\",\"speedMbps\":\"1024\"}]"));assertEquals("",t.nodes.get(0).rate);
    }
    @Test public void radioLabelsRetainUsefulDistinctionWithoutDuplicateGhz(){assertEquals("5 GHz · high band",Topology.radioLabel("5GH"));assertEquals("5 GHz · low band",Topology.radioLabel("5GL"));assertEquals("5 GHz",Topology.radioLabel("RADIO_5GHz"));assertEquals("2.4 GHz",Topology.radioLabel("RADIO_2.4GHz"));}
    @Test public void positiveRssiAndInvalidRatesAreUnknown()throws Exception{Topology.Node n=Topology.parse(fixture("[]","[{\"deviceUUID\":\"B\",\"connectionType\":\"Wireless\",\"speedMbps\":\"NaN\",\"wirelessConnectionInfo\":{\"stationRSSI\":45,\"apRSSI\":0}}]")).nodes.get(0);assertEquals("",n.rssi);assertEquals("",n.rate);}
    @Test public void explicitClientAssociationDoesNotBorrowRateFromAnotherAp()throws Exception{
        Map<String,JSONObject> raw=fixture("[{\"deviceID\":\"A\",\"isAuthority\":true},{\"deviceID\":\"B\",\"nodeType\":\"Slave\"},{\"deviceID\":\"pc\",\"connections\":[{\"macAddress\":\"AA:BB:CC:DD:EE:FF\",\"parentDeviceID\":\"A\"}]}]","[]");
        raw.put(ReadPolicy.NODE_CONNECTIONS,new JSONObject("{\"result\":\"OK\",\"output\":{\"nodeWirelessConnections\":[{\"deviceID\":\"B\",\"connections\":[{\"macAddress\":\"AA:BB:CC:DD:EE:FF\",\"negotiatedMbps\":600,\"wireless\":{\"signalDecibels\":-60}}]}]}}"));
        Topology.Client c=Topology.parse(raw).clients.get(0);assertEquals("A",c.nodeId);assertEquals("",c.rate);assertEquals("",c.rssi);
    }
    @Test public void backhaulReportOutranksInventoryParentAndPrimaryHasNone() throws Exception {
        // Inventory says C was last seen on A; the backhaul report says C uplinks through B.
        Topology t=Topology.parse(fixture("[{\"deviceID\":\"A\",\"isAuthority\":true,\"connections\":[{\"ipAddress\":\"192.168.1.10\",\"parentDeviceID\":\"C\"}]},"
            +"{\"deviceID\":\"B\",\"nodeType\":\"Slave\",\"connections\":[{\"ipAddress\":\"192.168.1.11\",\"parentDeviceID\":\"A\"}]},"
            +"{\"deviceID\":\"C\",\"nodeType\":\"Slave\",\"connections\":[{\"ipAddress\":\"192.168.1.12\",\"parentDeviceID\":\"A\"}]},"
            +"{\"deviceID\":\"D\",\"nodeType\":\"Slave\",\"connections\":[{\"ipAddress\":\"192.168.1.13\",\"parentDeviceID\":\"A\"}]}]",
            "[{\"deviceUUID\":\"B\",\"ipAddress\":\"192.168.1.11\",\"parentIPAddress\":\"192.168.1.10\",\"connectionType\":\"Wired\"},"
            +"{\"deviceUUID\":\"C\",\"ipAddress\":\"192.168.1.12\",\"parentIPAddress\":\"192.168.1.11\",\"connectionType\":\"Wireless\"},"
            +"{\"deviceUUID\":\"D\",\"ipAddress\":\"192.168.1.13\",\"parentIPAddress\":\"0.0.0.0\",\"connectionType\":\"Wireless\"}]"));
        Map<String,Topology.Node> n=new HashMap<>();for(Topology.Node x:t.nodes)n.put(x.id,x);
        assertNull(n.get("A").parentId);
        assertEquals("B",n.get("C").parentId);assertEquals("Backhaul report",n.get("C").parentSource);
        assertEquals("A",n.get("B").parentId);
        assertEquals("Unknown backhaul parent falls back to inventory","A",n.get("D").parentId);assertEquals("Device inventory (fallback)",n.get("D").parentSource);
    }
    @Test public void diagramDrawsChildrenDirectlyUnderTheirParents() throws Exception {
        Topology t=new Topology();String[][] rows={{"P",null,"Primary"},{"A","Z","Attic"},{"B","P","Basement"},{"Z","P","Zebra room"},{"X","missing","Xylophone"},{"L1","L2","Loop one"},{"L2","L1","Loop two"}};
        for(String[] r:rows){Topology.Node x=new Topology.Node();x.id=r[0];x.parentId=r[1];x.name=r[2];x.primary=r[0].equals("P");t.nodes.add(x);}
        t.nodes.sort(Comparator.comparing((Topology.Node x)->!x.primary).thenComparing(x->x.name));
        List<Topology.Node> order=new ArrayList<>();List<Integer> depths=new ArrayList<>(),parents=new ArrayList<>();
        TopologyView.layout(t,order,depths,parents);
        List<String> ids=new ArrayList<>();for(Topology.Node x:order)ids.add(x.id);
        assertEquals(Arrays.asList("P","B","Z","A","X","L1","L2"),ids);
        assertEquals(Arrays.asList(0,1,1,2,0,0,1),depths);
        assertEquals(Arrays.asList(-1,0,0,2,-1,-1,5),parents);
    }
}
