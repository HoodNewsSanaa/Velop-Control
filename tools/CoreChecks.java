import com.velopcontrol.app.*;
import org.json.JSONObject;
import java.util.*;

/** Host smoke checks. Compile with ReadPolicy, Topology and upstream org.json source. */
public class CoreChecks {
    static void check(boolean value, String label) { if(!value) throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        for(String action:new String[]{"core/Reboot","core/Transaction","wirelessap/SetRadioSettings","nodes/diagnostics/RefreshBackhaulInfo"}) {
            boolean blocked=false;
            try { ReadPolicy.requireAllowed(action); } catch(IllegalArgumentException expected) {blocked=true;}
            check(blocked,"Write blocked: "+action);
        }
        for(String address:new String[]{"8.8.8.8","127.0.0.1","172.32.1.1","192.168.01.1","192.168.1.1@other"}) {
            boolean blocked=false;
            try { ReadPolicy.privateAddress(address); } catch(IllegalArgumentException expected) {blocked=true;}
            check(blocked,"Unsafe address blocked");
        }
        check(ReadPolicy.privateAddress("192.168.1.10").equals("192.168.1.10"),"LAN seed accepted");
        Map<String,JSONObject> raw=new HashMap<>();
        raw.put(ReadPolicy.DEVICES,new JSONObject("{\"result\":\"OK\",\"output\":{\"devices\":[{\"deviceID\":\"A\",\"isAuthority\":true,\"connections\":[{\"ipAddress\":\"192.168.1.10\"}]},{\"deviceID\":\"pc\",\"connections\":[{\"ipAddress\":\"192.168.1.50\"}]}]}}"));
        raw.put(ReadPolicy.BACKHAUL,new JSONObject("{\"result\":\"OK\",\"output\":{\"backhaulDevices\":[{\"deviceUUID\":\"B\",\"parentIPAddress\":\"192.168.1.10\",\"connectionType\":\"Wired\"}]}}"));
        Topology t=Topology.parse(raw);
        check(t.nodes.size()==2,"Backhaul-only node retained");
        check("A".equals(t.nodes.get(1).parentId),"Parent IP correlated");
        check(t.clients.get(0).nodeId.isEmpty(),"Unknown AP stays unknown");
        check(t.nodes.get(0).clients==0,"No invented primary clients");
        raw.put(ReadPolicy.BACKHAUL,new JSONObject("{\"result\":\"ErrorUnsupportedMode\"}"));
        t=Topology.parse(raw);
        check(t.nodes.size()==1,"Partial capability failure handled");
        check(t.capabilities.get(ReadPolicy.BACKHAUL).equals("ErrorUnsupportedMode"),"Capability error retained");
        check(Topology.demo().demo && Topology.demo().nodes.size()==5,"Explicit five-node demo");
        System.out.println("Core checks passed: write restrictions, LAN validation, parent mapping, unknown attribution, partial results and demo.");
    }
}
