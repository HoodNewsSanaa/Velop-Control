package com.velopcontrol.app;

import java.net.URI;
import java.util.*;
import org.json.*;

/** Public GitHub metadata. Never receives router sessions or credentials. */
public final class ReleaseInfo {
    public static final String REPOSITORY="HoodNewsSanaa/Velop-Control";
    public static final String API="https://api.github.com/repos/"+REPOSITORY+"/releases/latest";
    public final String version,url,digest,notes;
    public final long size;
    private ReleaseInfo(String version,String url,String digest,String notes,long size){this.version=version;this.url=url;this.digest=digest;this.notes=notes;this.size=size;}
    public static ReleaseInfo parse(JSONObject json){
        if(json.optBoolean("draft")||json.optBoolean("prerelease"))throw new IllegalArgumentException("No stable release available");
        String tag=json.optString("tag_name"),version=tag.replaceFirst("^v","");parts(version);
        JSONArray assets=json.optJSONArray("assets");JSONObject apk=null;
        for(int i=0;assets!=null&&i<assets.length();i++){JSONObject a=assets.optJSONObject(i);if(a!=null&&a.optString("name").equals("Velop-Control-"+version+".apk")){if(apk!=null)throw new IllegalArgumentException("Ambiguous release assets");apk=a;}}
        if(apk==null)throw new IllegalArgumentException("The release has no matching Android APK");
        String url=apk.optString("browser_download_url"),digest=apk.optString("digest");
        if(!url.equals("https://github.com/"+REPOSITORY+"/releases/download/"+tag+"/Velop-Control-"+version+".apk"))throw new IllegalArgumentException("Unexpected APK location");
        if(!digest.matches("sha256:[a-fA-F0-9]{64}"))throw new IllegalArgumentException("GitHub has not provided an APK SHA-256 digest");
        long size=apk.optLong("size");if(size<1||size>64L*1024*1024)throw new IllegalArgumentException("Unexpected APK size");
        String notes=json.optString("body");return new ReleaseInfo(version,url,digest.substring(7).toLowerCase(Locale.ROOT),notes.length()>5000?notes.substring(0,5000):notes,size);
    }
    public static boolean allowedDownload(String url){
        try{URI uri=new URI(url);String host=uri.getHost();return uri.getScheme().equals("https")&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)&&Arrays.asList("github.com","release-assets.githubusercontent.com","objects.githubusercontent.com").contains(host);}catch(Exception e){return false;}
    }
    private static int[] parts(String version){if(!version.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"))throw new IllegalArgumentException("Unrecognized app version");String[] p=version.split("\\.");return new int[]{Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2])};}
    public static int compare(String a,String b){int[] first=parts(a),second=parts(b);for(int i=0;i<3;i++){int c=Integer.compare(first[i],second[i]);if(c!=0)return c;}return 0;}
}
