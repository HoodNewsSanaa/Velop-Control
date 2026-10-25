package com.velopcontrol.app;

import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fresh-read checks and one-shot changes. A network timeout is never retried. */
public final class SettingsRepository {
    public interface Transport {
        JSONObject read(String action,boolean auth) throws Exception;
        JSONObject write(String action,JSONObject body) throws Exception;
        String host();
    }
    public static final class Snapshot {
        public final SettingsCatalog.Spec spec; public final JSONObject raw; public final long at;
        final String host;
        Snapshot(SettingsCatalog.Spec spec,JSONObject raw,String host){this.spec=spec;this.raw=Json.copy(raw);this.host=host;at=System.nanoTime();}
        public JSONObject editable(){return spec.project(raw);}
    }
    public static final class Change {
        final Snapshot before; final JSONObject desired;
        final AtomicBoolean used=new AtomicBoolean(false);
        public final String preview;
        private Change(Snapshot snapshot,JSONObject value,String preview){before=snapshot;desired=Json.copy(value);this.preview=preview;}
    }
    public static final class Outcome {
        public final String message; public final boolean verified;
        Outcome(String message,boolean verified){this.message=message;this.verified=verified;}
    }
    private final Transport transport;
    public SettingsRepository(Transport transport){this.transport=transport;}
    public Snapshot load(SettingsCatalog.Spec spec) throws Exception {
        if(spec.read.isEmpty())throw new IllegalArgumentException(spec.note);
        JSONObject response=transport.read(spec.read,true);
        requireOK(response);
        JSONObject output=response.optJSONObject("output");
        if(output==null)throw new IllegalArgumentException("This firmware returned no settings");
        if(spec.id.equals("wan")||spec.id.equals("ipv6")){
            // Type choices come from this node's capabilities, not a global model assumption.
            try{JSONObject status=transport.read("router/GetWANStatus3",true);JSONObject caps=status.optJSONObject("output");
                if("OK".equals(status.optString("result"))&&caps!=null){
                    for(String key:Arrays.asList("supportedWANTypes","supportedIPv6WANTypes","supportedWANCombinations"))if(caps.has(key))output.put(key,caps.opt(key));
                    output.put("currentIPv4WANType",Topology.object(caps,"wanConnection").optString("wanType"));
                }
            }catch(SecurityException e){throw e;}catch(Exception ignored){/* Existing mode remains viewable; mode changes require capabilities. */}
        }
        return new Snapshot(spec,output,transport.host());
    }
    public Change prepare(Snapshot before,JSONObject draft){
        if(!before.spec.editable())throw new IllegalArgumentException("This page is read-only");
        JSONObject desired=before.spec.project(draft);
        normalize(before.spec,desired);
        SettingsValidation.check(before.spec,desired,before.raw);
        JSONObject old=before.editable();normalize(before.spec,old);
        if(Json.same(old,desired))throw new IllegalArgumentException("No changes to apply");
        List<String> lines=new ArrayList<>();
        diff(old,desired,"",lines);
        if(lines.size()>40)lines=new ArrayList<>(lines.subList(0,40));
        return new Change(before,desired,String.join("\n",lines));
    }
    public synchronized Outcome apply(Change change,boolean userConfirmed) throws Exception {
        if(!userConfirmed)throw new SecurityException("Confirm the change first");
        if(!change.before.host.equals(transport.host()))throw new SecurityException("Router session changed");
        if((System.nanoTime()-change.before.at)>180_000_000_000L)throw new IllegalStateException("Settings expired. Reload and review the changes again.");
        if(!change.used.compareAndSet(false,true))throw new IllegalStateException("This change has already been submitted. Reload before trying again.");
        Snapshot fresh=load(change.before.spec);
        JSONObject initial=change.before.editable(),current=fresh.editable();
        normalize(change.before.spec,initial);normalize(change.before.spec,current);
        if(!Json.same(initial,current))throw new IllegalStateException("Settings changed on the router. Reload before applying your edits.");
        SettingsValidation.check(change.before.spec,change.desired,fresh.raw);
        final JSONObject response;
        try{response=transport.write(change.before.spec.write,Json.copy(change.desired));}
        catch(SecurityException e){throw e;}
        catch(Exception e){throw new UncertainChangeException("The request could not be confirmed. It may have reached the router. Reconnect and reload; it will not be retried.");}
        requireOK(response);
        try {
            Snapshot after=load(change.before.spec);
            JSONObject actual=after.editable();normalize(change.before.spec,actual);
            boolean same=Json.same(actual,change.desired);
            return new Outcome(same?"Change applied and read back successfully.":"The router accepted the change. Read-back differs or is incomplete; review the current settings.",same);
        }catch(Exception e){return new Outcome("The router accepted the change. Reconnect and reload to verify it.",false);}
    }
    public static class UncertainChangeException extends Exception {UncertainChangeException(String message){super(message);}}
    public static void requireOK(JSONObject response){
        String r=response.optString("result","MissingResult");
        if(!r.equals("OK"))throw new IllegalArgumentException("Router result: "+safeCode(r));
    }
    public static String safeCode(String result){return result!=null&&result.matches("[_A-Za-z0-9.-]{1,100}")?result:"UnrecognizedResult";}
    private static void normalize(SettingsCatalog.Spec spec,JSONObject value){
        // Empty optional fields are omitted, not sent as empty IP addresses.
        for(JSONObject field:spec.fields)if(field.optString("type").endsWith("optional"))
            for(String path:Json.expand(value,field.optString("path")))if("".equals(Json.get(value,path)))remove(value,path);
        if(spec.id.equals("wan")){
            String type=value.optString("wanType");String keep=type.equals("Static")?"staticSettings":type.equals("PPPoE")?"pppoeSettings":type.equals("PPTP")||type.equals("L2TP")?"tpSettings":type.equals("Bridge")?"bridgeSettings":"";
            for(String k:Arrays.asList("staticSettings","pppoeSettings","tpSettings","bridgeSettings"))if(!k.equals(keep))value.remove(k);
            if(type.equals("Bridge")&&value.optJSONObject("bridgeSettings")==null)Json.set(value,"bridgeSettings.useStaticSettings",false);
        }
        if(spec.id.equals("ipv6")&&!value.optString("wanType").equals("Automatic"))value.remove("ipv6AutomaticSettings");
        if(spec.id.equals("wifi"))for(JSONObject radio:Topology.entries(value,"radios")){
            JSONObject s=radio.optJSONObject("settings");if(s==null)continue;String security=s.optString("security").toLowerCase(Locale.ROOT);
            if(!security.matches(".*(personal|psk).*"))s.remove("wpaPersonalSettings");
            if(!security.contains("enterprise"))s.remove("wpaEnterpriseSettings");
            if(!security.equals("wep"))s.remove("wepSettings");
        }
        prune(value);
    }
    private static void prune(JSONObject object){
        for(String key:new ArrayList<>(Json.keys(object))){
            Object v=object.opt(key);if(v instanceof JSONObject){prune((JSONObject)v);if(((JSONObject)v).length()==0)object.remove(key);}
        }
    }
    public static void remove(JSONObject root,String path){int i=path.lastIndexOf('.');Object parent=i<0?root:Json.get(root,path.substring(0,i));if(parent instanceof JSONObject)((JSONObject)parent).remove(i<0?path:path.substring(i+1));}
    private static void diff(Object old,Object value,String path,List<String> out){
        if(Json.same(old,value))return;
        if(old instanceof JSONObject || value instanceof JSONObject){
            Set<String> keys=new TreeSet<>();if(old instanceof JSONObject)keys.addAll(Json.keys((JSONObject)old));if(value instanceof JSONObject)keys.addAll(Json.keys((JSONObject)value));
            for(String key:keys)diff(old instanceof JSONObject?((JSONObject)old).opt(key):null,value instanceof JSONObject?((JSONObject)value).opt(key):null,path.isEmpty()?key:path+"."+key,out);
        }else if(old instanceof JSONArray || value instanceof JSONArray){
            JSONArray a=old instanceof JSONArray?(JSONArray)old:new JSONArray(),b=value instanceof JSONArray?(JSONArray)value:new JSONArray();
            for(int i=0;i<Math.max(a.length(),b.length());i++)diff(a.opt(i),b.opt(i),path+"."+i,out);
        }else{
            String label=Json.label(path.substring(path.lastIndexOf('.')+1));
            out.add(label+": "+(Json.secret(path)?"[password changed]":display(old)+" → "+display(value)));
        }
    }
    private static String display(Object v){if(v==null||v==JSONObject.NULL)return "not set";String s=String.valueOf(v);return s.length()>80?s.substring(0,80)+"…":s;}
}
