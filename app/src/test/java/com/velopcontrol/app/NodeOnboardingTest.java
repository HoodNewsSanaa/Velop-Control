package com.velopcontrol.app;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class NodeOnboardingTest {
    private static final class Router implements NodeOnboarding.Transport,NodeOnboarding.Journal {
        boolean enabled,advertised=true,missingSetting,failEnable,failDisable,failStart;String mode="Master",serial="synthetic-primary",journal="";
        JSONArray devices=new JSONArray();List<String> calls=new ArrayList<>();int enables,disables,starts;
        public void save(String record){journal=record;}
        public JSONObject call(String action,JSONObject body) throws Exception {
            NodeOnboarding.validate(action,body);calls.add(action);
            JSONObject o=new JSONObject();
            if(action.equals(ReadPolicy.MODE))o.put("mode",mode);
            else if(action.equals(ReadPolicy.INFO))o.put("serialNumber",serial).put("services",new JSONArray(advertised?"[\"http://linksys.com/jnap/nodes/autoonboarding/AutoOnboarding\"]":"[]"));
            else if(action.equals(ReadPolicy.DEVICES))o.put("devices",devices);
            else if(action.contains("GetBluetoothAutoOnboardingStatus"))o.put("autoOnboardingStatus","running");
            else if(action.contains("Settings")){
                if(action.contains("/Set")){
                    assertFalse("Journal must be durable before writes",journal.isEmpty());enabled=body.getBoolean("isAutoOnboardingEnabled");
                    if(enabled){enables++;if(failEnable)throw new java.io.IOException();}else{disables++;if(failDisable)throw new java.io.IOException();}
                }else if(!missingSetting)o.put("isAutoOnboardingEnabled",enabled);
            }else if(action.endsWith("StartBluetoothAutoOnboarding")){starts++;if(failStart)throw new java.io.IOException();}
            return new JSONObject().put("result","OK").put("output",o);
        }
        void device(String id,String type,boolean online) throws Exception {devices.put(new JSONObject().put("deviceID",id).put("nodeType",type).put("friendlyName",id).put("connections",online?new JSONArray("[{\"ipAddress\":\"192.168.1.20\"}]"):new JSONArray()));}
        NodeOnboarding flow(NodeOnboarding.Kind kind) throws Exception{return new NodeOnboarding(this,this,NodeOnboarding.inspect(this,kind));}
    }
    @Test public void wiredWindowEnablesOnceAndRestores() throws Exception {Router r=new Router();NodeOnboarding f=r.flow(NodeOnboarding.Kind.WIRED);f.start();assertEquals(1,r.enables);assertEquals(0,r.starts);assertFalse(r.journal.isEmpty());f.stop();assertEquals(1,r.disables);assertEquals("",r.journal);assertTrue(f.finished());f.stop();assertEquals(1,r.disables);}
    @Test public void wirelessStartIsOneShot() throws Exception {Router r=new Router();NodeOnboarding f=r.flow(NodeOnboarding.Kind.WIRELESS);f.start();assertEquals(1,r.starts);try{f.start();fail();}catch(IllegalStateException expected){}assertEquals(1,r.starts);}
    @Test public void originalEnabledSettingIsRetained() throws Exception {Router r=new Router();r.enabled=true;NodeOnboarding f=r.flow(NodeOnboarding.Kind.WIRELESS);f.start();f.stop();assertTrue(r.enabled);assertEquals(0,r.enables+r.disables);assertEquals("",r.journal);}
    @Test public void wrongCoordinatorAndUnsupportedFirmwareNeverWrite() throws Exception {Router r=new Router();r.mode="Slave";try{r.flow(NodeOnboarding.Kind.WIRED);fail();}catch(IllegalStateException expected){}r.mode="Master";r.advertised=false;try{r.flow(NodeOnboarding.Kind.WIRED);fail();}catch(IllegalStateException expected){}assertEquals(0,r.enables+r.starts);}
    @Test public void missingSettingBlocksSetup() throws Exception {Router r=new Router();r.missingSetting=true;try{r.flow(NodeOnboarding.Kind.WIRED);fail();}catch(IllegalStateException expected){}assertEquals("",r.journal);}
    @Test public void changedFlagsInvalidatePreview() throws Exception {Router r=new Router();NodeOnboarding f=r.flow(NodeOnboarding.Kind.WIRED);r.enabled=true;try{f.start();fail();}catch(IllegalStateException expected){}assertFalse(f.attempted());assertEquals(0,r.enables);}
    @Test public void changedInventoryInvalidatesPreview() throws Exception {Router r=new Router();NodeOnboarding f=r.flow(NodeOnboarding.Kind.WIRED);r.device("new","Slave",true);try{f.start();fail();}catch(IllegalStateException expected){}assertFalse(f.attempted());}
    @Test public void lostEnableReplyIsNotRetriedAndCanBeRecovered() throws Exception {Router r=new Router();r.failEnable=true;NodeOnboarding f=r.flow(NodeOnboarding.Kind.WIRELESS);try{f.start();fail();}catch(java.io.IOException expected){}assertTrue(f.attempted());assertEquals(0,r.starts);try{f.start();fail();}catch(IllegalStateException expected){}assertEquals(1,r.enables);f.stop();assertFalse(r.enabled);assertEquals("",r.journal);}
    @Test public void lostStartReplyNeverBecomesSuccess() throws Exception {Router r=new Router();r.failStart=true;NodeOnboarding f=r.flow(NodeOnboarding.Kind.WIRELESS);try{f.start();fail();}catch(java.io.IOException expected){}assertTrue(f.poll().added.isEmpty());assertEquals(1,r.starts);assertFalse(r.journal.isEmpty());}
    @Test public void onlyNewConnectedSlavesCount() throws Exception {Router r=new Router();r.device("existing","Slave",false);NodeOnboarding f=r.flow(NodeOnboarding.Kind.WIRELESS);f.start();r.devices=new JSONArray();r.device("EXISTING","Slave",true);r.device("laptop","",true);r.device("discovered","Unconfigured",true);r.device("offline","Slave",false);assertTrue(f.poll().added.isEmpty());r.device("new-node","Slave",true);assertEquals(Collections.singletonList("new-node"),f.poll().added);}
    @Test public void previouslyDiscoveredUnconfiguredNodeCanBeConfirmed() throws Exception {Router r=new Router();r.device("candidate","Unconfigured",true);NodeOnboarding f=r.flow(NodeOnboarding.Kind.WIRED);f.start();r.devices=new JSONArray();r.device("candidate","Slave",true);assertEquals(Collections.singletonList("candidate"),f.poll().added);}
    @Test public void failedCleanupKeepsJournalForExplicitRecovery() throws Exception {Router r=new Router();NodeOnboarding f=r.flow(NodeOnboarding.Kind.WIRED);f.start();r.failDisable=true;try{f.stop();fail();}catch(java.io.IOException expected){}assertFalse(f.finished());assertFalse(r.journal.isEmpty());r.failDisable=false;NodeOnboarding.recover(r,r,r.journal);assertEquals("",r.journal);}
    @Test public void journalFailurePreventsAnyWrite() throws Exception {Router r=new Router();NodeOnboarding f=new NodeOnboarding(r,value->{throw new java.io.IOException();},NodeOnboarding.inspect(r,NodeOnboarding.Kind.WIRED));try{f.start();fail();}catch(java.io.IOException expected){}assertEquals(0,r.enables+r.starts);assertFalse(f.attempted());}
    @Test public void differentPrimaryCannotReceiveCleanup() throws Exception {Router r=new Router();NodeOnboarding f=r.flow(NodeOnboarding.Kind.WIRED);f.start();r.serial="other-primary";try{f.stop();fail();}catch(IllegalStateException expected){}assertEquals(0,r.disables);assertFalse(r.journal.isEmpty());}
    @Test public void unexpectedActionsAndFieldsAreRejected() throws Exception {for(String a:new String[]{"nodes/setup/SetAdminPassword2","nodes/smartmode/SetDeviceMode",NodeOnboarding.PREFIX+"RemoveNode"})try{NodeOnboarding.validate(a,new JSONObject());fail();}catch(IllegalArgumentException expected){}try{NodeOnboarding.validate(NodeOnboarding.setter(NodeOnboarding.Kind.WIRED),new JSONObject().put("isAutoOnboardingEnabled",true).put("password","secret"));fail();}catch(IllegalArgumentException expected){}try{NodeOnboarding.validate(NodeOnboarding.setter(NodeOnboarding.Kind.WIRED),new JSONObject().put("isAutoOnboardingEnabled","true"));fail();}catch(IllegalArgumentException expected){}}
}
