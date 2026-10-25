package com.velopcontrol.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Unknown values stay unknown. Firmware reports are not physical cable verification. */
public final class Topology {
    public final List<Node> nodes = new ArrayList<>();
    public final List<Client> clients = new ArrayList<>();
    public final Map<String,String> capabilities = new LinkedHashMap<>();
    public boolean demo;
    public long observedAt = System.currentTimeMillis();

    public static final class Node {
        public String id, name, ip, parentId, parentIp, link, firmware, model, rssi, rate;
        public boolean primary, seen, wirelessGroupReported;
        public String reportedParentId, radio="", channel="", backhaulTimestamp="", linkRate="", linkRateTimestamp="", linkRateSource="", stationMac="", apMac="";
        public int clients;
    }
    public static final class Client {
        public String name, ip, mac, nodeId, type, rssi, rate, timestamp;
        public boolean seen;
    }

    static JSONObject object(JSONObject parent, String key) {
        JSONObject result = parent.optJSONObject(key);
        return result == null ? new JSONObject() : result;
    }
    static List<JSONObject> entries(JSONObject parent, String key) {
        List<JSONObject> result = new ArrayList<>();
        JSONArray values = parent.optJSONArray(key);
        if (values != null) for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i); if (item != null) result.add(item);
        }
        return result;
    }
    static JSONObject output(Map<String,JSONObject> raw, String action) {
        JSONObject response = raw.get(action);
        return response != null && "OK".equals(response.optString("result")) ? object(response, "output") : new JSONObject();
    }
    static String value(JSONObject obj, String field) { return obj.isNull(field) ? "" : obj.optString(field, ""); }
    static String name(JSONObject device) {
        for (JSONObject property : entries(device, "properties"))
            if ("userDeviceName".equals(value(property, "name")) && !value(property, "value").isEmpty()) return value(property, "value");
        String name = value(device, "friendlyName");
        return name.isEmpty() ? value(device, "deviceID") : name;
    }
    static String first(List<JSONObject> objects, String key) {
        for (JSONObject o : objects) if (!value(o, key).isEmpty()) return value(o, key);
        return "";
    }
    static String mac(String text) { return text.replace("-", ":").toUpperCase(Locale.ROOT); }

    public static Topology parse(Map<String,JSONObject> raw) {
        Topology t = new Topology();
        raw.forEach((a,r) -> t.capabilities.put(a, r.optString("result", "Missing result")));
        List<JSONObject> devices = entries(output(raw, ReadPolicy.DEVICES), "devices");
        Map<String,JSONObject> byId = new LinkedHashMap<>();
        Map<String,JSONObject> backhaul = new HashMap<>();
        for (JSONObject b : entries(output(raw, ReadPolicy.BACKHAUL), "backhaulDevices")) {
            String id = value(b, "deviceUUID"); if (!id.isEmpty()) backhaul.put(id,b);
        }
        for (JSONObject device : devices) {
            String id = value(device, "deviceID");
            if (!id.isEmpty() && (!value(device,"nodeType").isEmpty() || device.optBoolean("isAuthority") || backhaul.containsKey(id)))
                byId.put(id,device);
        }
        for (String id : backhaul.keySet()) if (!byId.containsKey(id)) {
            JSONObject placeholder = new JSONObject();
            try { placeholder.put("deviceID",id); } catch (Exception e) { throw new IllegalStateException(e); }
            byId.put(id, placeholder);
        }
        Map<String,String> nodeByIp = new HashMap<>();
        for (Map.Entry<String,JSONObject> item : byId.entrySet()) {
            Node node = new Node(); JSONObject device = item.getValue();
            JSONObject b = backhaul.getOrDefault(item.getKey(), new JSONObject());
            List<JSONObject> connections = entries(device, "connections");
            node.id = item.getKey(); node.name = name(device);
            node.primary = device.optBoolean("isAuthority");
            node.ip = first(connections, "ipAddress"); if (node.ip.isEmpty()) node.ip = value(b,"ipAddress");
            node.seen = !connections.isEmpty() || backhaul.containsKey(node.id);
            node.parentIp = value(b,"parentIPAddress");
            node.link = value(b,"connectionType");
            node.firmware = value(object(device,"unit"),"firmwareVersion");
            node.model = value(object(device,"model"),"modelNumber");
            JSONObject wireless = object(b,"wirelessConnectionInfo");
            node.rssi = signal(value(wireless,"stationRSSI"));
            if (node.rssi.isEmpty()) node.rssi = signal(value(wireless,"apRSSI"));
            node.rate = positiveRate(value(b,"speedMbps"));
            node.radio=value(wireless,"radioID");node.channel=value(wireless,"channel");if(node.channel.equals("0"))node.channel="";node.backhaulTimestamp=value(b,"timestamp");
            node.stationMac=mac(value(wireless,"stationBSSID"));node.apMac=mac(value(wireless,"apBSSID"));
            if("Wired".equalsIgnoreCase(node.link))node.rate=""; // bh_report hard-codes 1024 for wired links.
            if (!node.ip.isEmpty()) nodeByIp.put(node.ip,node.id);
            t.nodes.add(node);
        }
        JSONObject identity = output(raw, ReadPolicy.INFO);
        for (Node n : t.nodes) {
            String explicitParent=first(entries(byId.get(n.id),"connections"),"parentDeviceID");
            n.parentId = byId.containsKey(explicitParent)?explicitParent:nodeByIp.get(n.parentIp);
            if (n.id.equals(n.parentId)) n.parentId = null;
            n.reportedParentId=n.parentId;
            if (n.primary) {
                if (n.firmware.isEmpty()) n.firmware = value(identity,"firmwareVersion");
                if (n.model.isEmpty()) n.model = value(identity,"modelNumber");
            }
        }
        Map<String,JSONObject> liveByMac = new HashMap<>();
        Map<String,String> association = new HashMap<>();
        Map<String,Map<String,JSONObject>> wirelessByParent=new HashMap<>();
        for (JSONObject c : entries(output(raw, ReadPolicy.CONNECTIONS),"connections")) {
            String m = mac(value(c,"macAddress")); if (!m.isEmpty()) liveByMac.put(m,c);
        }
        for (JSONObject group : entries(output(raw, ReadPolicy.NODE_CONNECTIONS),"nodeWirelessConnections")) {
            for(Node n:t.nodes)if(n.id.equals(value(group,"deviceID")))n.wirelessGroupReported=true;
            for (JSONObject c : entries(group,"connections")) {
                String m = mac(value(c,"macAddress"));
                if (!m.isEmpty()) { liveByMac.put(m,c); association.put(m,value(group,"deviceID"));
                    wirelessByParent.computeIfAbsent(value(group,"deviceID"),k->new HashMap<>()).put(m,c); }
            }
        }
        // Correlate only a backhaul station on its reported parent AP. Never borrow a client rate.
        for(Node n:t.nodes)if("Wireless".equalsIgnoreCase(n.link)&&!n.stationMac.isEmpty()&&!n.apMac.isEmpty()){
            JSONObject peer=wirelessByParent.getOrDefault(n.parentId,Collections.emptyMap()).get(n.stationMac);
            if(peer!=null)setBackhaulRate(n,peer,"Parent AP wireless report");
            Node primary=t.nodes.stream().filter(p->p.primary).findFirst().orElse(null);
            if(n.linkRate.isEmpty()&&primary!=null&&primary.id.equals(n.parentId))for(JSONObject c:entries(output(raw,ReadPolicy.CONNECTIONS),"connections"))setBackhaulRate(n,c,"Primary AP station report");
        }
        for (JSONObject device : devices) {
            if (byId.containsKey(value(device,"deviceID"))) continue;
            Client c = new Client(); c.name = name(device);
            List<JSONObject> connections = entries(device,"connections");
            List<JSONObject> interfaces = new ArrayList<>(connections); interfaces.addAll(entries(device,"knownInterfaces"));
            String inventoryParent=first(connections,"parentDeviceID");
            JSONObject live = null;
            for (JSONObject iface : interfaces) {
                String m = mac(value(iface,"macAddress"));
                if (liveByMac.containsKey(m)) {
                    c.mac=m;
                    if(!inventoryParent.isEmpty()&&association.containsKey(m)&&!inventoryParent.equals(association.get(m)))
                        live=wirelessByParent.getOrDefault(inventoryParent,Collections.emptyMap()).get(m);
                    else live=liveByMac.get(m);
                    if(live!=null)break;
                }
            }
            c.seen = live != null || !connections.isEmpty();
            c.ip = first(connections,"ipAddress");
            if (c.mac == null) c.mac = first(interfaces,"macAddress");
            JSONObject conn = live == null ? (connections.isEmpty() ? new JSONObject() : connections.get(0)) : live;
            if (c.ip.isEmpty()) c.ip = value(conn,"ipAddress");
            c.nodeId = first(connections,"parentDeviceID");
            if(c.nodeId.isEmpty())c.nodeId=value(conn,"parentDeviceID");
            if(c.nodeId.isEmpty())c.nodeId=association.get(mac(c.mac));
            if(c.nodeId==null)c.nodeId="";
            // Never attribute an unassigned device to the primary by default.
            c.type = first(connections,"connectionType");
            if(c.type.isEmpty())c.type=value(conn,"connectionType");
            JSONObject wireless = object(conn,"wireless");
            if (c.type.isEmpty() && wireless.length() > 0) c.type = "Wireless";
            c.rssi = signal(value(wireless,"signalDecibels")); c.rate = positiveRate(value(conn,"negotiatedMbps"));
            c.timestamp=value(conn,"timestamp");
            t.clients.add(c);
            if (c.seen) for (Node n : t.nodes) if (n.id.equals(c.nodeId)) n.clients++;
        }
        t.nodes.sort(Comparator.comparing((Node n) -> !n.primary).thenComparing(n -> n.name));
        return t;
    }

    private static void setBackhaulRate(Node n,JSONObject c,String source){
        if(!mac(value(c,"macAddress")).equals(n.stationMac)||!mac(value(object(c,"wireless"),"bssid")).equals(n.apMac))return;
        String rate=positiveRate(value(c,"negotiatedMbps"));if(rate.isEmpty())return;
        n.linkRate=rate;n.linkRateTimestamp=value(c,"timestamp");n.linkRateSource=source;
    }
    private static String signal(String value){try{double n=Double.parseDouble(value);if(Double.isFinite(n)&&n<0&&n>=-150)return value;}catch(Exception ignored){}return "";}
    private static String positiveRate(String value){try{double n=Double.parseDouble(value);if(Double.isFinite(n)&&n>0&&n<=100000)return value;}catch(Exception ignored){}return "";}
    public static String frequency(String radio){String r=radio==null?"":radio.toUpperCase(Locale.ROOT);if(r.contains("2.4")||r.equals("2G"))return "2.4 GHz";if(r.startsWith("5")||r.startsWith("RADIO_5"))return "5 GHz";if(r.startsWith("6")||r.startsWith("RADIO_6"))return "6 GHz";return "Not reported";}
    public static String radioLabel(String radio){
        String id=radio==null?"":radio.toUpperCase(Locale.ROOT).replace("RADIO_","");
        if(id.equals("5GH"))return "5 GHz · high band";
        if(id.equals("5GL"))return "5 GHz · low band";
        String band=frequency(radio);
        return band.equals("Not reported")?(id.isEmpty()?band:radio):band;
    }
    public static String backhaulRadio(Node node){if(node.primary)return "No mesh uplink (primary)";if("Wired".equalsIgnoreCase(node.link))return "Wired";if(!"Wireless".equalsIgnoreCase(node.link))return "Not reported";return radioLabel(node.radio)+(node.channel.isEmpty()?"":" · channel "+node.channel);}
    public String nodeName(String id) {
        for (Node n : nodes) if (n.id.equals(id)) return n.name;
        return "AP unknown";
    }

    public static Topology demo() {
        Topology t = new Topology(); t.demo = true;
        String[] names = {"A · Primary", "B · Office", "C · Living room", "D · Bedroom", "E · Patio"};
        for (int i=0;i<5;i++) {
            Node n = new Node(); n.id = "demo-"+i; n.name=names[i]; n.ip="192.168.1."+(10+i);
            n.primary=i==0; n.seen=true; n.parentId=i==0?null:"demo-"+(i<3?0:1);
            n.reportedParentId=n.parentId;n.radio=i==2?"5GL":i==4?"5GH":"";n.channel=i==2?"44":i==4?"161":"";
            n.parentIp=i==0?"":"192.168.1."+(i<3?10:11);
            n.link=i==2||i==4?"Wireless":"Wired"; n.model="MX4200";
            n.rssi=i==2?"-57":i==4?"-68":""; n.rate=""; n.firmware="Demo";n.wirelessGroupReported=true;
            t.nodes.add(n);
        }
        String[] clients={"Pixel phone","Work laptop","Living room TV","Kitchen speaker","Android tablet","Game console","Patio camera","Desktop PC","Home server","Bedroom light","Printer","Watch"};
        for(int i=0;i<clients.length;i++){Client c=new Client();c.name=clients[i];c.ip="192.168.1."+(100+i);c.mac=String.format(Locale.ROOT,"02:00:00:00:00:%02X",i);c.nodeId="demo-"+(i%5);c.type=i==1||i==7||i==8?"Wired":"Wireless";c.seen=i!=11;c.rssi=c.type.equals("Wireless")?"-"+(45+i):"";c.rate=c.type.equals("Wireless")?"600":"1000";c.timestamp="";t.clients.add(c);if(c.seen)t.nodes.get(i%5).clients++;}
        t.capabilities.put("Demo", "Synthetic planned topology; no router requests");
        return t;
    }
}
