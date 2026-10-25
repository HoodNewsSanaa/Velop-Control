package com.velopcontrol.app;

import org.json.*;
import java.util.*;

/** Stock primary-node auto-onboarding. No firmware changes or child credentials. */
public final class NodeOnboarding {
    public static final String PREFIX="nodes/autoonboarding/";
    public static final long WINDOW_MS=300_000;
    public enum Kind { WIRED, WIRELESS }
    public interface Transport { JSONObject call(String action,JSONObject body) throws Exception; }
    public interface Journal { void save(String value) throws Exception; }
    public static final class Snapshot {
        public final Kind kind;public final boolean enabled;public final Set<String> known;public final String serial;
        Snapshot(Kind kind,boolean enabled,Set<String> known,String serial){this.kind=kind;this.enabled=enabled;this.known=Collections.unmodifiableSet(known);this.serial=serial;}
    }
    public static final class Progress {
        public final List<String> added;public final String radio;
        Progress(List<String> added,String radio){this.added=added;this.radio=radio;}
    }
    private final Transport transport;private final Journal journal;private final Snapshot before;
    private boolean attempted,finished;
    public NodeOnboarding(Transport transport,Journal journal,Snapshot before){this.transport=transport;this.journal=journal;this.before=before;}
    public static String getter(Kind kind){return PREFIX+"Get"+(kind==Kind.WIRED?"Wired":"Bluetooth")+"AutoOnboardingSettings";}
    public static String setter(Kind kind){return PREFIX+"Set"+(kind==Kind.WIRED?"Wired":"Bluetooth")+"AutoOnboardingSettings";}
    public static void validate(String action,JSONObject body) throws Exception {
        if(body==null)throw new IllegalArgumentException("Missing onboarding request");
        if(action.equals(setter(Kind.WIRED))||action.equals(setter(Kind.WIRELESS))){
            if(body.length()!=1||!(body.opt("isAutoOnboardingEnabled") instanceof Boolean))throw new IllegalArgumentException("Invalid onboarding setting");
        }else if(Arrays.asList(ReadPolicy.INFO,ReadPolicy.MODE,ReadPolicy.DEVICES,getter(Kind.WIRED),getter(Kind.WIRELESS),PREFIX+"StartBluetoothAutoOnboarding",PREFIX+"GetBluetoothAutoOnboardingStatus").contains(action)){
            if(body.length()!=0)throw new IllegalArgumentException("Unexpected onboarding fields");
        }else throw new IllegalArgumentException("Onboarding action blocked");
    }
    private static JSONObject output(Transport t,String action,JSONObject body) throws Exception {
        JSONObject r=t.call(action,body);Authentication.require(r);JSONObject o=r.optJSONObject("output");return o==null?new JSONObject():o;
    }
    private static JSONObject read(Transport t,String action) throws Exception{return output(t,action,new JSONObject());}
    public static Snapshot inspect(Transport t,Kind kind) throws Exception {
        if(!"Master".equals(read(t,ReadPolicy.MODE).optString("mode")))throw new IllegalStateException("Connect to the primary mesh node to add nodes. Bridge mode is supported when this node is the mesh coordinator.");
        JSONObject identity=read(t,ReadPolicy.INFO);String serial=identity.optString("serialNumber");if(serial.isEmpty())throw new IllegalStateException("Primary identity is unavailable. Nothing was changed.");
        JSONArray services=identity.optJSONArray("services");boolean supported=false;
        for(int i=0;services!=null&&i<services.length();i++)if(("http://linksys.com/jnap/"+PREFIX+"AutoOnboarding").equals(services.optString(i)))supported=true;
        if(!supported)throw new IllegalStateException("This primary does not advertise the stock auto-onboarding service.");
        JSONObject settings=read(t,getter(kind));
        if(!(settings.opt("isAutoOnboardingEnabled") instanceof Boolean))throw new IllegalStateException("The router did not report its onboarding setting. Nothing was changed.");
        Set<String> known=new HashSet<>();for(JSONObject d:inventory(t))if(("Slave".equals(d.optString("nodeType"))||"Master".equals(d.optString("nodeType"))||d.optBoolean("isAuthority"))&&!d.optString("deviceID").isEmpty())known.add(d.optString("deviceID").toUpperCase(Locale.ROOT));
        return new Snapshot(kind,settings.getBoolean("isAutoOnboardingEnabled"),known,serial);
    }
    private static List<JSONObject> inventory(Transport t) throws Exception {
        JSONObject o=read(t,ReadPolicy.DEVICES);if(!(o.opt("devices") instanceof JSONArray))throw new IllegalStateException("Mesh inventory was unavailable. No membership result can be confirmed.");
        return Topology.entries(o,"devices");
    }
    public void start() throws Exception {
        if(attempted)throw new IllegalStateException("This setup request was already sent or attempted. Check status instead of repeating it.");
        Snapshot fresh=inspect(transport,before.kind);
        if(!fresh.serial.equals(before.serial)||fresh.enabled!=before.enabled||!fresh.known.equals(before.known))throw new IllegalStateException("Mesh or onboarding settings changed. Review setup again before starting.");
        // Persist recovery information before the first possible side effect.
        journal.save(new JSONObject().put("kind",before.kind.name()).put("restoreDisabled",!before.enabled).put("serial",before.serial).toString());attempted=true;
        if(!before.enabled)output(transport,setter(before.kind),new JSONObject().put("isAutoOnboardingEnabled",true));
        if(before.kind==Kind.WIRELESS)output(transport,PREFIX+"StartBluetoothAutoOnboarding",new JSONObject());
    }
    public Progress poll() throws Exception {
        if(!attempted||finished)throw new IllegalStateException("No active setup window");
        List<String> added=new ArrayList<>();
        for(JSONObject d:inventory(transport)){
            String id=d.optString("deviceID").toUpperCase(Locale.ROOT);
            if(!id.isEmpty()&&!before.known.contains(id)&&"Slave".equals(d.optString("nodeType"))&&!Topology.entries(d,"connections").isEmpty())added.add(Topology.name(d));
        }
        String radio="";
        if(before.kind==Kind.WIRELESS){
            try{String s=read(transport,PREFIX+"GetBluetoothAutoOnboardingStatus").optString("autoOnboardingStatus");radio=s.matches("[A-Za-z0-9 _-]{1,60}")?s:"Status unavailable";}
            catch(SecurityException e){throw e;}catch(Exception e){radio="Status unavailable";}
        }
        return new Progress(Collections.unmodifiableList(added),radio);
    }
    /** Explicit cleanup is repeatable, but never restarts setup or removes joined nodes. */
    public void stop() throws Exception {
        if(!attempted||finished)return;
        recover(transport,journal,new JSONObject().put("kind",before.kind.name()).put("restoreDisabled",!before.enabled).put("serial",before.serial).toString());finished=true;
    }
    public static void recover(Transport t,Journal journal,String record) throws Exception {
        JSONObject p=new JSONObject(record);Kind kind=Kind.valueOf(p.getString("kind"));
        if(!(p.opt("restoreDisabled") instanceof Boolean))throw new IllegalArgumentException("Invalid setup recovery record");
        if(!p.getString("serial").equals(read(t,ReadPolicy.INFO).optString("serialNumber")))throw new IllegalStateException("This is a different primary. Setup cleanup was not sent.");
        if(!"Master".equals(read(t,ReadPolicy.MODE).optString("mode")))throw new IllegalStateException("Reconnect to the original primary to finish setup cleanup.");
        if(p.getBoolean("restoreDisabled")){
            JSONObject current=read(t,getter(kind));
            if(!(current.opt("isAutoOnboardingEnabled") instanceof Boolean))throw new IllegalStateException("Cannot verify onboarding settings. Cleanup is still pending.");
            if(current.getBoolean("isAutoOnboardingEnabled"))output(t,setter(kind),new JSONObject().put("isAutoOnboardingEnabled",false));
            if(!Boolean.FALSE.equals(read(t,getter(kind)).opt("isAutoOnboardingEnabled")))throw new IllegalStateException("Onboarding disable could not be verified. Cleanup is still pending.");
        }
        journal.save("");
    }
    public boolean attempted(){return attempted;}
    public boolean finished(){return finished;}
    public Kind kind(){return before.kind;}
    public boolean wasEnabled(){return before.enabled;}
}
