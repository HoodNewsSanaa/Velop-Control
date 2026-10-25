package com.velopcontrol.app;

import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Shareable, structure-only diagnostics. No raw responses, credentials, names, IPs or MACs. */
public final class AuditReport {
    private final JSONObject root=new JSONObject();
    private final JSONArray records=new JSONArray();
    private int passed,unsupported,failed,skipped;
    public AuditReport(){
        put(root,"application","Velop Control");put(root,"version",BuildConfig.VERSION_NAME);put(root,"createdUTC",utc());
        put(root,"readOnly",true);put(root,"writeOperationsTested",false);put(root,"formatVersion",2);
        put(root,"privacy","No raw router responses, credentials, device names, IP addresses or MAC addresses. Array indexes are structural positions.");put(root,"checks",records);
    }
    public synchronized void add(String node,String action,String status,long duration,JSONObject response){
        JSONObject item=new JSONObject();put(item,"node",node);put(item,"action",action);put(item,"status",status);put(item,"durationMs",duration);put(item,"utc",utc());
        if(response!=null){
            put(item,"result",SettingsRepository.safeCode(response.optString("result","MissingResult")));
            JSONObject schema=new JSONObject();shape(response.optJSONObject("output"),"",schema,new int[]{0});put(item,"schema",schema);
            JSONObject flags=new JSONObject();flags(response.optJSONObject("output"),"",flags,new int[]{0});put(item,"reportedFlags",flags);
            if(action.equals(ReadPolicy.INFO)){JSONObject o=response.optJSONObject("output");if(o!=null){put(item,"model",safe(o.optString("modelNumber")));put(item,"firmware",safe(o.optString("firmwareVersion")));}}
        }
        records.put(item);if(status.equals("Passed"))passed++;else if(status.equals("Unsupported"))unsupported++;else if(status.equals("Skipped"))skipped++;else failed++;
    }
    public synchronized String summary(){return passed+" passed · "+unsupported+" unavailable · "+failed+" failed · "+skipped+" skipped";}
    public synchronized int size(){return records.length();}
    public synchronized String json(){put(root,"summary",summary());put(root,"completedUTC",utc());try{return root.toString(2);}catch(Exception e){return root.toString();}}
    private static void shape(Object value,String path,JSONObject out,int[] count){
        if(count[0]++>600||Json.secret(path))return;
        if(value instanceof JSONObject){put(out,path.isEmpty()?"output":path,"object");for(String key:Json.keys((JSONObject)value)){if(!key.matches("[A-Za-z][A-Za-z0-9_]{0,80}"))continue;shape(((JSONObject)value).opt(key),path.isEmpty()?key:path+"."+key,out,count);}}
        else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;put(out,path,"array("+a.length()+")");if(a.length()>0)shape(a.opt(0),path+".*",out,count);}
        else if(value!=null&&value!=JSONObject.NULL)put(out,path,value instanceof Boolean?"boolean":value instanceof Number?"number":"string");
    }
    private static void flags(Object value,String path,JSONObject out,int[] count){
        if(count[0]++>300||Json.secret(path))return;
        if(value instanceof JSONObject)for(String key:Json.keys((JSONObject)value)){if(key.matches("[A-Za-z][A-Za-z0-9_]{0,80}"))flags(((JSONObject)value).opt(key),path.isEmpty()?key:path+"."+key,out,count);}
        else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<Math.min(a.length(),5);i++)flags(a.opt(i),path+"."+i,out,count);}
        else if(value instanceof Boolean)put(out,path,value);
    }
    private static String safe(String v){return v.matches("[A-Za-z0-9._ -]{1,80}")?v:"Unreported";}
    private static String utc(){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.ROOT);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date());}
    private static void put(JSONObject o,String k,Object v){try{o.put(k,v);}catch(JSONException e){throw new IllegalStateException(e);}}
}
