package com.velopcontrol.app;

import org.json.*;
import org.junit.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Tests the consequential boundaries, independently of Android widgets or hardware. */
public class SettingsSafetyTest {
    @BeforeClass public static void catalog() throws Exception {try(InputStream in=new FileInputStream("src/main/assets/settings.json")){SettingsCatalog.load(in);}}
    private static class Router implements SettingsRepository.Transport {
        JSONObject current;int reads,writes;boolean timeout,reject;String address="192.168.1.1";
        Router(String value) throws Exception {current=new JSONObject(value);}
        public JSONObject read(String action,boolean auth) throws Exception {reads++;return new JSONObject().put("result","OK").put("output",Json.copy(current));}
        public JSONObject write(String action,JSONObject body) throws Exception {writes++;if(reject)throw new SecurityException();if(timeout)throw new IOException();current=Json.copy(body);return new JSONObject().put("result","OK");}
        public String host(){return address;}
    }
    private SettingsRepository.Change loggingChange(SettingsRepository repo) throws Exception {
        SettingsRepository.Snapshot snapshot=repo.load(SettingsCatalog.find("logging"));JSONObject desired=snapshot.editable();desired.put("isLoggingEnabled",true);return repo.prepare(snapshot,desired);
    }
    @Test public void changedSettingsAreCheckedAgainAndWrittenOnce() throws Exception {
        Router router=new Router("{\"isLoggingEnabled\":false,\"readOnlyCapability\":true}");SettingsRepository repo=new SettingsRepository(router);SettingsRepository.Change change=loggingChange(repo);
        assertTrue(repo.apply(change,true).verified);assertEquals(1,router.writes);assertFalse(router.current.has("readOnlyCapability"));
        assertThrows(IllegalStateException.class,()->repo.apply(change,true));assertEquals(1,router.writes);
    }
    @Test public void confirmationIsRequiredAndRejectionDoesNotConsumeDraft() throws Exception {
        Router router=new Router("{\"isLoggingEnabled\":false}");SettingsRepository repo=new SettingsRepository(router);SettingsRepository.Change change=loggingChange(repo);
        assertThrows(SecurityException.class,()->repo.apply(change,false));assertEquals(0,router.writes);assertTrue(repo.apply(change,true).verified);
    }
    @Test public void concurrentRouterChangeBlocksTheWrite() throws Exception {
        Router router=new Router("{\"isLoggingEnabled\":false}");SettingsRepository repo=new SettingsRepository(router);SettingsRepository.Change change=loggingChange(repo);router.current.put("isLoggingEnabled",true);
        assertThrows(IllegalStateException.class,()->repo.apply(change,true));assertEquals(0,router.writes);
    }
    @Test public void differentRouterSessionCannotUseTheDraft() throws Exception {
        Router router=new Router("{\"isLoggingEnabled\":false}");SettingsRepository repo=new SettingsRepository(router);SettingsRepository.Change change=loggingChange(repo);router.address="192.168.1.2";
        assertThrows(SecurityException.class,()->repo.apply(change,true));assertEquals(0,router.writes);
    }
    @Test public void timeoutIsUncertainAndNeverRetried() throws Exception {
        Router router=new Router("{\"isLoggingEnabled\":false}");router.timeout=true;SettingsRepository repo=new SettingsRepository(router);SettingsRepository.Change change=loggingChange(repo);
        assertThrows(SettingsRepository.UncertainChangeException.class,()->repo.apply(change,true));assertEquals(1,router.writes);assertThrows(IllegalStateException.class,()->repo.apply(change,true));assertEquals(1,router.writes);
    }
    @Test public void authenticationFailureRemainsDistinct() throws Exception {
        Router router=new Router("{\"isLoggingEnabled\":false}");router.reject=true;SettingsRepository repo=new SettingsRepository(router);SettingsRepository.Change change=loggingChange(repo);
        assertThrows(SecurityException.class,()->repo.apply(change,true));assertEquals(1,router.writes);
    }
    @Test public void projectionKeepsArrayIdentityAndEmptyRuleLists() throws Exception {
        JSONObject wireless=new JSONObject("{\"radios\":[{\"radioID\":\"5GHz\",\"capability\":true,\"settings\":{\"isEnabled\":true,\"ssid\":\"Home\"}}],\"readOnly\":10}");
        JSONObject projected=SettingsCatalog.find("wifi").project(wireless);assertFalse(projected.has("readOnly"));assertEquals("5GHz",Json.get(projected,"radios.0.radioID"));assertNull(Json.get(projected,"radios.0.capability"));
        JSONObject empty=SettingsCatalog.find("forward").project(new JSONObject("{\"rules\":[],\"maxRules\":50}"));assertEquals(0,empty.getJSONArray("rules").length());assertFalse(empty.has("maxRules"));
    }
    @Test public void passwordChangesAreHiddenInPreview() throws Exception {
        Router router=new Router("{\"isAnonymousAccessEnabled\":false,\"username\":\"admin\",\"password\":\"old-secret-value\"}");SettingsRepository repo=new SettingsRepository(router);SettingsRepository.Snapshot snapshot=repo.load(SettingsCatalog.find("smb"));JSONObject value=snapshot.editable();value.put("password","new-secret-value");String preview=repo.prepare(snapshot,value).preview;
        assertFalse(preview.contains("old-secret"));assertFalse(preview.contains("new-secret"));assertTrue(preview.contains("password changed"));
    }
    @Test public void maskedPasswordsCannotBeAccidentallySent() throws Exception {
        JSONObject payload=new JSONObject("{\"isAnonymousAccessEnabled\":false,\"username\":\"admin\",\"password\":\"********\"}");
        assertThrows(IllegalArgumentException.class,()->SettingsValidation.check(SettingsCatalog.find("smb"),payload,new JSONObject()));
    }
    @Test public void wirelessScheduleRetainsObjectRadioSelectors() throws Exception {
        JSONObject value=new JSONObject("{\"isWirelessSchedulerEnabled\":true,\"configuredRadios\":[{\"radioID\":\"5GHz\",\"isGuestRadio\":false}],\"configurableRadios\":[{\"radioID\":\"5GHz\",\"isGuestRadio\":false}]}" );
        JSONObject draft=SettingsCatalog.find("schedule").project(value);SettingsValidation.check(SettingsCatalog.find("schedule"),draft,value);
        assertEquals("5GHz",draft.getJSONArray("configuredRadios").getJSONObject(0).getString("radioID"));
        draft.getJSONArray("configuredRadios").getJSONObject(0).put("radioID","unsupported");assertThrows(IllegalArgumentException.class,()->SettingsValidation.check(SettingsCatalog.find("schedule"),draft,value));
    }
    @Test public void managementCannotDisableTheAppsHttpsTransport() throws Exception {
        JSONObject v=new JSONObject("{\"canManageUsingHTTP\":true,\"canManageUsingHTTPS\":false,\"canManageRemotely\":false}");assertThrows(IllegalArgumentException.class,()->SettingsValidation.check(SettingsCatalog.find("management"),v,v));
    }
    @Test public void lanPoolMustExcludeRouterAndReservationsMustBeUnique() throws Exception {
        JSONObject v=new JSONObject("{\"ipAddress\":\"192.168.1.1\",\"networkPrefixLength\":24,\"hostName\":\"router\",\"isDHCPEnabled\":true,\"dhcpSettings\":{\"leaseMinutes\":1440,\"firstClientIPAddress\":\"192.168.1.1\",\"lastClientIPAddress\":\"192.168.1.200\",\"reservations\":[]}}");
        assertThrows(IllegalArgumentException.class,()->SettingsValidation.check(SettingsCatalog.find("lan"),v,v));
        Json.set(v,"dhcpSettings.firstClientIPAddress","192.168.1.10");SettingsValidation.check(SettingsCatalog.find("lan"),v,v);
        JSONObject reservation=new JSONObject("{\"macAddress\":\"02:00:00:00:00:01\",\"ipAddress\":\"192.168.1.20\",\"description\":\"test\"}");Json.set(v,"dhcpSettings.reservations",new JSONArray().put(reservation).put(Json.copy(reservation)));
        assertThrows(IllegalArgumentException.class,()->SettingsValidation.check(SettingsCatalog.find("lan"),v,v));
    }
    @Test public void wanCannotSelectUnadvertisedModeOrIncompleteStaticAddress() throws Exception {
        JSONObject current=new JSONObject("{\"wanType\":\"DHCP\",\"mtu\":0}");JSONObject draft=new JSONObject("{\"wanType\":\"Static\",\"mtu\":0,\"staticSettings\":{\"ipAddress\":\"10.0.0.2\"}}");
        assertThrows(IllegalArgumentException.class,()->SettingsValidation.check(SettingsCatalog.find("wan"),draft,current));current.put("supportedWANTypes",new JSONArray().put("DHCP").put("Static"));
        assertThrows(IllegalArgumentException.class,()->SettingsValidation.check(SettingsCatalog.find("wan"),draft,current));
        Json.set(draft,"staticSettings.networkPrefixLength",24);Json.set(draft,"staticSettings.gateway","10.0.0.1");Json.set(draft,"staticSettings.dnsServer1","1.1.1.1");SettingsValidation.check(SettingsCatalog.find("wan"),draft,current);
    }
    @Test public void portRangesRejectBackwardsBounds() throws Exception {
        JSONObject value=new JSONObject("{\"rules\":[{\"isEnabled\":true,\"description\":\"test\",\"protocol\":\"TCP\",\"internalServerIPAddress\":\"192.168.1.20\",\"firstExternalPort\":100,\"lastExternalPort\":90}]}");assertThrows(IllegalArgumentException.class,()->SettingsValidation.check(SettingsCatalog.find("range"),value,value));
    }
    @Test public void exportCannotLeakRouterValuesEvenFromNestedArrays() throws Exception {
        JSONObject response=new JSONObject("{\"result\":\"OK\",\"output\":{\"password\":\"secret-password\",\"ssid\":\"my-private-network\",\"devices\":[{\"name\":\"private-device\",\"ipAddress\":\"192.168.1.44\",\"macAddress\":\"AA:BB:CC:DD:EE:FF\",\"isEnabled\":true,\"sharedKey\":\"nested-secret\"}]}}");
        AuditReport report=new AuditReport();report.add("Node 1","devicelist/GetDevices3","Passed",20,response);String text=report.json();
        for(String secret:Arrays.asList("secret-password","my-private-network","private-device","192.168.1.44","AA:BB:CC:DD:EE:FF","nested-secret"))assertFalse(text.contains(secret));assertTrue(text.contains("isEnabled"));assertTrue(text.contains("readOnly"));
    }
    @Test public void actionAllowlistHasNoArbitraryOrTransactionWrite() {
        assertFalse(SettingsCatalog.allowsWrite("core/Transaction"));assertFalse(SettingsCatalog.allowsWrite("core/FactoryReset"));assertFalse(SettingsCatalog.allowsWrite("http://evil/SetRadioSettings3"));assertTrue(SettingsCatalog.allowsWrite("wirelessap/SetRadioSettings3"));
    }
}
