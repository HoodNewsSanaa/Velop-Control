package com.velopcontrol.app;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Validation happens again in the repository, independently of form widgets. */
public final class SettingsValidation {
    public static long ipv4(String value){
        if(!value.matches("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}"))throw new IllegalArgumentException("Enter a valid IPv4 address");
        long n=0;for(String part:value.split("\\.")){int p=Integer.parseInt(part);if(p>255)throw new IllegalArgumentException("Invalid IPv4 address");n=(n<<8)|p;}return n;
    }
    public static boolean masked(String value){return !value.isEmpty() && value.matches("[*•●]+");}
    public static void check(SettingsCatalog.Spec spec,JSONObject payload,JSONObject capabilities){
        for(String path:spec.required)if(Json.get(payload,path)==null)throw new IllegalArgumentException("This firmware did not return all required settings. Reload before saving.");
        for(JSONObject field:spec.fields){
            String pattern=field.optString("path"),type=field.optString("type"),label=field.optString("label");
            for(String path:Json.expand(payload,pattern)){
                Object value=Json.get(payload,path);if(value==null)continue;
                String s=String.valueOf(value);
                if(type.equals("bool") && !(value instanceof Boolean))fail(label,"choose on or off");
                if(type.equals("number")){
                    if(!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).longValue())fail(label,"enter a whole number");
                    double v=((Number)value).doubleValue();if(v<field.optDouble("min",0)||v>field.optDouble("max",Integer.MAX_VALUE))fail(label,"value is outside the permitted range");
                }
                if(type.equals("ip") || type.equals("ipoptional")){if(!s.isEmpty() || type.equals("ip"))ipv4(s);}
                if(type.equals("mac")||type.equals("macoptional")){if(!s.isEmpty()||type.equals("mac"))if(!s.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))fail(label,"use six hexadecimal pairs separated by colons");}
                if(type.equals("ssid")){int size=s.getBytes(StandardCharsets.UTF_8).length;if(size<1||size>32)fail(label,"use 1–32 UTF-8 bytes");}
                if(type.equals("hostname")&&!s.matches("[A-Za-z0-9][A-Za-z0-9-]{0,62}"))fail(label,"use a hostname with letters, numbers and hyphens");
                if(type.equals("schedule")&&!s.matches("[01]{48}"))fail(label,"set a valid daily schedule");
                if(type.equals("password") && masked(s))fail(label,"the router returned a masked password; enter the actual password before saving");
                if(type.equals("enum")&&field.has("options")&&!allowed(field.optJSONArray("options"),value)&&!Json.same(value,Json.get(capabilities,path)))fail(label,"choose a listed option");
                if(type.equals("maclist")){
                    if(!(value instanceof JSONArray))fail(label,"choose a list of MAC addresses");
                    JSONArray list=(JSONArray)value;for(int i=0;i<list.length();i++)if(!list.optString(i).matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))fail(label,"one or more MAC addresses are invalid");
                }
                if(type.equals("radiolist")){
                    if(!(value instanceof JSONArray))fail(label,"choose supported radios");
                    JSONArray available=capabilities.optJSONArray("configurableRadios");
                    JSONArray list=(JSONArray)value;for(int i=0;i<list.length();i++){
                        boolean found=false;for(int j=0;available!=null&&j<available.length();j++)if(Json.same(list.opt(i),available.opt(j)))found=true;
                        if(!found)fail(label,"choose a radio advertised by this node");
                    }
                }
            }
        }
        if(spec.id.equals("wifi")){
            JSONArray radios=payload.optJSONArray("radios");boolean enabled=false;
            for(int i=0;radios!=null&&i<radios.length();i++){
                JSONObject settings=radios.optJSONObject(i).optJSONObject("settings");if(settings==null)continue;
                if(settings.optBoolean("isEnabled"))enabled=true;
                if(settings.optBoolean("isEnabled") && settings.optString("security").toLowerCase(Locale.ROOT).matches(".*(personal|psk).*")){
                    String password=Topology.object(settings,"wpaPersonalSettings").optString("passphrase");
                    if(!(password.length()>=8&&password.length()<=63)&&!password.matches("(?i)[a-f0-9]{64}"))fail("Wi-Fi password","use 8–63 characters or 64 hexadecimal digits");
                }
                if(settings.optString("security").toLowerCase(Locale.ROOT).contains("enterprise")){
                    JSONObject e=settings.optJSONObject("wpaEnterpriseSettings");if(e==null||e.optString("radiusServer").isEmpty()||e.optInt("radiusPort")==0||e.optString("sharedKey").isEmpty())fail("Enterprise Wi-Fi","enter the RADIUS server, port and shared key");
                }
                final String radioId=radios.optJSONObject(i).optString("radioID");
                JSONObject radio=Topology.entries(capabilities,"radios").stream().filter(r->r.optString("radioID").equals(radioId)).findFirst().orElse(new JSONObject());
                if(!allowed(radio.optJSONArray("supportedModes"),settings.opt("mode")))fail("Wireless mode","choose a mode supported by this radio");
                if(!allowed(radio.optJSONArray("supportedSecurityTypes"),settings.opt("security")))fail("Security","choose a supported security type");
                JSONArray widths=radio.optJSONArray("supportedChannelsForChannelWidths");boolean channelOK=widths==null||widths.length()==0;
                for(int j=0;widths!=null&&j<widths.length();j++){
                    JSONObject w=widths.optJSONObject(j);if(w!=null&&w.optString("channelWidth").equals(settings.optString("channelWidth"))&&allowed(w.optJSONArray("channels"),settings.opt("channel")))channelOK=true;
                }
                if(!channelOK)fail("Channel","choose a channel and width advertised by this radio");
            }
            if(!enabled)fail("Wi-Fi radios","keep at least one radio enabled in this build");
        }
        if(spec.id.equals("management")&&!payload.optBoolean("canManageUsingHTTPS"))fail("HTTPS","keep HTTPS enabled so this app can reconnect");
        if(spec.id.equals("lan")){
            int prefix=payload.optInt("networkPrefixLength",24);long mask=(0xffffffffL<<(32-prefix))&0xffffffffL;
            long router=ipv4(payload.optString("ipAddress")),network=router&mask,broadcast=network|(~mask&0xffffffffL);
            if(router==network||router==broadcast)fail("LAN address","use a host address");
            JSONObject dhcp=payload.optJSONObject("dhcpSettings");
            if(payload.optBoolean("isDHCPEnabled")){
                if(dhcp==null)fail("DHCP","the address pool is missing");
                long first=ipv4(dhcp.optString("firstClientIPAddress")),last=ipv4(dhcp.optString("lastClientIPAddress"));
                if((first&mask)!=network||(last&mask)!=network||first>last||first==network||last==broadcast||(router>=first&&router<=last))fail("DHCP pool","use addresses in this subnet that exclude the router");
                Set<String> ips=new HashSet<>(),macs=new HashSet<>();
                for(JSONObject r:Topology.entries(dhcp,"reservations")){
                    long ip=ipv4(r.optString("ipAddress"));
                    if((ip&mask)!=network||ip==network||ip==broadcast||ip==router||!ips.add(r.optString("ipAddress"))||!macs.add(r.optString("macAddress").toUpperCase(Locale.ROOT)))fail("Reservations","check the subnet and remove duplicate IP or MAC addresses");
                }
            }
        }
        if(spec.id.equals("wan")){
            String type=payload.optString("wanType");
            if(!type.matches("DHCP|Static|PPPoE|PPTP|L2TP|Bridge"))fail("Connection type","this build does not have a complete editor for the current internet mode");
            requireAdvertisedMode(type,"supportedWANTypes",capabilities);
            if(type.equals("Static"))requireStatic(payload,"staticSettings");
            if(type.equals("Bridge")&&Boolean.TRUE.equals(Json.get(payload,"bridgeSettings.useStaticSettings")))requireStatic(payload,"bridgeSettings.staticSettings");
            if((type.equals("PPTP")||type.equals("L2TP"))){requireCredentials(payload,"tpSettings");if(String.valueOf(Json.get(payload,"tpSettings.server")).matches("null|"))fail(type,"enter the VPN server");if(Boolean.TRUE.equals(Json.get(payload,"tpSettings.useStaticSettings")))requireStatic(payload,"tpSettings.staticSettings");}
            if(type.equals("PPPoE"))requireCredentials(payload,"pppoeSettings");
            if(type.equals("Bridge")&&Json.get(payload,"bridgeSettings.useStaticSettings")==null)try{Json.set(payload,"bridgeSettings.useStaticSettings",false);}catch(Exception ignored){}
        }
        if(spec.id.equals("ipv6")){
            requireAdvertisedMode(payload.optString("wanType"),"supportedIPv6WANTypes",capabilities);
            if(payload.optString("wanType").equals("Automatic")&&Json.get(payload,"ipv6AutomaticSettings.isIPv6AutomaticEnabled")==null)fail("Automatic IPv6","choose enabled or disabled");
        }
        if(spec.id.equals("guest"))for(JSONObject radio:Topology.entries(payload,"radios"))if(radio.optBoolean("isEnabled")&&radio.has("guestWPAPassphrase")){
            String s=radio.optString("guestWPAPassphrase");if(s.length()<8||s.length()>63)fail("Guest password","use 8–63 characters");
        }
        if(spec.id.equals("guest")&&payload.optBoolean("isGuestNetworkEnabled")&&capabilities.has("canEnableGuestNetwork")&&!capabilities.optBoolean("canEnableGuestNetwork"))fail("Guest Wi-Fi","the firmware reports that guest networking cannot be enabled in this configuration");
        if(spec.id.equals("clone")&&payload.optBoolean("isMACAddressCloneEnabled")&&payload.optString("macAddress").isEmpty())fail("Cloned MAC","enter the address to clone");
        if(spec.id.equals("dmz")&&payload.optBoolean("isDMZEnabled")&&payload.optString("destinationIPAddress").isEmpty()&&payload.optString("destinationMACAddress").isEmpty())fail("DMZ","select a destination device");
        if(spec.id.equals("smb")&&!payload.optBoolean("isAnonymousAccessEnabled")&&(payload.optString("username").isEmpty()||payload.optString("password").isEmpty()))fail("File sharing","enter a username and password");
        for(JSONObject array:spec.arrays){
            Object list=Json.get(payload,array.optString("path"));if(!(list instanceof JSONArray))continue;
            String maxField=array.optString("maxField");int max=capabilities.optInt(maxField,100);
            if(((JSONArray)list).length()>max)fail(array.optString("label"),"too many entries");
        }
        for(JSONObject rule:Topology.entries(payload,"rules")){
            if(rule.has("firstExternalPort")&&rule.optInt("firstExternalPort")>rule.optInt("lastExternalPort"))fail("Port range","start must not exceed end");
            if(rule.has("firstTriggerPort")&&(rule.optInt("firstTriggerPort")>rule.optInt("lastTriggerPort")||rule.optInt("firstForwardedPort")>rule.optInt("lastForwardedPort")))fail("Port triggering","check the start and end ports");
        }
    }
    private static void requireAdvertisedMode(String type,String field,JSONObject capabilities){
        if(type.equals(capabilities.optString("wanType")))return;JSONArray modes=capabilities.optJSONArray(field);
        if(modes==null||!allowed(modes,type))fail("Connection type","this node has not advertised support for that mode");
    }
    private static void requireStatic(JSONObject payload,String prefix){
        for(String key:Arrays.asList("ipAddress","networkPrefixLength","gateway","dnsServer1"))if(Json.get(payload,prefix+"."+key)==null||String.valueOf(Json.get(payload,prefix+"."+key)).isEmpty())fail("Static address","enter address, prefix, gateway and primary DNS");
    }
    private static void requireCredentials(JSONObject payload,String prefix){
        for(String key:Arrays.asList("username","password"))if(Json.get(payload,prefix+"."+key)==null||String.valueOf(Json.get(payload,prefix+"."+key)).isEmpty())fail("Internet credentials","enter username and password");
    }
    private static boolean allowed(JSONArray list,Object value){if(list==null||value==null)return true;for(int i=0;i<list.length();i++)if(String.valueOf(list.opt(i)).equals(String.valueOf(value)))return true;return false;}
    private static void fail(String label,String reason){throw new IllegalArgumentException(label+": "+reason);}
    private SettingsValidation(){}
}
