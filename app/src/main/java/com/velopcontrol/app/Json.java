package com.velopcontrol.app;

import org.json.*;
import java.util.*;

/** Small JSON helpers; never log a request, response, password or router error body. */
public final class Json {
    public static JSONObject copy(JSONObject value) { try { return new JSONObject(value.toString()); } catch (JSONException e) { throw new IllegalArgumentException("Invalid data"); } }
    public static Object get(Object root, String path) {
        Object at=root;
        if(path.isEmpty()) return at;
        for(String key:path.split("\\.")) {
            if(at instanceof JSONObject) at=((JSONObject)at).opt(key);
            else if(at instanceof JSONArray) { try { at=((JSONArray)at).opt(Integer.parseInt(key)); } catch(Exception e){return null;} }
            else return null;
            if(at==null || at==JSONObject.NULL) return null;
        }
        return at;
    }
    public static void set(JSONObject root,String path,Object value) {
        String[] keys=path.split("\\."); Object at=root;
        try {
            for(int i=0;i<keys.length-1;i++){
                if(at instanceof JSONObject){
                    JSONObject o=(JSONObject)at; Object next=o.opt(keys[i]);
                    if(next==null || next==JSONObject.NULL){ next=keys[i+1].matches("[0-9]+")?new JSONArray():new JSONObject();o.put(keys[i],next); }
                    at=next;
                } else {
                    JSONArray a=(JSONArray)at;int n=Integer.parseInt(keys[i]);Object next=a.opt(n);
                    if(next==null || next==JSONObject.NULL){next=keys[i+1].matches("[0-9]+")?new JSONArray():new JSONObject();a.put(n,next);}
                    at=next;
                }
            }
            if(at instanceof JSONObject)((JSONObject)at).put(keys[keys.length-1],value);
            else ((JSONArray)at).put(Integer.parseInt(keys[keys.length-1]),value);
        }catch(Exception e){throw new IllegalArgumentException("This field cannot be changed");}
    }
    public static List<String> keys(JSONObject o){List<String> a=new ArrayList<>();o.keys().forEachRemaining(a::add);Collections.sort(a);return a;}
    public static String canonical(Object value) {
        if(value instanceof JSONObject){List<String> a=new ArrayList<>();for(String k:keys((JSONObject)value))a.add(JSONObject.quote(k)+":"+canonical(((JSONObject)value).opt(k)));return "{"+String.join(",",a)+"}";}
        if(value instanceof JSONArray){List<String>a=new ArrayList<>();for(int i=0;i<((JSONArray)value).length();i++)a.add(canonical(((JSONArray)value).opt(i)));return "["+String.join(",",a)+"]";}
        return value==null || value==JSONObject.NULL?"null":value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
    public static boolean same(Object a,Object b){return canonical(a).equals(canonical(b));}
    public static List<String> expand(JSONObject root,String pattern) {
        List<String> result=new ArrayList<>();expand(root,pattern.split("\\."),0,"",result);return result;
    }
    private static void expand(Object at,String[] keys,int i,String path,List<String> out){
        if(i==keys.length){out.add(path);return;}
        if(keys[i].equals("*")){
            if(at instanceof JSONArray)for(int n=0;n<((JSONArray)at).length();n++)expand(((JSONArray)at).opt(n),keys,i+1,path+"."+n,out);
        }else{
            Object next=at instanceof JSONObject?((JSONObject)at).opt(keys[i]):null;
            expand(next,keys,i+1,path.isEmpty()?keys[i]:path+"."+keys[i],out);
        }
    }
    public static JSONObject project(JSONObject raw,Collection<String> patterns){
        JSONObject out=new JSONObject();
        for(String pattern:patterns){
            int wildcard=pattern.indexOf(".*");
            if(wildcard>0){String prefix=pattern.substring(0,wildcard);Object a=get(raw,prefix);if(a instanceof JSONArray && ((JSONArray)a).length()==0)set(out,prefix,new JSONArray());}
            for(String path:expand(raw,pattern)){
            Object val=get(raw,path);
            if(val!=null)try{set(out,path,val instanceof JSONObject?copy((JSONObject)val):val instanceof JSONArray?new JSONArray(val.toString()):val);}catch(JSONException e){throw new IllegalArgumentException("Invalid array");}
        }}
        return out;
    }
    public static boolean secret(String path){return path.toLowerCase(Locale.ROOT).matches(".*(password|passphrase|sharedkey|wepsettings|secret|token|authorization).*");}
    public static String label(String field){
        String s=field.replaceAll("([a-z0-9])([A-Z])","$1 $2").replaceAll("^is ","").replace("IPAddress","IP address").replace("SSID","Wi-Fi name").replace("dns","DNS");
        return s.isEmpty()?s:Character.toUpperCase(s.charAt(0))+s.substring(1);
    }
    private Json(){}
}
