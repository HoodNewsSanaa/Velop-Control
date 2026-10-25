package com.velopcontrol.app;

import org.json.*;
import java.io.*;
import java.util.*;

/** Bundled protocol metadata, not a user-editable action console. */
public final class SettingsCatalog {
    public static final List<Spec> ALL=new ArrayList<>();
    private static final Set<String> READS=new HashSet<>(), WRITES=new HashSet<>();
    static {
        Collections.addAll(READS,"nodes/setup/GetSelectedChannels","diagnostics/GetSystemStats","router/GetEthernetPortConnections",
            "router/GetWANSettings","router/GetWANSettings2","diagnostics/GetPingStatus",Authentication.STATUS,Authentication.CHECK,"core/CheckAdminPassword");
        Collections.addAll(WRITES,"core/Reboot","core/SetAdminPassword2","core/SetAdminPassword3","devicelist/SetDeviceProperties",
            "diagnostics/StartPing","nodes/setup/StartAutoChannelSelection");
    }
    public static synchronized void load(InputStream input) throws Exception {
        if(!ALL.isEmpty())return;
        ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] a=new byte[8192];int n;
        while((n=input.read(a))!=-1)b.write(a,0,n);
        JSONArray json=new JSONArray(b.toString("UTF-8"));
        for(int i=0;i<json.length();i++){
            Spec spec=new Spec(json.getJSONObject(i));ALL.add(spec);
            if(!spec.read.isEmpty())READS.add(spec.read);
            if(!spec.write.isEmpty())WRITES.add(spec.write);
        }
    }
    public static boolean allowsRead(String action){return READS.contains(action);}
    public static boolean allowsWrite(String action){return WRITES.contains(action);}
    public static Spec find(String id){for(Spec s:ALL)if(s.id.equals(id))return s;throw new IllegalArgumentException("Unknown settings page");}
    public static final class Spec {
        public final String id,title,group,read,write,impact,note;
        public final boolean routerOnly,disruption;
        public final List<JSONObject> fields=new ArrayList<>(),arrays=new ArrayList<>();
        public final List<String> paths=new ArrayList<>(),required=new ArrayList<>();
        Spec(JSONObject raw){
            id=raw.optString("id");title=raw.optString("title");group=raw.optString("group");read=raw.optString("read");write=raw.optString("write");
            impact=raw.optString("impact");note=raw.optString("note");routerOnly=raw.optBoolean("routerOnly");disruption=raw.optBoolean("disruption");
            JSONArray fs=raw.optJSONArray("fields");if(fs!=null)for(int i=0;i<fs.length();i++){JSONObject f=fs.optJSONObject(i);fields.add(f);paths.add(f.optString("path"));}
            JSONArray as=raw.optJSONArray("arrays");if(as!=null)for(int i=0;i<as.length();i++)arrays.add(as.optJSONObject(i));
            JSONArray rs=raw.optJSONArray("required");if(rs!=null)for(int i=0;i<rs.length();i++)required.add(rs.optString(i));
        }
        public JSONObject project(JSONObject raw){return Json.project(raw,paths);}
        public boolean editable(){return !write.isEmpty();}
    }
    private SettingsCatalog(){}
}
