package com.velopcontrol.app;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Standalone local Android client. Navigation and credentials never leave native code. */
public final class ControlActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,JnapClient> sessions=new LinkedHashMap<>();
    private final Map<String,JSONObject> latest=new LinkedHashMap<>();
    private final Map<String,String> pageStatus=new HashMap<>();
    private final Map<String,NodeHealth> health=new HashMap<>();
    private static final class NodeHealth {
        final long uptime,readAt;
        NodeHealth(long uptime){this.uptime=uptime;readAt=System.currentTimeMillis();}
    }
    private static final class MeshRead {
        final Map<String,JSONObject> raw;final Topology topology;final long uptime;final boolean historyFailed;
        MeshRead(Map<String,JSONObject> raw,Topology topology,long uptime,boolean historyFailed){this.raw=raw;this.topology=topology;this.uptime=uptime;this.historyFailed=historyFailed;}
    }
    private NodeOnboarding onboarding;
    private JnapClient onboardingSession;
    private long onboardingStarted;
    private boolean showingOnboarding,onboardingChecking,onboardingPaused;
    private String onboardingMessage="";
    private final Runnable onboardingPoll=this::checkOnboarding;
    private RestartMonitor restart;
    private JnapClient restartSession;
    private boolean restartChecking,showingUpdates;
    private final Runnable restartPoll=this::checkRestart;
    private android.content.SharedPreferences preferences;
    private Ui ui;private LinearLayout page,frame;private TextView status;private ProgressBar progress;
    private JnapClient primary;private Topology topology;private HistoryStore history;private CredentialVault vault;
    private ReleaseInfo availableRelease;private java.io.File downloadedUpdate;private boolean updateBusy;
    private final ExecutorService updateWorker=Executors.newSingleThreadExecutor();
    private final ExecutorService discoveryWorker=Executors.newSingleThreadExecutor();
    private java.util.concurrent.atomic.AtomicBoolean discovery;private boolean autoLocated;
    private EditText loginAddress;private TextView loginDiscovery;
    /** Identifies the tool screen on display; polls stop as soon as the user leaves it. */
    private String screenKey;
    private String selectedHost="",tab="Home",notice="",scope="All devices",nodeFilter="",clientQuery="";
    private boolean busy,resumed,auto,management,bridge,bridgeKnown,validating;
    private volatile boolean cancelled;
    private volatile int generation;private AuditReport audit;private String exportText;
    private Runnable back;
    private static final String[] TABS={"Home","Devices","Mesh","Settings"};
    private static final int[] TAB_ICONS={R.drawable.ic_home,R.drawable.ic_devices,R.drawable.ic_mesh,R.drawable.ic_settings};
    /** Where "‹ Back" returns from a page opened inside a group page; reset whenever a main tab is shown. */
    private Runnable returnTo;
    private Runnable parent(){Runnable r=returnTo;return r!=null?r:this::showTab;}
    private final Runnable poll=()->{if(auto&&resumed&&!busy&&primary!=null&&back==null&&!restartActive()&&onboarding==null)refresh();else schedule();};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        preferences=getSharedPreferences("preferences",MODE_PRIVATE);applyScreenshotPolicy();ui=new Ui(this,preferences.getBoolean("dark",true));history=new HistoryStore(this);vault=new CredentialVault(this);
        try(java.io.InputStream in=getAssets().open("settings.json")){SettingsCatalog.load(in);}catch(Exception e){new AlertDialog.Builder(this).setMessage("Settings catalog could not be loaded").setPositiveButton("Close",(d,w)->finish()).show();return;}
        auto=preferences.getBoolean("auto",false);login("");
        if(preferences.getBoolean("updateChecks",true)&&System.currentTimeMillis()-preferences.getLong("lastUpdateCheck",0)>8L*3600000)checkUpdates(false);
    }
    private ScrollView scroller;private String scrollerTitle;
    private void chrome(String title,boolean navigation){
        int keepScroll=scroller!=null&&title.equals(scrollerTitle)?scroller.getScrollY():0;
        showingUpdates=false;showingOnboarding=false;loginAddress=null;loginDiscovery=null;screenKey=null;
        ui=new Ui(this,preferences.getBoolean("dark",true));frame=ui.column();frame.setBackgroundColor(ui.bg);setContentView(frame);
        frame.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        getWindow().setStatusBarColor(ui.bg);getWindow().setNavigationBarColor(ui.surface);
        getWindow().getDecorView().setSystemUiVisibility(ui.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout header=ui.row();header.setPadding(ui.d(20),ui.d(9),ui.d(16),ui.d(6));
        LinearLayout identity=ui.column();
        if(back!=null){TextView up=ui.text(identity,"‹ Back",14,ui.accent);up.setPadding(0,ui.d(4),ui.d(24),ui.d(2));up.setContentDescription("Back");up.setOnClickListener(v->{Runnable action=back;back=null;if(action!=null)action.run();});}
        else ui.text(identity,"VELOP CONTROL",11,ui.accent);TextView heading=ui.label(title,23,ui.text,true);identity.addView(heading);header.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
        if(navigation&&back==null&&primary!=null){Button b=ui.button("Refresh",false,this::refresh);header.addView(b,new LinearLayout.LayoutParams(-2,ui.d(46)));}frame.addView(header);
        status=ui.label(busy?"Working…":connectionLabel(),12,ui.muted,false);status.setPadding(ui.d(20),0,ui.d(20),ui.d(6));frame.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);progress.setVisibility(busy?View.VISIBLE:View.GONE);frame.addView(progress,new LinearLayout.LayoutParams(-1,ui.d(3)));
        LinearLayout middle=ui.row();middle.setGravity(Gravity.TOP);frame.addView(middle,new LinearLayout.LayoutParams(-1,0,1));
        boolean wide=getResources().getConfiguration().screenWidthDp>=720;
        if(navigation&&wide)middle.addView(navigation(true),new LinearLayout.LayoutParams(ui.d(170),-1));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroller=scroll;scrollerTitle=title;
        if(keepScroll>0)scroll.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener(){public void onGlobalLayout(){scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);scroll.scrollTo(0,keepScroll);}});page=ui.column();page.setPadding(ui.d(20),ui.d(14),ui.d(20),ui.d(28));scroll.addView(page,new ScrollView.LayoutParams(-1,-2));middle.addView(scroll,new LinearLayout.LayoutParams(0,-1,1));
        if(navigation&&!wide)frame.addView(navigation(false),new LinearLayout.LayoutParams(-1,ui.d(66)));
    }
    private View navigation(boolean vertical){
        LinearLayout nav=vertical?ui.column():ui.row();nav.setPadding(ui.d(6),ui.d(4),ui.d(6),ui.d(4));nav.setBackgroundColor(ui.surface);
        for(int i=0;i<TABS.length;i++){String name=TABS[i];boolean on=name.equals(tab)&&back==null;
            LinearLayout item=vertical?ui.row():ui.column();item.setGravity(Gravity.CENTER);item.setBackground(ui.shape(on?ui.bg:ui.surface,14));item.setContentDescription(name+(on?", selected":""));
            item.addView(ui.icon(TAB_ICONS[i],on?ui.accent:ui.muted,vertical?22:24));
            TextView label=ui.label(name,vertical?15:11,on?ui.accent:ui.muted,on);label.setPadding(vertical?ui.d(12):0,vertical?0:ui.d(2),0,0);label.setGravity(Gravity.CENTER);item.addView(label);
            if(vertical){item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(ui.d(14),0,ui.d(8),0);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,ui.d(54));p.bottomMargin=ui.d(4);nav.addView(item,p);}
            else nav.addView(item,new LinearLayout.LayoutParams(0,-1,1));
            View.OnClickListener open=v->{tab=name;back=null;returnTo=null;showTab();schedule();};item.setOnClickListener(open);label.setOnClickListener(open);
        }return nav;
    }
    private String connectionLabel(){if(topology!=null&&topology.demo)return "DEMO · Sample data · No router connected";if(primary==null)return "Local Android app · Stock Linksys firmware";return primary.host()+" · "+(bridge?"Bridge mode":"Local mesh")+(management?" · Controls enabled":" · Read-only");}
    private void setBusy(boolean value,String message){busy=value;if(progress!=null)progress.setVisibility(value?View.VISIBLE:View.GONE);if(status!=null)status.setText(value?message:connectionLabel());}
    private <T> void task(String message,Callable<T> work,Consumer<T> done){task(message,work,done,null);}
    /** As task(), but {@code failed} replaces the default error dialog and keeps automatic refresh running. */
    private <T> void task(String message,Callable<T> work,Consumer<T> done,Consumer<Exception> failed){
        if(busy){toast("Wait for the current operation to finish");return;}main.removeCallbacks(poll);setBusy(true,message);int ticket=generation;
        worker.execute(()->{try{if(ticket!=generation)return;T result=work.call();main.post(()->{if(ticket!=generation||isDestroyed()){if(result instanceof AutoCloseable)try{((AutoCloseable)result).close();}catch(Exception ignored){}return;}setBusy(false,"");done.accept(result);schedule();});}
            catch(Exception error){main.post(()->{if(ticket!=generation||isDestroyed())return;setBusy(false,"");if(failed!=null){failed.accept(error);schedule();return;}auto=false;if(topology!=null&&!topology.demo&&back==null){notice="The last operation failed. Automatic refresh is paused; the previous mesh snapshot is retained.";showTab();}message("Unable to complete",safeMessage(error));schedule();});}});
    }
    private static String safeMessage(Exception e){
        if(e instanceof Authentication.Failure)return e.getMessage();
        if(e instanceof SecurityException)return "The operation was blocked for security or the session was closed. Reconnect and verify the selected node. No automatic retry was made.";
        if(e instanceof javax.net.ssl.SSLException)return "The HTTPS certificate could not be verified. Disconnect and review the certificate on your trusted local network.";
        if(e instanceof JSONException)return "This firmware returned an unexpected response. Run validation and export the log.";
        if(e instanceof IllegalArgumentException||e instanceof IllegalStateException||e instanceof SettingsRepository.UncertainChangeException)return e.getMessage()==null?"Reload and try again.":e.getMessage();
        return "The router could not be reached or the reply was incomplete. Check Wi-Fi and the node’s IP address. A submitted change may have reached the router; reload before trying it again.";
    }
    private void login(String info){
        back=null;chrome("Your mesh, clearly.",false);LinearLayout intro=ui.card();ui.title(intro,"A clear view of home.");ui.text(intro,"See your nodes, find connected devices and manage supported settings from one local app.",16,ui.muted);page.addView(intro);
        LinearLayout card=ui.card();ui.heading(card,"Connect to your primary Velop");ui.text(card,"Use its local IP address and router admin password. In bridge mode, this is the Velop’s assigned IP address.",14,ui.muted);
        PrimaryLocator.LocalNetwork network=localNetwork();String lastHost=preferences.getString("lastHost","");
        EditText address=ui.input("Primary Velop IP",!lastHost.isEmpty()?lastHost:!network.gateway.isEmpty()?network.gateway:"192.168.1.1",false);address.setInputType(android.text.InputType.TYPE_CLASS_PHONE);card.addView(address);
        loginDiscovery=ui.text(card,lastHost.isEmpty()&&!network.gateway.isEmpty()?"Suggested from your Wi-Fi gateway. In bridge mode, enter the Velop’s own address instead.":"",12,ui.muted);loginDiscovery.setVisibility(loginDiscovery.length()==0?View.GONE:View.VISIBLE);
        EditText password=ui.input("Local administrator password","",true);fillRemembered(address.getText().toString(),password);card.addView(password);
        address.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence t,int a,int c,int f){}public void afterTextChanged(Editable e){fillRemembered(e.toString(),password);}public void onTextChanged(CharSequence t,int a,int b,int c){}});
        Switch remember=ui.toggle("Remember passwords on this device",preferences.getBoolean("remember",true));card.addView(remember);remember.setOnCheckedChangeListener((button,on)->{preferences.edit().putBoolean("remember",on).apply();if(!on)vault.forgetAll();});
        ui.action(card,"Connect securely",true,()->connect(address,password,true));ui.action(card,"Find my primary Velop",false,()->locatePrimary(true));ui.text(card,"Remembered passwords are encrypted on this device. Router access stays local; app update checks use GitHub.",12,ui.muted);page.addView(card);
        if(!info.isEmpty())ui.note(page,info);
        ui.action(page,"Explore demo",false,()->{if(busy)return;topology=Topology.demo();tab="Home";showTab();});
        ui.action(page,"App updates",false,this::updates);ui.text(page,"Android "+BuildConfig.VERSION_NAME+" · Local router access",12,ui.muted);
        loginAddress=address;
        if(discovery!=null)discoveryStatus("Finding your primary Velop…");
        else if(!autoLocated&&rememberedPrimary().valid()){autoLocated=true;locatePrimary(false);}
    }
    private PrimaryLocator.Remembered rememberedPrimary(){return new PrimaryLocator.Remembered(preferences.getString("lastHost",""),preferences.getString("primaryPin",""),preferences.getString("primarySerial",""));}
    private void discoveryStatus(String text){if(loginDiscovery==null)return;loginDiscovery.setText(text);loginDiscovery.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);}
    /** Wi-Fi or Ethernet only: a VPN or mobile-data gateway is never suggested or scanned. */
    @SuppressWarnings("deprecation") private PrimaryLocator.LocalNetwork localNetwork(){
        try{android.net.ConnectivityManager manager=getSystemService(android.net.ConnectivityManager.class);if(manager==null)return PrimaryLocator.LocalNetwork.NONE;
            for(android.net.Network network:manager.getAllNetworks()){
                android.net.NetworkCapabilities caps=manager.getNetworkCapabilities(network);
                if(caps==null||!(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)))continue;
                android.net.LinkProperties link=manager.getLinkProperties(network);if(link==null)continue;
                String gateway="",address="";int prefix=0;
                for(android.net.RouteInfo route:link.getRoutes())if(route.isDefaultRoute()&&route.getGateway() instanceof java.net.Inet4Address)gateway=route.getGateway().getHostAddress();
                for(android.net.LinkAddress a:link.getLinkAddresses())if(a.getAddress() instanceof java.net.Inet4Address){address=a.getAddress().getHostAddress();prefix=a.getPrefixLength();}
                if(!address.isEmpty())return new PrimaryLocator.LocalNetwork(gateway,address,prefix);
            }
        }catch(Exception ignored){}
        return PrimaryLocator.LocalNetwork.NONE;
    }
    /** Certificate-only search for the remembered primary. No passwords are sent during discovery. */
    private void locatePrimary(boolean interactive){
        if(discovery!=null){if(interactive)toast("Already searching for your primary Velop");return;}
        PrimaryLocator.Remembered remembered=rememberedPrimary();PrimaryLocator.LocalNetwork network=localNetwork();
        if(!remembered.valid()){
            if(!network.gateway.isEmpty()&&loginAddress!=null){loginAddress.setText(network.gateway);discoveryStatus("Suggested from your Wi-Fi gateway. In bridge mode, enter the Velop’s own address instead. After one successful connection, the app finds this Velop automatically.");}
            else discoveryStatus("Connect once to your primary Velop. After that, the app finds it automatically, even if its address changes.");
            return;
        }
        PrimaryLocator.Plan plan=PrimaryLocator.plan(remembered.host,network);
        java.util.concurrent.atomic.AtomicBoolean cancel=new java.util.concurrent.atomic.AtomicBoolean();discovery=cancel;
        String typed=loginAddress==null?"":loginAddress.getText().toString();discoveryStatus("Finding your primary Velop…");
        discoveryWorker.execute(()->{
            PrimaryLocator.Match found=null;try{found=PrimaryLocator.locate(JnapClient.LOCATOR_PROBE,remembered,plan,32,30000,cancel);}catch(Exception ignored){}
            final PrimaryLocator.Match match=found;
            main.post(()->{if(discovery==cancel)discovery=null;if(cancel.get()||isDestroyed())return;primaryLocated(remembered,match,typed,plan.size());});
        });
    }
    private void primaryLocated(PrimaryLocator.Remembered remembered,PrimaryLocator.Match match,String typed,int searched){
        if(match==null){discoveryStatus("Your primary Velop was not found ("+searched+" local addresses checked). Make sure you are on your home Wi-Fi, or enter its address.");return;}
        String host=match.host;boolean moved=!host.equals(remembered.host);
        if(moved){
            // Same serial: carry the last approved pin and saved password to the new address. A changed
            // certificate still goes through the explicit "certificate changed" approval on connect.
            SharedPreferences.Editor edit=preferences.edit().putString("pin:"+host,remembered.fingerprint).putString("lastHost",host);
            if(!remembered.host.isEmpty()&&remembered.fingerprint.equals(preferences.getString("pin:"+remembered.host,"")))edit.remove("pin:"+remembered.host);
            edit.apply();
            if(preferences.getBoolean("remember",true))try{vault.move(remembered.host,host,remembered.fingerprint);}catch(Exception e){toast("Saved password could not be moved; enter it once");}
        }
        if(loginAddress!=null&&loginAddress.getText().toString().equals(typed))loginAddress.setText(host);
        String where="Primary Velop found at "+host+(moved?" (moved from "+remembered.host+")":"")+".";
        discoveryStatus(match.certificateChanged?where+" Its serial number matches, but its certificate is new, which is expected after a Linksys firmware update. Review and approve it when you connect.":moved?where+" Its approved certificate and serial number match.":where);
    }
    private void connect(EditText address,EditText input,boolean asPrimary){connect(address,input,asPrimary,null);}
    private void connect(EditText address,EditText input,boolean asPrimary,Consumer<Exception> failed){
        if(busy)return;if(discovery!=null){discovery.set(true);discovery=null;discoveryStatus("");}final String host;try{host=ReadPolicy.privateAddress(address.getText().toString());}catch(Exception e){address.setError(e.getMessage());return;}
        if(input.length()==0){input.setError("Enter this node’s local administrator password");return;}
        char[] secret=new char[input.length()];input.getText().getChars(0,input.length(),secret,0);input.setText("");
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        int ticket=generation;setBusy(true,"Inspecting local HTTPS certificate…");worker.execute(()->{
            try{JnapClient.Identity identity=JnapClient.inspect(host);
                // For a changed certificate, an anonymous identity read (no credentials) helps the user decide.
                String knownPin=preferences.getString("pin:"+host,""),primarySerial=preferences.getString("primarySerial","");Boolean serialMatches=null;
                if(!knownPin.isEmpty()&&!knownPin.equals(identity.formatted())&&!primarySerial.isEmpty()&&host.equals(preferences.getString("lastHost","")))
                    try{serialMatches=primarySerial.equals(JnapClient.LOCATOR_PROBE.serial(host,identity.formatted()));}catch(Exception ignored){}
                final Boolean sameSerial=serialMatches;main.post(()->{
                if(ticket!=generation||isDestroyed()){Arrays.fill(secret,'\0');return;}setBusy(false,"");
                String known=preferences.getString("pin:"+host,"");Runnable establish=()->{
                    try{JnapClient next=new JnapClient(host,secret,identity.fingerprint);Arrays.fill(secret,'\0');java.util.concurrent.atomic.AtomicReference<String> serial=new java.util.concurrent.atomic.AtomicReference<>("");
                        task("Checking local administrator access…",()->{try{JSONObject identityReply=next.read(ReadPolicy.INFO,false);SettingsRepository.requireOK(identityReply);next.authenticate(identityReply.getJSONObject("output"));serial.set(identityReply.getJSONObject("output").optString("serialNumber"));
                            if(asPrimary)SettingsRepository.requireOK(next.read(ReadPolicy.DEVICES,true));
                            if(preferences.getBoolean("remember",true)){char[] remembered=next.passwordCopy();try{vault.save(host,identity.formatted(),remembered);}catch(Exception e){main.post(()->toast("Connected; password could not be saved on this device"));}finally{Arrays.fill(remembered,'\0');}}return next;}catch(Exception e){next.close();throw e;}},accepted->{
                            JnapClient old=sessions.put(host,accepted);if(old!=null)old.close();selectedHost=host;
                            preferences.edit().putString("pin:"+host,identity.formatted()).apply();
                            if(asPrimary||primary!=null&&host.equals(primary.host())){primary=accepted;preferences.edit().putString("lastHost",host).putString("primaryPin",identity.formatted()).putString("primarySerial",serial.get()).apply();topology=null;latest.clear();tab="Home";back=null;refresh();}
                            else{rememberNode(host,identity.formatted(),serial.get());Runnable then=nodeConnectedThen;nodeConnectedThen=null;
                                if(then!=null)then.run();else{notice="Connected directly to "+host+". Other node sessions are unchanged.";back=null;tab="Mesh";showTab();}}
                        },failed);
                    }catch(Exception e){Arrays.fill(secret,'\0');message("Connection failed",safeMessage(e));}
                };
                // Nodes on one firmware build share the certificate already approved for the primary; any other certificate still asks.
                boolean sameAsPrimary=known.isEmpty()&&!asPrimary&&identity.formatted().equals(preferences.getString("primaryPin",""));
                if(known.equals(identity.formatted())||sameAsPrimary)establish.run();else ui.dialog(known.isEmpty()?"Trust this local router?":"Router certificate changed")
                    .setMessage("Address: "+host+"\nCertificate: "+identity.subject+"\n\nSHA-256\n"+identity.formatted()+"\n\n"+PrimaryLocator.trustNotice(!known.isEmpty(),PrimaryLocator.linksys(identity.subject),sameSerial))
                    .setNegativeButton("Cancel",(d,w)->Arrays.fill(secret,'\0')).setPositiveButton("Trust & connect",(d,w)->establish.run()).setOnCancelListener(d->Arrays.fill(secret,'\0')).show();
            });}catch(Exception e){Arrays.fill(secret,'\0');main.post(()->{if(ticket==generation&&!isDestroyed()){setBusy(false,"");message("Could not connect",safeMessage(e));}});}
        });
    }
    /** Child nodes are remembered by inventory ID, so a new DHCP address (common in bridge mode) keeps their saved login. */
    private void rememberNode(String host,String fingerprint,String serial){
        if(topology==null||serial==null||serial.isEmpty())return;
        for(Topology.Node n:topology.nodes)if(host.equals(n.ip)&&!n.primary)preferences.edit().putString("node:"+n.id+":host",host).putString("node:"+n.id+":serial",serial).putString("node:"+n.id+":pin",fingerprint).apply();
    }
    private void connectNode(Topology.Node node){
        if(node.ip==null||node.ip.isEmpty()){message("Address unavailable","Refresh the inventory to find this node’s address.");return;}
        String old=preferences.getString("node:"+node.id+":host",""),serial=preferences.getString("node:"+node.id+":serial",""),pin=preferences.getString("node:"+node.id+":pin","");
        if(!old.isEmpty()&&!old.equals(node.ip)&&!serial.isEmpty()&&!pin.isEmpty()&&!busy){
            // Same anonymous identity check as primary discovery: only a matching serial carries the saved login over.
            String host=node.ip;int ticket=generation;setBusy(true,"Checking "+node.name+" at its new address…");
            worker.execute(()->{boolean same=false;
                try{PrimaryLocator.Certificate c=JnapClient.LOCATOR_PROBE.certificate(host,3000);if(pin.equals(c.fingerprint)||PrimaryLocator.linksys(c.subject))same=serial.equals(JnapClient.LOCATOR_PROBE.serial(host,c.fingerprint));}catch(Exception ignored){}
                final boolean matched=same;
                main.post(()->{if(ticket!=generation||isDestroyed())return;setBusy(false,"");
                    if(matched){SharedPreferences.Editor e=preferences.edit().putString("pin:"+host,pin).putString("node:"+node.id+":host",host);if(pin.equals(preferences.getString("pin:"+old,"")))e.remove("pin:"+old);e.apply();
                        if(preferences.getBoolean("remember",true))try{vault.move(old,host,pin);}catch(Exception ignored){}
                        toast(node.name+" moved from "+old+" to "+host+"; saved login carried over");}
                    else preferences.edit().remove("node:"+node.id+":host").apply();
                    connectNodeAuto(node);});});
            return;
        }
        connectNodeAuto(node);
    }
    /** Nodes whose saved or primary password was rejected this session: never auto-tried again. */
    private final Set<String> autoRejected=new HashSet<>();
    private Runnable nodeConnectedThen;
    /**
     * The mesh syncs the primary's admin password to every node (config_sync::admin_password_synchronized), so try the node's
     * saved password, else the primary's, exactly once. The node's lockout status is checked first by JnapClient.authenticate.
     * A rejection falls back to asking, and is never retried automatically.
     */
    private void connectNodeAuto(Topology.Node node){
        if(primary==null||autoRejected.contains(node.ip)){connectNodeDialog(node,null);return;}
        char[] saved=vault.load(node.ip,preferences.getString("pin:"+node.ip,""));boolean useSaved=saved.length>0;
        char[] secret=useSaved?saved:primary.passwordCopy();
        EditText address=ui.input("",node.ip,false),input=ui.input("",new String(secret),true);Arrays.fill(secret,'\0');
        connect(address,input,false,error->{
            Runnable fromPicker=nodeConnectedThen;nodeConnectedThen=null;if(fromPicker!=null&&back==null&&tab.equals("Settings"))showTab();
            if(error instanceof Authentication.Failure){String code=((Authentication.Failure)error).code;autoRejected.add(node.ip);
                if(code.equals("ErrorAdminAccountLocked")||code.equals("ErrorPasswordCheckDelayed")){message("Cannot connect to "+node.name,error.getMessage());return;}
                connectNodeDialog(node,node.name+" did not accept "+(useSaved?"its saved password":"the primary's password")+". It may have a different password if mesh syncing failed. Enter its password; the app will not retry automatically.");}
            else message("Could not connect to "+node.name,safeMessage(error));
        });
    }
    private void connectNodeDialog(Topology.Node node,String reason){
        LinearLayout fields=ui.column();fields.setPadding(ui.d(22),ui.d(8),ui.d(22),0);if(reason!=null)ui.text(fields,reason,14,ui.warning);ui.text(fields,"Use the local router admin password. Saved credentials are specific to this node and certificate. A failed connection leaves the primary session connected.",14,ui.muted);
        EditText address=ui.input("Node IP",node.ip,false);address.setEnabled(false);fields.addView(address);EditText password=ui.input("This node’s local password","",true);fillRemembered(node.ip,password);fields.addView(password);
        AlertDialog dialog=ui.dialog("Connect to "+node.name).setView(fields).setNegativeButton("Cancel",null).setPositiveButton("Connect",null).create();dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(b->{if(password.length()==0){password.setError("Enter the local password");return;}dialog.dismiss();connect(address,password,false);}));dialog.show();
    }
    private void refresh(){
        if(primary==null){if(topology!=null&&topology.demo){toast("Demo uses sample data");return;}login("");return;}
        if(back!=null){toast("Return to a main tab to refresh the mesh");return;}
        JnapClient session=primary;
        task("Reading node inventory and backhaul…",()->{
            Map<String,JSONObject> raw=new LinkedHashMap<>();raw.put(ReadPolicy.INFO,session.read(ReadPolicy.INFO,false));
            JSONObject inventory=session.read(ReadPolicy.DEVICES,true);SettingsRepository.requireOK(inventory);raw.put(ReadPolicy.DEVICES,inventory);
            for(String action:new String[]{ReadPolicy.BACKHAUL,ReadPolicy.NODE_CONNECTIONS,ReadPolicy.CONNECTIONS,ReadPolicy.MODE,"router/GetWANSettings5"}){
                try{raw.put(action,session.read(action,true));}catch(SecurityException e){throw e;}catch(Exception e){raw.put(action,new JSONObject().put("result","TransportUnavailable"));}
            }
            long uptime=-1;try{uptime=RestartMonitor.uptime(session.read("diagnostics/GetSystemStats",true));}catch(SecurityException e){throw e;}catch(Exception ignored){}
            Topology result=Topology.parse(raw);boolean historyFailed=false;
            if(preferences.getBoolean("history",false))try{history.record(result);}catch(Exception e){historyFailed=true;}
            return new MeshRead(raw,result,uptime,historyFailed);
        },read->{latest.clear();latest.putAll(read.raw);topology=read.topology;if(read.uptime>=0)health.put(session.host(),new NodeHealth(read.uptime));JSONObject wan=Topology.output(read.raw,"router/GetWANSettings5");bridgeKnown=wan.has("wanType");bridge=wan.optString("wanType").equals("Bridge");if(selectedHost.isEmpty())selectedHost=primary.host();notice=read.historyFailed?"Mesh refreshed, but local history could not be saved.":"";showTab();});
    }
    private void showTab(){
        returnTo=null;
        if(topology==null){if(primary!=null){chrome(tab,true);ui.note(page,"Read the mesh inventory to populate this view.");ui.action(page,"Read mesh",true,this::refresh);}else login("");return;}
        chrome(tab,true);if(!notice.isEmpty())ui.note(page,notice);
        restartCard();onboardingCard();
        switch(tab){case "Mesh":mesh();break;case "Devices":devices();break;case "Settings":settings();break;default:home();}
    }
    private String snapshotTime(){return topology==null||topology.demo?"Sample data":"Updated "+DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(topology.observedAt));}
    private void home(){
        int online=(int)topology.clients.stream().filter(c->c.seen).count(),active=(int)topology.nodes.stream().filter(n->n.seen).count(),total=topology.nodes.size();
        LinearLayout hero=ui.card();
        ui.text(hero,topology.demo?"SAMPLE NETWORK":(bridgeKnown?(bridge?"BRIDGE MODE":"ROUTER MODE"):"YOUR MESH")+" · "+snapshotTime().toUpperCase(Locale.ROOT),11,ui.accent);
        hero.addView(ui.label(active==total?(total==1?"Your node is online":"All "+total+" nodes are online"):active+" of "+total+" nodes reporting",24,active==total?ui.text:ui.warning,true));
        ui.text(hero,online+" devices connected · "+topology.clients.size()+" known",15,ui.muted);
        ui.text(hero,management?"Management controls are on":"Read-only · turn on management controls in Settings to make changes",13,management?ui.accent:ui.muted);
        page.addView(hero);
        List<String> issues=new ArrayList<>();
        int weak=0;for(Topology.Node n:topology.nodes){try{if(Double.parseDouble(n.rssi)<=-75)weak++;}catch(Exception ignored){}}
        if(weak>0)issues.add(weak+" node"+(weak>1?"s have":" has")+" a weak wireless link to its parent. Check placement under Mesh.");
        int missing=(int)topology.nodes.stream().filter(n->!n.wirelessGroupReported).count();if(missing>0&&!topology.demo)issues.add(missing+" node(s) did not report their wireless clients in this snapshot.");
        if(total-active>0)issues.add((total-active)+" node(s) are not reporting. They may be off or out of range.");
        if(!issues.isEmpty()){LinearLayout card=ui.card();ui.heading(card,"Needs attention");for(String i:issues)ui.text(card,i,14,ui.warning);ui.action(card,"Open Mesh",false,()->{tab="Mesh";showTab();});page.addView(card);}
        List<View> tiles=new ArrayList<>();
        tiles.add(ui.tile("Wi-Fi","Name, password, bands",R.drawable.ic_wifi,()->openSettings(SettingsCatalog.find("wifi"))));
        tiles.add(ui.tile("Guest Wi-Fi","Access for visitors",R.drawable.ic_guest,()->openSettings(SettingsCatalog.find("guest"))));
        tiles.add(ui.tile("Devices",online+" connected",R.drawable.ic_devices,()->{tab="Devices";showTab();}));
        tiles.add(ui.tile("Speed check","Test your internet",R.drawable.ic_speed,this::speedCheck));
        tiles.add(ui.tile("Channel finder","Reduce interference",R.drawable.ic_channel,this::channelFinder));
        tiles.add(ui.tile("Drop monitor","Catch connection drops",R.drawable.ic_monitor,this::monitorScreen));
        tiles.add(ui.tile("Restart network","Restart every node",R.drawable.ic_restart,this::restartNetwork));
        tiles.add(ui.tile("Add a node","Wired or wireless",R.drawable.ic_mesh,this::onboardingScreen));
        ui.grid(page,tiles);
    }
    private void mesh(){
        if(!topology.demo)ui.text(page,snapshotTime(),12,ui.muted);
        LinearLayout graph=ui.card();ui.heading(graph,"Mesh map");graph.addView(new TopologyView(this,topology,this::node),new LinearLayout.LayoutParams(-1,ui.d(Math.max(220,topology.nodes.size()*86))));
        ui.text(graph,"Line into each node: blue dashed = wired, green = wireless. Parents come from the mesh's own backhaul report.",12,ui.muted);page.addView(graph);
        LinearLayout nodes=ui.group(page,"Nodes");
        for(Topology.Node n:topology.nodes){
            String sub;
            if(n.primary)sub="Primary · "+Ui.known(n.ip);
            else{String via=n.parentId==null?"parent not reported":"via "+topology.nodeName(n.parentId);
                sub=("Wireless".equalsIgnoreCase(n.link)?"Wireless"+(n.rssi==null||n.rssi.isEmpty()?"":" · "+n.rssi+" dBm"):"Wired".equalsIgnoreCase(n.link)?"Wired":Ui.known(n.link))+" · "+via;}
            if(!n.seen)sub="Not reporting · "+sub;
            ui.item(nodes,n.name,sub+" · "+n.clients+" devices",R.drawable.ic_mesh,()->node(n));
        }
        LinearLayout tools=ui.group(page,"Mesh tools");
        ui.item(tools,"Add mesh nodes","Wired or wireless setup",R.drawable.ic_mesh,this::onboardingScreen);
        ui.item(tools,"Neighbor signals & steering","What each node hears, and why it uses its parent",R.drawable.ic_wifi,this::neighborSignals);
        ui.item(tools,"Channel finder","Pick the clearest Wi-Fi channels",R.drawable.ic_channel,this::channelFinder);
        ui.item(tools,"Restart whole network","Restarts every node",R.drawable.ic_restart,this::restartNetwork);
        ui.note(page,"Manual parent assignment is not available on stock Intelligent Mesh firmware; steering and backhaul choice stay automatic.");
    }
    private void detail(String title,Runnable previous){back=previous;chrome(title,true);}
    private void node(Topology.Node n){
        detail(n.name,parent());LinearLayout card=ui.card();ui.heading(card,"Node details");key(card,"Status",n.seen?"Reported in snapshot":"Not currently reported");key(card,"Address",n.ip);key(card,"Model",n.model);key(card,"Firmware",n.firmware);key(card,"Connection",n.primary?"Primary mesh node":n.link);if(!n.primary){key(card,"Parent",n.parentId==null?n.parentIp:topology.nodeName(n.parentId));key(card,"Parent source",n.parentId==null?"Not reported":n.parentSource);}key(card,"Backhaul signal",n.rssi==null||n.rssi.isEmpty()?"Not reported":n.rssi+" dBm");key(card,"Wireless backhaul radio",Topology.backhaulRadio(n));key(card,"Reported wireless link rate",n.linkRate.isEmpty()?"Not available from firmware":n.linkRate+" Mbps");if(!n.linkRateSource.isEmpty()){key(card,"Link-rate source",n.linkRateSource);key(card,"Link-rate report time",measurementTime(n.linkRateTimestamp));}key(card,"Last throughput measurement",measurementTime(n.backhaulTimestamp));key(card,"Child → parent throughput",hopValue(n));key(card,"Assigned client reports",n.clients+(n.wirelessGroupReported?"":" (central group missing)"));page.addView(card);
        ui.action(page,"View devices on this node",true,()->{nodeFilter=n.id;scope="All devices";tab="Devices";back=null;showTab();});
        NodeHealth observation=health.get(n.ip);
        key(card,"Uptime at last direct read",observation==null?"Not read yet":formatUptime(observation.uptime));
        if(observation!=null)key(card,"Status read",DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(observation.readAt)));
        if(!topology.demo){
            JnapClient direct=sessions.get(n.ip);if(direct==null)ui.action(page,"Connect directly to this node",false,()->connectNode(n));
            else{ui.action(page,"Read current uptime",false,()->task("Reading node uptime…",()->{JSONObject r=direct.read("diagnostics/GetSystemStats",true);SettingsRepository.requireOK(r);return RestartMonitor.uptime(r);},uptime->{if(uptime>=0)health.put(n.ip,new NodeHealth(uptime));node(n);if(uptime<0)message("Uptime unavailable","This firmware did not return a valid uptime value.");}));ui.action(page,"Reconnect / replace password",false,()->connectNodeDialog(n,null));ui.action(page,"Settings for this node",false,()->{selectedHost=n.ip;tab="Settings";back=null;showTab();});ui.action(page,"Read node diagnostics",false,()->nodeDiagnostics(n,direct));ui.action(page,n.primary?"Restart whole network…":"Restart this node…",false,()->reboot(n,direct));}
            ui.action(page,"Rename node…",false,()->rename(n));
            if(direct!=null&&"Wired".equalsIgnoreCase(n.link))ui.action(page,"Inspect wired neighbors (experimental)",false,()->wiredNeighbors(n,direct));
        }
        ui.note(page,"Manual parent assignment is unavailable on the confirmed stock Intelligent Mesh interface. Automatic steering and backhaul selection remain under firmware control.");
    }
    private void devices(){
        EditText search=ui.input("Search name, IP, MAC or access point",clientQuery,false);page.addView(search);LinearLayout filters=ui.row();Spinner state=new Spinner(ui.dialogContext());state.setAdapter(new ArrayAdapter<>(ui.dialogContext(),android.R.layout.simple_spinner_dropdown_item,new String[]{"All devices","Reported connected","No connection report","Wireless","Wired"}));
        state.setSelection(Arrays.asList("All devices","Reported connected","No connection report","Wireless","Wired").indexOf(scope));filters.addView(state,new LinearLayout.LayoutParams(0,ui.d(50),1));
        List<String> names=new ArrayList<>(),ids=new ArrayList<>();names.add("All nodes");ids.add("");for(Topology.Node n:topology.nodes){names.add(n.name);ids.add(n.id);}Spinner accessPoint=new Spinner(ui.dialogContext());accessPoint.setAdapter(new ArrayAdapter<>(ui.dialogContext(),android.R.layout.simple_spinner_dropdown_item,names));accessPoint.setSelection(Math.max(0,ids.indexOf(nodeFilter)));filters.addView(accessPoint,new LinearLayout.LayoutParams(0,ui.d(50),1));page.addView(filters);
        TextView count=ui.text(page,"",12,ui.muted);LinearLayout list=ui.column();page.addView(list);LinearLayout rows=ui.group(list,null);
        List<Topology.Client> sorted=new ArrayList<>(topology.clients);sorted.sort(Comparator.comparing((Topology.Client c)->!c.seen).thenComparing(c->c.name.toLowerCase(Locale.ROOT)));
        Runnable fill=()->{rows.removeAllViews();for(int i=list.getChildCount()-1;i>0;i--)list.removeViewAt(i);clientQuery=search.getText().toString();String q=clientQuery.toLowerCase(Locale.ROOT);scope=String.valueOf(state.getSelectedItem());nodeFilter=ids.get(accessPoint.getSelectedItemPosition());int shown=0;
            for(Topology.Client c:sorted){String text=c.name+" "+c.ip+" "+c.mac+" "+topology.nodeName(c.nodeId);if(!text.toLowerCase(Locale.ROOT).contains(q)||!nodeFilter.isEmpty()&&!nodeFilter.equals(c.nodeId))continue;
                if(scope.equals("Reported connected")&&!c.seen||scope.equals("No connection report")&&c.seen||scope.equals("Wireless")&&!"Wireless".equalsIgnoreCase(c.type)||scope.equals("Wired")&&!"Wired".equalsIgnoreCase(c.type))continue;
                String sub=(c.seen?topology.nodeName(c.nodeId):"Offline")+" · "+Ui.known(c.ip)+("Wired".equalsIgnoreCase(c.type)?" · Wired":c.rssi.isEmpty()?"":" · "+c.rssi+" dBm");
                ui.item(rows,c.name,sub,"Wired".equalsIgnoreCase(c.type)?R.drawable.ic_lan:R.drawable.ic_devices,()->device(c));shown++;
            }count.setText(shown+" of "+topology.clients.size()+" known devices"+(topology.demo?"":" · "+snapshotTime()));rows.setVisibility(shown==0?View.GONE:View.VISIBLE);if(shown==0)ui.note(list,"No devices match these filters.");
        };
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){fill.run();}});
        AdapterView.OnItemSelectedListener listener=new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){fill.run();}};state.setOnItemSelectedListener(listener);accessPoint.setOnItemSelectedListener(listener);fill.run();
    }
    private void device(Topology.Client c){
        detail(c.name,parent());LinearLayout card=ui.card();key(card,"IP address",c.ip);key(card,"MAC address",c.mac);key(card,"Access point",topology.nodeName(c.nodeId));key(card,"Connection",c.type);key(card,"Status",c.seen?"Reported connected":"No current connection report");key(card,"Signal",c.rssi==null||c.rssi.isEmpty()?"Not reported":c.rssi+" dBm");key(card,"Negotiated rate",c.rate==null||c.rate.isEmpty()?"Not reported":c.rate+" Mbps");key(card,"Router observation time",c.timestamp);page.addView(card);
        if(preferences.getBoolean("history",false)&&!topology.demo)ui.action(page,"Connection history",false,()->showHistory(c.mac));
        if(!c.seen&&!topology.demo&&!c.id.isEmpty())ui.action(page,"Remove from device list…",false,()->deleteDevice(c));ui.note(page,"Signal, rate and association are last reported values. Stock firmware does not provide confirmed per-device internet usage counters here.");
    }
    private JnapClient selected(){return sessions.get(selectedHost);}
    private static final String[][] SETTING_GROUP_ICONS={{"Wi-Fi","wifi"},{"Internet","globe"},{"Local network","lan"},{"Security","shield"},{"Parental controls & priority","family"},{"Administration","admin"},{"Storage","storage"},{"Mesh","mesh"}};
    private int groupIcon(String group){
        switch(group){case "Wi-Fi":return R.drawable.ic_wifi;case "Internet":return R.drawable.ic_globe;case "Local network":return R.drawable.ic_lan;case "Security":return R.drawable.ic_shield;
            case "Parental controls & priority":return R.drawable.ic_family;case "Administration":return R.drawable.ic_admin;case "Storage":return R.drawable.ic_storage;default:return R.drawable.ic_mesh;}
    }
    private String specStatus(SettingsCatalog.Spec spec){
        if(spec.routerOnly&&bridge)return "Managed by your main router in bridge mode";
        if(spec.id.equals("onboarding"))return "Wired and wireless setup wizard";
        if(spec.read.isEmpty())return "Not available on stock firmware";
        String state=pageStatus.get(selectedHost+":"+spec.id);if(state!=null)return state;
        return spec.editable()?"":"View only";
    }
    private void settings(){
        LinearLayout controls=ui.card();Switch toggle=ui.toggle("Management controls",management);controls.addView(toggle);
        ui.text(controls,management?"On: you can review and apply changes. Turns off when you disconnect.":"Off: settings are view-only. Turn on to change settings; every change still needs a review.",13,ui.muted);
        toggle.setOnCheckedChangeListener((b,on)->{if(on){toggle.setChecked(false);ui.dialog("Turn on management controls?").setMessage("The app can then send supported configuration commands. Writes have not been tested on your hardware. Every change requires its own review. Controls turn off when you disconnect.").setNegativeButton("Keep read-only",null).setPositiveButton("Turn on",(d,w)->{management=true;showTab();}).show();}else{management=false;showTab();}});
        page.addView(controls);
        if(!topology.demo&&(sessions.size()>1||topology.nodes.size()>1))targetPicker();else if(!topology.demo&&selected()!=null)ui.text(page,"Settings apply to "+targetName(selectedHost),12,ui.muted);
        EditText search=ui.input("Search settings","",false);page.addView(search);LinearLayout list=ui.column();page.addView(list);
        Runnable fill=()->{list.removeAllViews();String q=search.getText().toString().trim().toLowerCase(Locale.ROOT);
            if(q.isEmpty()){
                LinearLayout groups=ui.group(list,"Router settings");Map<String,List<String>> byGroup=new LinkedHashMap<>();
                for(SettingsCatalog.Spec spec:SettingsCatalog.ALL)byGroup.computeIfAbsent(spec.group,k->new ArrayList<>()).add(spec.title);
                for(Map.Entry<String,List<String>> e:byGroup.entrySet()){List<String> t=e.getValue();String sub=String.join(", ",t.subList(0,Math.min(3,t.size())))+(t.size()>3?" and "+(t.size()-3)+" more":"");ui.item(groups,e.getKey(),sub,groupIcon(e.getKey()),()->settingsGroup(e.getKey()));}
                LinearLayout more=ui.group(list,"More");
                ui.item(more,"Tools & diagnostics","Speed check, logs, validation, ping, firmware",R.drawable.ic_tools,this::toolsPage);
                ui.item(more,"App preferences","Appearance, refresh, history, saved passwords",R.drawable.ic_settings,this::preferencesPage);
                ui.item(more,"About & updates","Version "+BuildConfig.VERSION_NAME+", app updates, disconnect",R.drawable.ic_info,this::aboutPage);
            }else{
                LinearLayout results=ui.group(list,"Results");int n=0;
                for(SettingsCatalog.Spec spec:SettingsCatalog.ALL){if(!(spec.title+" "+spec.group+" "+spec.note).toLowerCase(Locale.ROOT).contains(q))continue;String st=specStatus(spec);ui.item(results,spec.title,spec.group+(st.isEmpty()?"":" · "+st),groupIcon(spec.group),()->openSettings(spec));n++;}
                if(n==0){results.setVisibility(View.GONE);ui.note(list,"No settings match \""+q+"\".");}
            }
        };fill.run();search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){fill.run();}});
    }
    private String targetName(String host){for(Topology.Node n:topology.nodes)if(host.equals(n.ip))return n.name+" · "+host;return host;}
    private void settingsGroup(String group){
        detail(group,()->{returnTo=null;showTab();});returnTo=()->settingsGroup(group);
        LinearLayout list=ui.group(page,null);
        for(SettingsCatalog.Spec spec:SettingsCatalog.ALL)if(spec.group.equals(group))ui.item(list,spec.title,specStatus(spec),0,()->openSettings(spec));
        if(group.equals("Wi-Fi")){LinearLayout more=ui.group(page,"Wi-Fi tools");ui.item(more,"WPS pairing","Connect a device with its WPS button",R.drawable.ic_wifi,this::wpsPairing);ui.item(more,"Channel finder","Pick the clearest channels",R.drawable.ic_channel,this::channelFinder);}
        if(group.equals("Administration")){LinearLayout more=ui.group(page,"Administration tools");ui.item(more,"Change admin password","For the selected node",R.drawable.ic_admin,this::changePassword);ui.item(more,"Firmware update","Check now or install",R.drawable.ic_restart,this::firmwareUpdate);ui.item(more,"Router logs","Traffic, security and DHCP logs",R.drawable.ic_tools,this::routerLogs);}
        if(!topology.demo&&!management)ui.note(page,"View-only. Turn on management controls on the Settings tab to change these.");
    }
    private void targetPicker(){
        LinearLayout card=ui.card();ui.text(card,"SETTINGS TARGET",11,ui.accent);
        if(topology.demo){ui.text(card,"Demo · Connect to read actual settings",14,ui.text);page.addView(card);return;}
        List<String> hosts=new ArrayList<>(),labels=new ArrayList<>();List<Topology.Node> nodes=new ArrayList<>();
        for(String host:sessions.keySet()){hosts.add(host);labels.add(targetName(host));Topology.Node match=null;for(Topology.Node n:topology.nodes)if(host.equals(n.ip))match=n;nodes.add(match);}
        for(Topology.Node n:topology.nodes)if(n.ip!=null&&!n.ip.isEmpty()&&!sessions.containsKey(n.ip)){hosts.add(n.ip);labels.add(n.name+" · "+n.ip+" · connect");nodes.add(n);}
        int initial=Math.max(0,hosts.indexOf(selectedHost));
        Spinner selector=new Spinner(ui.dialogContext());selector.setAdapter(new ArrayAdapter<>(ui.dialogContext(),android.R.layout.simple_spinner_dropdown_item,labels));selector.setSelection(initial);card.addView(selector);selector.setMinimumHeight(ui.d(48));
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int i,long id){
            // Android reports the initial selection when the spinner is laid out; act only on a real change.
            if(i==initial||i>=hosts.size()||selectedHost.equals(hosts.get(i)))return;String host=hosts.get(i);
            if(sessions.containsKey(host)){selectedHost=host;pageStatus.clear();showTab();return;}
            Topology.Node n=nodes.get(i);if(n==null)return;nodeConnectedThen=()->{pageStatus.clear();tab="Settings";back=null;showTab();};connectNode(n);}});
        ui.text(card,"Nodes you have not connected to use the primary's password automatically (one attempt).",12,ui.muted);page.addView(card);
    }
    private void openSettings(SettingsCatalog.Spec spec){
        if(spec.id.equals("onboarding")){onboardingScreen();return;}if(spec.read.isEmpty()){message(spec.title,spec.note);return;}if(topology.demo){demoSettings(spec);return;}
        JnapClient session=selected();if(session==null){message("Connect first","Select a connected node or connect directly from Mesh.");return;}
        task("Reading "+spec.title.toLowerCase(Locale.ROOT)+"…",()->new SettingsRepository(session).load(spec),snapshot->{pageStatus.put(session.host()+":"+spec.id,"Read validated · Writes untested");edit(snapshot,session);});
    }
    private void edit(SettingsRepository.Snapshot snapshot,JnapClient session){
        SettingsCatalog.Spec spec=snapshot.spec;detail(spec.title,parent());ui.text(page,"Target "+session.host(),12,ui.accent);boolean allowed=management&&(!spec.routerOnly||bridgeKnown&&!bridge);
        if(!spec.note.isEmpty())ui.note(page,spec.note);if(bridge&&spec.routerOnly)ui.note(page,"This mesh is in bridge mode. Configure routing features on your OpenWrt or OPNsense main router.");
        if(!bridgeKnown&&spec.routerOnly)ui.note(page,"Routing mode could not be confirmed. Return to a main tab and refresh before enabling these controls.");
        SettingsForm form=new SettingsForm(ui,snapshot,allowed);page.addView(form.view);
        if(spec.editable()&&allowed)ui.action(page,"Review changes",true,()->{try{SettingsRepository repo=new SettingsRepository(session);SettingsRepository.Change change=repo.prepare(snapshot,form.collect());confirm("Apply "+spec.title.toLowerCase(Locale.ROOT)+"?",change.preview+"\n\n"+spec.impact,spec.disruption,()->{
            if(!management){message("Controls disabled","Turn on management controls on the Settings tab first.");return;}
            task("Applying once, then verifying…",()->repo.apply(change,true),outcome->{message("Router response",outcome.message);notice=outcome.message;back=null;showTab();});
        });}catch(Exception e){message("Check the changes",safeMessage(e));}});
        ui.action(page,"Reload from router",false,()->openSettings(spec));
    }
    private void demoSettings(SettingsCatalog.Spec spec){
        detail(spec.title,parent());ui.note(page,"Demo uses sample data. Connect to your router to validate and manage this page.");
        LinearLayout card=ui.card();ui.heading(card,"Available controls");for(JSONObject f:spec.fields)if(!f.optString("type").equals("identity")&&!f.optString("type").equals("preserve"))ui.text(card,f.optString("label"),14,ui.text);if(spec.fields.isEmpty())ui.text(card,"Read firmware-reported information",14,ui.muted);page.addView(card);if(!spec.note.isEmpty())ui.note(page,spec.note);
    }
    private void toolsPage(){
        detail("Tools & diagnostics",()->{returnTo=null;showTab();});returnTo=this::toolsPage;
        LinearLayout network=ui.group(page,"Network");
        ui.item(network,"Speed check","Router's built-in internet speed test",R.drawable.ic_speed,this::speedCheck);
        ui.item(network,"Connection drop monitor","Log drops in the background for hours",R.drawable.ic_monitor,this::monitorScreen);
        ui.item(network,"Channel finder","Pick the clearest Wi-Fi channels",R.drawable.ic_channel,this::channelFinder);
        ui.item(network,"Neighbor signals & steering","What each node hears",R.drawable.ic_wifi,this::neighborSignals);
        ui.item(network,"Restart whole network","Restarts every node",R.drawable.ic_restart,this::restartNetwork);
        LinearLayout diag=ui.group(page,"Diagnostics");
        ui.item(diag,"Ping","From the selected router",R.drawable.ic_tools,this::ping);
        ui.item(diag,"Traceroute","From the selected router",R.drawable.ic_tools,this::traceroute);
        ui.item(diag,"Router logs","Traffic, security and DHCP logs",R.drawable.ic_tools,this::routerLogs);
        ui.item(diag,"Firmware update","Check now or install",R.drawable.ic_restart,this::firmwareUpdate);
        ui.item(diag,"WPS pairing","Connect a device with its WPS button",R.drawable.ic_wifi,this::wpsPairing);
        ui.item(diag,"Change admin password","For the selected node",R.drawable.ic_admin,this::changePassword);
        ui.item(diag,"API capability results","What the last refresh could read",R.drawable.ic_info,this::capabilities);
        LinearLayout checks=ui.card();ui.heading(checks,"Validate your firmware");ui.text(checks,"Runs read-only requests one at a time on connected node sessions. Authentication failure stops tests for that node. No settings are changed.",14,ui.muted);
        if(audit!=null)ui.text(checks,audit.summary(),14,ui.accent);ui.action(checks,validating?"Validation is running…":"Run sequential validation",true,this::validateAll);if(validating)ui.action(checks,"Stop after current request",false,()->{cancelled=true;toast("Stopping after the current read");});
        if(audit!=null)ui.action(checks,"Export diagnostic log",false,()->export(audit.json(),"Velop-Validation-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date())+".json"));page.addView(checks);
    }
    private void preferencesPage(){
        detail("App preferences",()->{returnTo=null;showTab();});returnTo=this::preferencesPage;
        LinearLayout app=ui.card();
        Switch polling=ui.toggle("Refresh every 30 seconds while open",auto);app.addView(polling);polling.setOnCheckedChangeListener((b,on)->{auto=on;preferences.edit().putBoolean("auto",on).apply();schedule();});
        Switch dark=ui.toggle("Dark appearance",ui.dark);app.addView(dark);dark.setOnCheckedChangeListener((b,on)->{preferences.edit().putBoolean("dark",on).apply();preferencesPage();});
        Switch shots=ui.toggle("Allow screenshots",preferences.getBoolean("screenshots",false));app.addView(shots);shots.setOnCheckedChangeListener((b,on)->{
            if(on){shots.setChecked(false);ui.dialog("Allow screenshots?").setMessage("Screenshots, screen recordings and the recent-apps preview will be able to capture device names, IP and MAC addresses and network settings. Password fields stay masked. You can turn this off again at any time.").setNegativeButton("Keep blocked",null).setPositiveButton("Allow",(d,w)->{preferences.edit().putBoolean("screenshots",true).apply();applyScreenshotPolicy();preferencesPage();}).show();}
            else{preferences.edit().putBoolean("screenshots",false).apply();applyScreenshotPolicy();}});
        ui.text(app,"Screenshots are off by default so they and the recent-apps preview do not capture network details.",12,ui.muted);page.addView(app);
        LinearLayout hist=ui.card();ui.heading(hist,"Connection history");
        Switch historical=ui.toggle("Save local connection history (7 days)",preferences.getBoolean("history",false));hist.addView(historical);historical.setOnCheckedChangeListener((b,on)->preferences.edit().putBoolean("history",on).apply());ui.text(hist,"Stores device labels, MAC addresses and association reports inside this app. Exported validation logs exclude these identifiers.",12,ui.muted);
        ui.action(hist,"View connection history",false,()->showHistory(null));ui.action(hist,"Clear local history",false,()->ui.dialog("Clear connection history?").setNegativeButton("Keep",null).setPositiveButton("Clear",(d,w)->task("Clearing local history…",()->{history.clear();return true;},r->toast("Local history cleared"))).show());page.addView(hist);
        LinearLayout pw=ui.card();ui.heading(pw,"Saved passwords");Switch remembered=ui.toggle("Remember passwords on this device",preferences.getBoolean("remember",true));pw.addView(remembered);remembered.setOnCheckedChangeListener((button,on)->{preferences.edit().putBoolean("remember",on).apply();if(!on)vault.forgetAll();});
        ui.action(pw,"Forget saved passwords",false,()->ui.dialog("Forget saved passwords?").setMessage("Current sessions stay connected. You will need the passwords on your next connection.").setNegativeButton("Keep",null).setPositiveButton("Forget",(d,w)->{vault.forgetAll();toast("Saved passwords removed");}).show());page.addView(pw);
    }
    private void aboutPage(){
        detail("About & updates",()->{returnTo=null;showTab();});returnTo=this::aboutPage;
        LinearLayout about=ui.card();ui.heading(about,"Velop Control "+BuildConfig.VERSION_NAME);ui.text(about,"Independent Android client for stock Linksys mesh routers. Local JNAP over pinned HTTPS. GitHub is contacted only for app updates. No advertisements, WebView or separate backend.",14,ui.muted);ui.text(about,"Read support is firmware-dependent. Manual parent locking, node removal, factory reset, full backup/restore and cloud subscription features are not available.",13,ui.muted);page.addView(about);
        LinearLayout list=ui.group(page,null);ui.item(list,"App updates","Check GitHub for a newer version",R.drawable.ic_restart,this::updates);
        ui.action(page,"Disconnect",false,()->{disconnect();login("");});
    }
    private String onboardingKey(JnapClient session){return "onboardingPending:"+session.host();}
    private NodeOnboarding.Journal onboardingJournal(JnapClient session){
        final String key=onboardingKey(session);
        return value->{boolean saved=value.isEmpty()?preferences.edit().remove(key).commit():preferences.edit().putString(key,value).commit();if(!saved)throw new IllegalStateException("Setup recovery information could not be saved. Check setup status before continuing.");};
    }
    private boolean onboardingPending(){return primary!=null&&!preferences.getString(onboardingKey(primary),"").isEmpty();}
    private void onboardingCard(){
        if(onboarding==null&&!onboardingPending())return;
        LinearLayout card=ui.card();ui.heading(card,"Mesh setup needs attention");ui.text(card,onboardingMessage.isEmpty()?"A previous setup window may still be enabled on the primary. Open setup to check and finish cleanup.":onboardingMessage,14,ui.warning);ui.action(card,"Open mesh setup",false,this::onboardingScreen);page.addView(card);
    }
    private void onboardingScreen(){
        detail("Add mesh nodes",parent());showingOnboarding=true;
        ui.note(page,"Connect an unconfigured compatible Linksys node to power near the primary. Wait for its setup-ready light, usually solid purple. Leave configured nodes alone. The app uses stock local setup; it does not reset routers.");
        if(primary==null||topology==null||topology.demo){ui.note(page,"Connect to your primary Velop to start setup. Demo sends no requests.");return;}
        ui.text(page,"Primary target: "+primary.host(),13,ui.accent);
        if(!onboardingMessage.isEmpty())ui.note(page,onboardingMessage);
        if(onboarding!=null||onboardingPending()){
            ui.text(page,"Keep this app open during setup. After five minutes it ends monitoring and restores any onboarding flag it enabled. If Android closes the app, reopen this page and finish cleanup. Bluetooth work already underway may still finish after the flag is disabled.",14,ui.muted);
            ui.action(page,"Check setup status",false,()->{if(onboarding!=null){onboardingPaused=false;checkOnboarding();}else message("Setup interrupted","The previous app session ended. Finish cleanup below, refresh the mesh, then review its nodes before starting another window.");});
            ui.action(page,"End setup window / finish cleanup",true,this::stopOnboarding);return;
        }
        ui.text(page,"Wireless setup uses the primary router’s Bluetooth radio. Your phone stays on the current mesh Wi-Fi; no phone Bluetooth permission or internet connection is needed. Have only the intended unconfigured node powered nearby: automatic setup may enroll more than one eligible node.",14,ui.muted);
        ui.text(page,"For wired setup, connect the new node’s Internet/WAN port to a LAN port on an existing mesh node. Keep the cable connected throughout setup. Compatibility and wireless backhaul after moving the node depend on its stock firmware.",14,ui.muted);
        if(!management)ui.note(page,"Turn on management controls on the Settings tab before starting a setup window.");
        ui.action(page,"Add a wireless node…",true,()->prepareOnboarding(NodeOnboarding.Kind.WIRELESS));
        ui.action(page,"Add a wired node…",false,()->prepareOnboarding(NodeOnboarding.Kind.WIRED));
        ui.action(page,"View nodes and refresh",false,()->{back=null;tab="Mesh";showTab();refresh();});
    }
    private void prepareOnboarding(NodeOnboarding.Kind kind){
        if(primary==null||topology==null||topology.demo)return;
        if(!management){message("Controls disabled","Turn on management controls on the Settings tab first.");return;}
        if(onboarding!=null||onboardingPending()||restartActive()){message("Finish the current operation","End any setup window and finish restart monitoring before adding nodes.");return;}
        JnapClient session=primary;
        task("Checking primary setup support and current mesh…",()->NodeOnboarding.inspect(session::onboardingRequest,kind),snapshot->{
            if(primary!=session)return;
            ui.dialog("Start "+(kind==NodeOnboarding.Kind.WIRED?"wired":"wireless")+" mesh setup?")
                .setMessage("Primary: "+session.host()+"\n\n"+(kind==NodeOnboarding.Kind.WIRED?"Allow compatible unconfigured nodes connected by Ethernet to join this mesh.":"Ask the primary’s Bluetooth radio to find nearby compatible unconfigured nodes and add them to this mesh. Any eligible nearby nodes may join; power only the intended one.")+"\n\n"+(snapshot.enabled?"Automatic onboarding is already enabled. The app will leave that existing setting enabled when monitoring ends.":"The app will temporarily enable automatic onboarding and restore it to disabled after a new connected mesh member appears, after five minutes while open, or when you end the window.")+" Already-started wireless setup may still finish. Setup support is based on firmware analysis and still needs live-device validation.")
                .setNegativeButton("Cancel",null).setPositiveButton("Start setup",(d,w)->{
                    if(!management||primary!=session||busy||onboarding!=null||onboardingPending())return;
                    NodeOnboarding flow=new NodeOnboarding(session::onboardingRequest,onboardingJournal(session),snapshot);
                    task("Starting setup once…",()->{try{flow.start();return "Setup request accepted. Waiting for a new connected mesh member…";}catch(Exception e){if(!flow.attempted())throw e;return "Setup may have started, but its reply was not confirmed. The start command will not be repeated. Check status or finish cleanup. "+safeMessage(e);}},text->{onboarding=flow;onboardingSession=session;onboardingStarted=SystemClock.elapsedRealtime();onboardingPaused=false;onboardingMessage=text;onboardingScreen();scheduleOnboarding();});
                }).show();
        });
    }
    private void scheduleOnboarding(){main.removeCallbacks(onboardingPoll);if(resumed&&onboarding!=null&&!onboardingChecking&&!onboardingPaused)main.postDelayed(onboardingPoll,5000);}
    private void checkOnboarding(){
        if(onboarding==null||onboardingChecking||!resumed)return;
        if(busy||restartActive()){scheduleOnboarding();return;}
        if(SystemClock.elapsedRealtime()-onboardingStarted>=NodeOnboarding.WINDOW_MS){onboardingMessage="Five-minute monitoring window ended. No new connected mesh member was confirmed.";stopOnboarding();return;}
        final NodeOnboarding flow=onboarding;final int ticket=generation;onboardingChecking=true;main.removeCallbacks(onboardingPoll);setBusy(true,"Checking mesh setup progress…");
        worker.execute(()->{
            NodeOnboarding.Progress state=null;String error=null;boolean joined=false;
            try{state=flow.poll();joined=!state.added.isEmpty();if(joined)flow.stop();}catch(Exception e){error=safeMessage(e);}
            final NodeOnboarding.Progress result=state;final String failure=error;final boolean observed=joined;
            main.post(()->{if(ticket!=generation||isDestroyed())return;onboardingChecking=false;setBusy(false,"");
                if(failure!=null){onboardingPaused=true;onboardingMessage=(observed?"A new connected mesh member was reported, but cleanup is still pending. ":"Setup status could not be read. Monitoring paused; check manually or finish cleanup. ")+failure;}
                else if(observed){onboardingMessage="New connected mesh member reported: "+android.text.TextUtils.join(", ",result.added)+". "+(flow.wasEnabled()?"The previously enabled onboarding setting was retained.":"The temporary onboarding setting is disabled again.")+" Bluetooth setup already underway may still finish. Refresh, then open Mesh to inspect the new member.";onboarding=null;onboardingSession=null;}
                else onboardingMessage="Waiting for a new connected mesh member. "+(result.radio.isEmpty()?"":("Primary Bluetooth report: "+result.radio+". "))+"Elapsed: "+((SystemClock.elapsedRealtime()-onboardingStarted)/1000)+" seconds. An accepted setup request alone does not confirm joining.";
                if(showingOnboarding)onboardingScreen();else if(back==null)showTab();
                if(failure==null)scheduleOnboarding();schedule();
            });
        });
    }
    private void stopOnboarding(){
        if(primary==null||busy||onboardingChecking){toast("Wait for the current operation");return;}
        main.removeCallbacks(onboardingPoll);final JnapClient session=onboardingSession==null?primary:onboardingSession;final NodeOnboarding flow=onboarding;
        String record=preferences.getString(onboardingKey(session),"");if(flow==null&&record.isEmpty()){onboardingScreen();return;}
        task("Ending setup and checking temporary settings…",()->{if(flow!=null)flow.stop();else NodeOnboarding.recover(session::onboardingRequest,onboardingJournal(session),record);return true;},ok->{onboarding=null;onboardingSession=null;onboardingMessage="Setup window ended and any temporary onboarding flag was restored. Existing settings were retained. Nodes already added remain members; Bluetooth setup already underway may still finish. Refresh, then open Mesh to check the result.";onboardingScreen();});
    }
    private void validateAll(){
        if(topology.demo||primary==null){message("Connect to your mesh","Validation needs a local router session.");return;}if(busy){toast("Wait for the current operation");return;}
        audit=new AuditReport();cancelled=false;validating=true;main.removeCallbacks(poll);setBusy(true,"Starting sequential read-only validation…");showTab();int ticket=generation;List<JnapClient> targets=new ArrayList<>(sessions.values());
        List<String> actions=new ArrayList<>(Arrays.asList(ReadPolicy.INFO,ReadPolicy.DEVICES,ReadPolicy.BACKHAUL,ReadPolicy.NODE_CONNECTIONS,ReadPolicy.CONNECTIONS,ReadPolicy.MODE,"diagnostics/GetSystemStats","nodes/setup/GetSelectedChannels","router/GetEthernetPortConnections",NeighborSignals.ACTION,"wirelessap/GetAdvancedRadioInfo","powertable/GetPowerTableSettings"));for(SettingsCatalog.Spec s:SettingsCatalog.ALL)if(!s.read.isEmpty()&&!actions.contains(s.read))actions.add(s.read);
        final AuditReport report=audit;final Map<String,String> checked=new HashMap<>();
        worker.execute(()->{int index=0;
            for(JnapClient target:targets){String alias="Node "+(++index);boolean rejected=false;
                for(String action:actions){if(cancelled||ticket!=generation)break;if(rejected){report.add(alias,action,"Skipped",0,null);continue;}
                    long start=System.nanoTime();JSONObject response=null;String state;
                    try{response=target.read(action,!action.equals(ReadPolicy.INFO));state=response.optString("result").equals("OK")?"Passed":response.optString("result").matches(".*(UnknownAction|InvalidAction|Unsupported|NotSupported).*" )?"Unsupported":"Rejected";}
                    catch(SecurityException e){state="AuthenticationRejected";rejected=true;}catch(Exception e){state="TransportOrResponseError";}
                    report.add(alias,action,state,(System.nanoTime()-start)/1_000_000,response);String label=alias+" · "+action.substring(action.lastIndexOf('/')+1)+" · "+state;
                    for(SettingsCatalog.Spec spec:SettingsCatalog.ALL)if(spec.read.equals(action))checked.put(target.host()+":"+spec.id,state.equals("Passed")?"Read validated · Writes untested":state);
                    main.post(()->{if(ticket==generation&&!isDestroyed()&&status!=null)status.setText("Read-only validation · "+label);});
                }if(cancelled||ticket!=generation)break;
            }
            main.post(()->{if(ticket!=generation||isDestroyed())return;validating=false;setBusy(false,"");pageStatus.putAll(checked);notice=(cancelled?"Validation stopped. ":"Validation finished. ")+report.summary();back=null;tab="Settings";returnTo=null;toolsPage();schedule();});
        });
    }
    private static final class SignalsRead {
        final List<NeighborSignals.Report> reports;final Map<String,String> radios;
        SignalsRead(List<NeighborSignals.Report> reports,Map<String,String> radios){this.reports=reports;this.radios=radios;}
    }
    /** Read-only: the primary's neighbor report, plus radio power and region from each connected node. */
    private void neighborSignals(){
        if(topology==null)return;
        if(topology.demo){showSignals(NeighborSignals.parse(NeighborSignals.demo(topology),topology),new LinkedHashMap<>());return;}
        if(primary==null){message("Connect first","Neighbor signals are read from the primary node.");return;}
        JnapClient session=primary;Topology snapshot=topology;List<JnapClient> targets=new ArrayList<>(sessions.values());
        task("Reading what each node hears…",()->{
            List<NeighborSignals.Report> reports=NeighborSignals.parse(session.read(NeighborSignals.ACTION,true),snapshot);
            Map<String,String> radios=new LinkedHashMap<>();for(JnapClient c:targets)radios.put(c.host(),radioSummary(c));
            return new SignalsRead(reports,radios);
        },r->showSignals(r.reports,r.radios));
    }
    static String radioSummary(JnapClient c) throws Exception {
        StringBuilder b=new StringBuilder();
        try{JSONObject r=c.read("wirelessap/GetAdvancedRadioInfo",true);SettingsRepository.requireOK(r);
            for(JSONObject radio:Topology.entries(Topology.object(r,"output"),"radios")){String power=Topology.object(radio,"settings").optString("transmissionPower");
                b.append(Topology.radioLabel(radio.optString("radioID"))).append(" power: ").append(power.matches("[A-Za-z]{1,20}")?power:"Not reported").append('\n');}
        }catch(SecurityException e){throw e;}catch(Exception e){b.append("Radio power: not available on this firmware\n");}
        try{JSONObject r=c.read("powertable/GetPowerTableSettings",true);SettingsRepository.requireOK(r);JSONObject o=Topology.object(r,"output");String country=o.optString("country");
            b.append("Power-table region: ").append(country.matches("[A-Za-z]{2,3}")?country:"Not set").append(o.optBoolean("isPowerTableSelectable")?" (selectable)":" (fixed)");
        }catch(SecurityException e){throw e;}catch(Exception e){b.append("Power-table region: not available on this firmware");}
        return b.toString().trim();
    }
    private void showSignals(List<NeighborSignals.Report> reports,Map<String,String> radios){
        detail("Neighbor signals",parent());
        ui.note(page,"How strongly each node hears the others on its radios, from the primary's neighbor report. The verdict applies the stock steering rules with default settings: an uplink at or above −60 dBm is left alone; otherwise the node moves when another node is more than 10 dB stronger, or when the strongest is the primary. Children and 2.4 GHz radios are ignored. Checks run about hourly or after a restart.");
        List<Topology.Node> silent=NeighborSignals.missing(reports,topology);
        if(!silent.isEmpty()){LinearLayout card=ui.card();ui.heading(card,"No report yet from "+silent.size()+" node"+(silent.size()>1?"s":""));
            for(Topology.Node n:silent)ui.text(card,n.name+(n.seen?"":" · not currently reported in the mesh"),14,ui.text);
            ui.text(card,"A node sends this report about hourly (scan, then about 5 minutes later) and whenever its Wi-Fi starts, but only while node steering is on under Settings → Automatic mesh steering. The primary keeps reports in memory, so they are lost when the primary restarts. Restart the missing node, wait a few minutes, then reopen this page.",13,ui.muted);
            page.addView(card);}
        for(NeighborSignals.Report r:reports){
            LinearLayout card=ui.card();Topology.Node n=r.node;ui.heading(card,n==null?"Node not in inventory":n.name);
            if(n!=null&&!n.primary)ui.text(card,"Uplink: "+(n.parentId==null?"parent not reported":topology.nodeName(n.parentId))+" · "+Ui.known(n.link)+(n.rssi==null||n.rssi.isEmpty()?"":" · "+n.rssi+" dBm"),13,ui.muted);
            ui.text(card,r.verdict,14,r.attention?ui.warning:ui.accent);
            if(r.heard.isEmpty())ui.text(card,"No neighboring node radios reported.",13,ui.muted);
            int shown=0;for(NeighborSignals.Heard h:r.heard){if(++shown>12)break;
                String who=h.node==null?"Unidentified mesh radio ··"+h.bssid.substring(11):h.node.name+(h.exact?"":" (matched by address)");
                boolean child=n!=null&&!n.primary&&"Wireless".equalsIgnoreCase(n.link)&&h.node!=null&&n.id.equals(h.node.parentId);
                ui.text(card,who+" · "+h.rssi+" dBm · "+h.band()+(h.ignoredBand()?" · ignored by steering":"")+(child?" · its child, ignored":""),13,ui.text);}
            page.addView(card);
        }
        LinearLayout power=ui.card();ui.heading(power,"Radio power & region");
        if(topology.demo)ui.text(power,"Demo: connect to read each node's radio power setting and power-table region.",13,ui.muted);
        for(Map.Entry<String,String> e:radios.entrySet()){String name=e.getKey();for(Topology.Node n:topology.nodes)if(e.getKey().equals(n.ip))name=n.name+" · "+e.getKey();ui.text(power,name,14,ui.text);ui.text(power,e.getValue(),13,ui.muted);}
        ui.text(power,"Only nodes with a direct session are read. Connect directly to other nodes from their page to include them. Wi-Fi radio settings are shared from the primary; the power-table region caps each node's maximum power.",12,ui.muted);
        page.addView(power);
    }
    private void nodeDiagnostics(Topology.Node node,JnapClient session){
        task("Reading diagnostics for "+node.name+"…",()->{
            Map<String,JSONObject> data=new LinkedHashMap<>();for(String a:new String[]{ReadPolicy.INFO,ReadPolicy.MODE,"diagnostics/GetSystemStats","router/GetEthernetPortConnections","nodes/setup/GetSelectedChannels","wirelessap/GetAdvancedRadioInfo","powertable/GetPowerTableSettings"}){try{data.put(a,session.read(a,!a.equals(ReadPolicy.INFO)));}catch(SecurityException e){throw e;}catch(Exception e){data.put(a,new JSONObject().put("result","Unavailable"));}}return data;
        },data->{detail(node.name+" diagnostics",()->node(node));for(Map.Entry<String,JSONObject> e:data.entrySet()){LinearLayout card=ui.card();ui.heading(card,Json.label(e.getKey().substring(e.getKey().lastIndexOf('/')+1).replaceFirst("^Get","")));key(card,"Result",SettingsRepository.safeCode(e.getValue().optString("result")));showSafeValues(card,e.getValue().optJSONObject("output"),0);page.addView(card);}});
    }
    private void wiredNeighbors(Topology.Node node,JnapClient session){
        ui.dialog("Read wired-neighbor evidence?").setMessage("This reads this node’s local system-information page once over pinned HTTPS. Only LLDP neighbor addresses and ports are kept in memory; the full report is discarded and is not included in exports. This may take several seconds. It does not change mesh settings.")
            .setNegativeButton("Cancel",null).setPositiveButton("Read once",(d,w)->task("Reading local wired-neighbor evidence…",session::readWiredNeighbors,report->{
                detail(node.name+" wired neighbors",()->node(node));
                ui.note(page,"LLDP is neighbor evidence, not proof of the physical cable path. A switch may sit between nodes, reports can be cached, and firmware may combine ports. These results do not override the mesh topology.");
                if(!report.sectionFound)ui.note(page,"This firmware did not expose a recognized LLDP section through the local system-information page.");
                else if(report.neighbors.isEmpty())ui.note(page,"The LLDP section contained no recognized neighbors. This does not mean no cable is connected.");
                for(WiredNeighbors.Neighbor peer:report.neighbors){LinearLayout card=ui.card();String name="Unmatched Ethernet neighbor";
                    for(Topology.Node candidate:topology.nodes)if(!peer.managementIp.isEmpty()&&peer.managementIp.equals(candidate.ip))name=candidate.name;
                    ui.heading(card,name);key(card,"Local interface",peer.localInterface);key(card,"Neighbor address",peer.managementIp);key(card,"Neighbor chassis MAC",peer.chassisMac);key(card,"Neighbor port",peer.remotePort);page.addView(card);
                }
                ui.text(page,"Read "+DateFormat.getDateTimeInstance().format(new Date()),12,ui.muted);
            })).show();
    }
    private void showSafeValues(LinearLayout parent,Object data,int depth){
        if(depth>5)return;if(data instanceof JSONObject){JSONObject o=(JSONObject)data;for(String k:Json.keys(o)){if(Json.secret(k)||k.toLowerCase(Locale.ROOT).contains("serial"))continue;Object v=o.opt(k);if(v instanceof JSONObject||v instanceof JSONArray){ui.text(parent,Json.label(k),13,ui.accent);showSafeValues(parent,v,depth+1);}else key(parent,Json.label(k),String.valueOf(v));}}
        else if(data instanceof JSONArray){JSONArray a=(JSONArray)data;for(int i=0;i<Math.min(a.length(),40);i++)showSafeValues(parent,a.opt(i),depth+1);}
    }
    private void capabilities(){detail("API capability results",parent());for(Map.Entry<String,String> e:topology.capabilities.entrySet()){LinearLayout card=ui.card();ui.text(card,e.getKey(),13,ui.text);ui.text(card,e.getValue(),13,ui.accent);page.addView(card);}ui.note(page,"A successful read confirms that read request only. It does not validate a matching write command.");}
    private void showHistory(String device){if(topology.demo){message("Demo","Connect to a router and enable local history to record association changes.");return;}task("Reading local history…",()->history.recent(device),events->{detail("Connection history",parent());ui.note(page,"App observations only. Gaps while the app was closed are unknown. History is retained for seven days.");if(events.isEmpty())ui.note(page,"No recorded observations yet. Turn on local history in Settings → App preferences, then refresh the mesh.");for(String event:events){LinearLayout card=ui.card();ui.text(card,event,14,ui.text);page.addView(card);}});}
    private void reboot(Topology.Node node,JnapClient session){
        if(!controls())return;
        if(restartActive()){message("Restart monitoring in progress","Wait for the current observation to finish before requesting another restart.");return;}
        confirm(node.primary?"Restart the whole network?":"Restart "+node.name+"?",node.primary
            ?"Restarting the primary restarts every node: the primary tells each child node to reboot, farthest first, then reboots itself. Wi-Fi and wired devices on the mesh lose their connection for about 3–5 minutes. This is a software restart. Nodes stay powered and keep all settings. Your modem, and in bridge mode your main router, are not restarted. The app sends the command once, then watches the primary come back while open."
            :"This restarts only "+node.name+" at "+session.host()+". Devices and child nodes using it may disconnect until the mesh recovers. Other nodes keep running. The app sends the command once, then watches for recovery while open.",true,()->task("Reading identity and uptime, then sending restart once…",()->{
            JSONObject identity=session.read(ReadPolicy.INFO,false);SettingsRepository.requireOK(identity);
            String serial=identity.getJSONObject("output").optString("serialNumber");
            if(serial.isEmpty())throw new IllegalStateException("Node identity was unavailable. No restart was sent.");
            long uptime=-1;try{uptime=RestartMonitor.uptime(session.read("diagnostics/GetSystemStats",true));}catch(SecurityException e){throw e;}catch(Exception ignored){}
            long started=SystemClock.elapsedRealtime();JSONObject response;
            try{response=session.write("core/Reboot",new JSONObject());}
            catch(SecurityException e){throw e;}
            catch(Exception e){return new RestartMonitor(session.host(),node.name,serial,uptime,started,false);}
            SettingsRepository.requireOK(response);
            return new RestartMonitor(session.host(),node.name,serial,uptime,started,true);
        },monitor->{restart=monitor;restartSession=session;if(monitor.uptimeBefore>=0)health.put(session.host(),new NodeHealth(monitor.uptimeBefore));back=null;tab="Mesh";showTab();scheduleRestart();}));
    }
    private boolean restartActive(){return restart!=null&&!restart.finished();}
    private void restartCard(){
        if(restart==null)return;LinearLayout card=ui.card();ui.heading(card,"Restart · "+restart.name);ui.text(card,restart.message(),14,restart.verified()?ui.accent:ui.muted);
        if(restartActive())ui.action(card,"Stop watching",false,()->{restart.stop("Monitoring stopped. The restart command will not be repeated.");main.removeCallbacks(restartPoll);showTab();});
        else ui.action(card,"Dismiss restart result",false,()->{restart=null;restartSession=null;showTab();});page.addView(card);
    }
    private void scheduleRestart(){main.removeCallbacks(restartPoll);if(resumed&&restartActive()&&!restartChecking)main.postDelayed(restartPoll,5000);}
    private void checkRestart(){
        if(!resumed||!restartActive()||restartChecking)return;
        if(busy){scheduleRestart();return;}
        RestartMonitor observed=restart;JnapClient session=restartSession;int ticket=generation;
        if(session==null||!sessions.containsValue(session)){observed.stop("Session changed. Reconnect to read the node’s current status.");return;}
        restartChecking=true;
        worker.execute(()->{
            long uptime=-1;boolean finishing=false;String blocked=null;JSONObject identity=null;
            try{identity=session.read(ReadPolicy.INFO,false);SettingsRepository.requireOK(identity);}
            catch(javax.net.ssl.SSLException e){blocked="HTTPS identity verification failed. Monitoring stopped; review the node certificate before reconnecting.";}
            catch(Exception ignored){}
            // State transitions stay on the main thread; determine the final read from a stable snapshot.
            long now=SystemClock.elapsedRealtime();
            if(identity!=null){String serial=Topology.object(identity,"output").optString("serialNumber");
                if(!observed.serial.equals(serial))blocked="A different or unidentified node answered at this address. Monitoring stopped.";
                else finishing=observed.needsFinalRead(now);
            }
            if(finishing&&blocked==null)try{uptime=RestartMonitor.uptime(session.read("diagnostics/GetSystemStats",true));}catch(Exception ignored){}
            final JSONObject reply=identity;final long finalUptime=uptime;final String reason=blocked;
            main.post(()->{
                if(ticket!=generation||isDestroyed())return;restartChecking=false;
                if(restart!=observed||observed.finished())return;
                long finishedAt=SystemClock.elapsedRealtime();
                if(reason!=null)observed.stop(reason);
                else if(reply==null){observed.unavailable();if(finishedAt-observed.startedAt>=RestartMonitor.WINDOW_MS)observed.expire();}
                else if(observed.reachable(Topology.object(reply,"output").optString("serialNumber"),finishedAt)){
                    if(finalUptime>=0)health.put(session.host(),new NodeHealth(finalUptime));observed.complete(finalUptime,finishedAt);
                }
                if(back==null&&(tab.equals("Mesh")||tab.equals("Home")))showTab();
                if(observed.finished()){
                    toast(observed.message());
                    if(reason==null&&reply!=null&&resumed&&back==null&&!busy)refresh();
                }else scheduleRestart();
            });
        });
    }
    static String formatUptime(long seconds){if(seconds<0)return "Not reported";long days=seconds/86400,hours=seconds/3600%24,minutes=seconds/60%60;return (days>0?days+"d ":"")+hours+"h "+minutes+"m "+seconds%60+"s";}
    private void rename(Topology.Node node){
        if(!controls()||primary==null)return;EditText name=ui.input("Node name",node.name,false);LinearLayout body=ui.column();body.setPadding(ui.d(22),0,ui.d(22),0);body.addView(name);AlertDialog d=ui.dialog("Rename node").setView(body).setNegativeButton("Cancel",null).setPositiveButton("Review",null).create();d.setOnShowListener(v->d.getButton(-1).setOnClickListener(b->{String text=name.getText().toString().trim();if(text.isEmpty()||text.length()>64){name.setError("Use 1–64 characters");return;}d.dismiss();confirm("Rename "+node.name+"?","New display name: "+text,false,()->task("Updating node label…",()->{JSONObject bodyJson=new JSONObject().put("deviceID",node.id).put("propertiesToModify",new JSONArray().put(new JSONObject().put("name","userDeviceName").put("value",text))).put("propertiesToRemove",new JSONArray());SettingsRepository.requireOK(primary.write("devicelist/SetDeviceProperties",bodyJson));return true;},r->{notice="Name accepted. Refresh to read it back.";back=null;showTab();}));}));d.show();
    }
    private void ping(){
        if(!controls())return;JnapClient session=selected();if(session==null){message("Select a node","Choose a connected target in Settings.");return;}EditText host=ui.input("IPv4 address or DNS hostname","1.1.1.1",false);LinearLayout body=ui.column();body.setPadding(ui.d(22),0,ui.d(22),0);body.addView(host);
        AlertDialog d=ui.dialog("Ping from "+session.host()).setView(body).setNegativeButton("Cancel",null).setPositiveButton("Review",null).create();d.setOnShowListener(v->d.getButton(-1).setOnClickListener(b->{String value=host.getText().toString().trim();if(!value.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")){host.setError("Enter an IPv4 address or hostname");return;}d.dismiss();confirm("Run router ping?","Send four small ICMP echo requests from "+session.host()+" to "+value+". This is an active diagnostic.",false,()->task("Starting router ping…",()->{JSONObject r=session.write("diagnostics/StartPing",new JSONObject().put("host",value).put("packetSizeBytes",64).put("pingCount",4));SettingsRepository.requireOK(r);return true;},r->pollPing(session,0)));}));d.show();
    }
    private void pollPing(JnapClient session,int count){main.postDelayed(()->{if(isDestroyed()||!sessions.containsValue(session))return;task("Reading router ping result…",()->session.read("diagnostics/GetPingStatus",true),r->{detail("Router ping",parent());showSafeValues(page,r.optJSONObject("output"),0);if(count<3)ui.action(page,"Read ping status again",false,()->pollPing(session,count+1));});},1500);}

    // ---- Stock-app tools: channel finder, speed check, traceroute, WPS, firmware, logs ----------
    /** Repeats a read every delayMs while screen {@code key} stays open. Errors are passed on, never retried as writes. */
    private void quietPoll(String key,long delayMs,Callable<JSONObject> read,Consumer<JSONObject> ok,Consumer<Exception> fail){
        main.postDelayed(()->{
            if(!key.equals(screenKey)||isDestroyed()||!resumed)return;
            if(busy){quietPoll(key,delayMs,read,ok,fail);return;}
            int ticket=generation;
            worker.execute(()->{JSONObject r=null;Exception err=null;try{r=read.call();}catch(Exception e){err=e;}final JSONObject res=r;final Exception e2=err;
                main.post(()->{if(ticket!=generation||isDestroyed()||!key.equals(screenKey))return;if(e2!=null)fail.accept(e2);else ok.accept(res);});});
        },delayMs);
    }
    /** Polls stop while the app is in the background; pick up the active one again. */
    private void resumeToolPolls(){
        if(screenKey==null)return;
        switch(screenKey){case "channels":if(channelScan)pollChannels();break;case "speed":if(speedRunning)pollSpeed();break;case "trace":if(traceRunning)pollTrace();break;case "wps":if(wpsActive)pollWps();break;case "monitor":monitorScreen();break;default:}
    }
    private long since(long start){return (SystemClock.elapsedRealtime()-start)/1000;}

    private NetworkTools.Channels channelsBefore,channelsNow;private boolean channelScan;private long channelScanStarted;private String channelNote="";
    private void channelFinder(){
        if(topology==null)return;
        if(topology.demo){channelsBefore=NetworkTools.channels(NetworkTools.demoChannels(topology,false),topology);channelsNow=NetworkTools.channels(NetworkTools.demoChannels(topology,true),topology);channelScan=false;channelNote="Demo: sample channels before and after a channel finder run.";renderChannels();return;}
        if(primary==null){message("Connect first","Channel finder runs on the primary node.");return;}
        JnapClient s=primary;Topology t=topology;
        task("Reading current channels…",()->NetworkTools.channels(s.read(NetworkTools.CHANNELS,true),t),c->{channelsNow=c;if(!channelScan)channelsBefore=null;
            if(c.running&&!channelScan){channelScan=true;channelScanStarted=SystemClock.elapsedRealtime();channelNote="A channel scan is already running. Waiting for it to finish.";}
            renderChannels();if(channelScan)pollChannels();});
    }
    private void renderChannels(){
        detail("Channel finder",parent());screenKey="channels";
        ui.note(page,"Channel finder asks every node to scan for interference and pick the clearest channel on each radio, like the stock app. Wi-Fi and wireless backhaul links drop for about a minute while radios retune. Channels 52–144 are DFS channels: a radio must leave them for about a minute if it detects radar.");
        if(!channelNote.isEmpty())ui.note(page,channelNote);
        if(channelScan)ui.text(page,"Scanning… "+since(channelScanStarted)+" s elapsed. Checking every 10 seconds; the request is never repeated.",14,ui.warning);
        if(channelsNow!=null){
            if(channelsNow.radios.isEmpty())ui.note(page,"The primary reported no node radios. Nodes report their channels while online; refresh the mesh and reload.");
            String node=null;LinearLayout card=null;
            for(NetworkTools.Radio r:channelsNow.radios){
                if(!r.node.equals(node)){if(card!=null)page.addView(card);card=ui.card();ui.heading(card,r.node);node=r.node;}
                Integer before=NetworkTools.previous(channelsBefore,r);boolean changed=before!=null&&before!=r.channel;
                ui.text(card,r.label()+" · channel "+(r.channel==0?"not reported":String.valueOf(r.channel))+(r.dfs()?" · DFS":"")+(changed?" · was "+before:""),14,changed?ui.accent:ui.text);
            }
            if(card!=null)page.addView(card);
        }
        if(!topology.demo&&!channelScan){ui.action(page,"Find best channels…",true,this::startChannelScan);ui.action(page,"Reload channels",false,this::channelFinder);}
    }
    private void startChannelScan(){
        if(!controls()||primary==null)return;JnapClient s=primary;
        confirm("Run channel finder?","Every node scans and may move its radios to new channels. Wireless devices and wireless backhaul links disconnect for about a minute. The request is sent once.",true,()->{NetworkTools.Channels before=channelsNow;
            task("Starting channel finder once…",()->{SettingsRepository.requireOK(s.write(NetworkTools.CHANNEL_START,new JSONObject()));return true;},r->{channelsBefore=before;channelScan=true;channelScanStarted=SystemClock.elapsedRealtime();channelNote="Channel finder started. Your phone may lose Wi-Fi briefly; keep this screen open.";renderChannels();pollChannels();});});
    }
    private void pollChannels(){
        JnapClient s=primary;Topology t=topology;if(s==null)return;
        quietPoll("channels",10000,()->s.read(NetworkTools.CHANNELS,true),r->{
            NetworkTools.Channels c=null;try{c=NetworkTools.channels(r,t);}catch(Exception ignored){}
            if(c!=null&&!c.running){channelScan=false;channelsNow=c;channelNote=channelsBefore==null?"Channel finder finished.":"Channel finder finished. Changed channels are highlighted with their previous channel.";renderChannels();return;}
            continueChannelPoll();
        },e->{if(e instanceof SecurityException){channelScan=false;channelNote=safeMessage(e);renderChannels();return;}continueChannelPoll();});
    }
    private void continueChannelPoll(){
        if(since(channelScanStarted)>300){channelScan=false;channelNote="No finished result after 5 minutes. The mesh may still be settling; reload later. The request was not repeated.";renderChannels();return;}
        renderChannels();pollChannels();
    }

    private List<NetworkTools.Speed> speedHistory=new ArrayList<>();private NetworkTools.Speed speedNow;private boolean speedRunning,speedSeenRunning;private long speedStarted;private String speedNote="";
    private static final String SPEED_HISTORY_QUERY="{\"healthCheckModule\":\"SpeedTest\",\"lastNumberOfResults\":5,\"includeModuleResults\":true}";
    private void speedCheck(){
        if(topology==null)return;
        if(topology.demo){speedHistory=new ArrayList<>();speedNow=null;speedRunning=false;speedNote="Demo: connect to run the router's speed check.";renderSpeed();return;}
        if(primary==null){message("Connect first","Speed check runs on the primary node.");return;}
        JnapClient s=primary;
        task("Reading speed check results…",()->NetworkTools.speedHistory(s.query(NetworkTools.SPEED_RESULTS,new JSONObject(SPEED_HISTORY_QUERY))),h->{speedHistory=h;renderSpeed();if(speedRunning)pollSpeed();});
    }
    private void renderSpeed(){
        detail("Speed check",parent());screenKey="speed";
        ui.note(page,"Runs the router's built-in Speedtest by Ookla from the primary node. It measures the internet connection at the router, not your Wi-Fi, and uses several hundred megabytes of data. Results are stored on the router.");
        if(!speedNote.isEmpty())ui.note(page,speedNote);
        if(speedRunning)ui.text(page,"Testing… "+since(speedStarted)+" s elapsed",14,ui.warning);
        if(speedNow!=null)speedCard("Current test",speedNow);
        for(int i=0;i<speedHistory.size();i++)speedCard(i==0&&!speedRunning&&speedNow==null?"Last test":"Earlier test",speedHistory.get(i));
        if(speedHistory.isEmpty()&&speedNow==null&&!speedRunning&&!topology.demo)ui.note(page,"No stored results yet.");
        if(!topology.demo){if(speedRunning)ui.action(page,"Stop speed check",false,this::stopSpeed);else{ui.action(page,"Run speed check…",true,this::startSpeed);ui.action(page,"Reload results",false,this::speedCheck);}}
    }
    private void speedCard(String title,NetworkTools.Speed r){
        LinearLayout card=ui.card();ui.heading(card,title);key(card,"Result",NetworkTools.describe(r.state));key(card,"Download",r.down());key(card,"Upload",r.up());key(card,"Latency",r.latencyMs>0?r.latencyMs+" ms":"—");
        if(!r.server.isEmpty())key(card,"Speedtest server ID",r.server);if(!r.timestamp.isEmpty())key(card,"Time (UTC)",r.timestamp);page.addView(card);
    }
    private void startSpeed(){
        if(!controls()||primary==null)return;JnapClient s=primary;
        confirm("Run speed check?","The primary router runs Speedtest by Ookla over your internet connection for about 30–60 seconds and stores the result. Other traffic may slow down during the test.",false,()->
            task("Starting speed check once…",()->{SettingsRepository.requireOK(s.write(NetworkTools.SPEED_RUN,new JSONObject().put("runHealthCheckModule","SpeedTest")));return true;},r->{speedRunning=true;speedSeenRunning=false;speedNow=null;speedStarted=SystemClock.elapsedRealtime();speedNote="";renderSpeed();pollSpeed();}));
    }
    private void pollSpeed(){
        JnapClient s=primary;if(s==null)return;
        quietPoll("speed",5000,()->s.read(NetworkTools.SPEED_STATUS,true),r->{
            NetworkTools.Speed now=null;boolean parsed=true;try{now=NetworkTools.speedStatus(r);}catch(Exception e){parsed=false;}
            boolean pending=parsed&&now==null&&!speedSeenRunning&&since(speedStarted)<20;
            if(parsed&&!pending&&(now==null||!now.running)){speedRunning=false;speedNow=now;finishSpeed();return;}
            if(now!=null){speedNow=now;speedSeenRunning=true;}
            if(since(speedStarted)>180){speedRunning=false;speedNote="No result after 3 minutes. Reload results later.";renderSpeed();return;}
            renderSpeed();pollSpeed();
        },e->{if(e instanceof SecurityException||since(speedStarted)>180){speedRunning=false;speedNote=safeMessage(e);renderSpeed();return;}renderSpeed();pollSpeed();});
    }
    private void finishSpeed(){
        JnapClient s=primary;if(s==null){renderSpeed();return;}
        task("Reading the result…",()->NetworkTools.speedHistory(s.query(NetworkTools.SPEED_RESULTS,new JSONObject(SPEED_HISTORY_QUERY))),h->{speedHistory=h;if(speedNow!=null&&speedNow.succeeded()&&!h.isEmpty())speedNow=null;renderSpeed();});
    }
    private void stopSpeed(){
        JnapClient s=primary;if(s==null)return;
        task("Stopping speed check…",()->{SettingsRepository.requireOK(s.write(NetworkTools.SPEED_STOP,new JSONObject()));return true;},r->{speedRunning=false;speedNote="Speed check stopped.";speedCheck();});
    }

    private String traceLog="",traceNote="";private boolean traceRunning;private long traceStarted;private JnapClient traceSession;
    private void traceroute(){
        if(!controls())return;JnapClient session=selected();if(session==null){message("Select a node","Choose a connected target in Settings.");return;}
        EditText host=ui.input("IPv4 address or DNS hostname","1.1.1.1",false);LinearLayout body=ui.column();body.setPadding(ui.d(22),0,ui.d(22),0);body.addView(host);
        AlertDialog d=ui.dialog("Traceroute from "+session.host()).setView(body).setNegativeButton("Cancel",null).setPositiveButton("Start",null).create();
        d.setOnShowListener(v->d.getButton(-1).setOnClickListener(b->{String value=host.getText().toString().trim();if(!NetworkTools.validHost(value)){host.setError("Enter an IPv4 address or hostname");return;}d.dismiss();
            task("Starting traceroute…",()->{SettingsRepository.requireOK(session.write(NetworkTools.TRACE_START,new JSONObject().put("host",value)));return true;},r->{traceSession=session;traceRunning=true;traceLog="";traceNote="Tracing the route to "+value+" from "+session.host()+".";traceStarted=SystemClock.elapsedRealtime();renderTrace();pollTrace();});}));d.show();
    }
    private void renderTrace(){
        detail("Traceroute",parent());screenKey="trace";ui.note(page,traceNote);
        if(traceRunning)ui.text(page,"Running… "+since(traceStarted)+" s",14,ui.warning);
        LinearLayout card=ui.card();TextView out=ui.text(card,traceLog.isEmpty()?"No hops reported yet.":traceLog,13,ui.text);out.setTypeface(android.graphics.Typeface.MONOSPACE);page.addView(card);
        if(traceRunning&&traceSession!=null)ui.action(page,"Stop traceroute",false,()->{JnapClient s=traceSession;task("Stopping traceroute…",()->{SettingsRepository.requireOK(s.write(NetworkTools.TRACE_STOP,new JSONObject()));return true;},r->{traceRunning=false;renderTrace();});});
    }
    private void pollTrace(){
        JnapClient s=traceSession;if(s==null)return;
        quietPoll("trace",3000,()->s.read(NetworkTools.TRACE_STATUS,true),r->{
            try{NetworkTools.Trace t=NetworkTools.trace(r);traceLog=t.log;traceRunning=t.running;}catch(Exception e){traceRunning=false;traceNote=safeMessage(e);}
            if(traceRunning&&since(traceStarted)>120){traceRunning=false;traceNote+=" Stopped watching after 2 minutes.";}
            renderTrace();if(traceRunning)pollTrace();
        },e->{traceRunning=false;traceNote=safeMessage(e);renderTrace();});
    }

    private boolean wpsActive;private long wpsStarted;private String wpsNote="";
    private void wpsPairing(){
        if(topology==null)return;
        if(topology.demo){wpsActive=false;wpsNote="Demo: connect to start WPS pairing on the primary node.";renderWps();return;}
        if(primary==null){message("Connect first","WPS pairing runs on the primary node.");return;}
        if(!wpsActive)wpsNote="";renderWps();if(wpsActive)pollWps();
    }
    private void renderWps(){
        detail("WPS pairing",parent());screenKey="wps";
        ui.note(page,"Push-button WPS lets a printer or other WPS device join your main Wi-Fi without typing the password. Start pairing here, then press the WPS button on the device within 2 minutes. WPS must be enabled under Settings → WPS.");
        if(!wpsNote.isEmpty())ui.note(page,wpsNote);
        if(wpsActive)ui.text(page,"Waiting for a device… "+since(wpsStarted)+" s",14,ui.warning);
        if(topology.demo)return;
        if(wpsActive)ui.action(page,"Stop pairing",false,()->{JnapClient s=primary;if(s==null)return;task("Stopping WPS pairing…",()->{SettingsRepository.requireOK(s.write(NetworkTools.WPS_STOP,new JSONObject()));return true;},r->{wpsActive=false;wpsNote="Pairing stopped.";renderWps();});});
        else ui.action(page,"Start WPS pairing…",true,this::startWps);
    }
    private void startWps(){
        if(!controls()||primary==null)return;JnapClient s=primary;
        confirm("Start WPS pairing?","For the next 2 minutes, any device that presses its WPS button nearby can join your main Wi-Fi.",false,()->
            task("Starting WPS pairing…",()->{JSONObject a=s.read(NetworkTools.WPS_AVAILABLE,true);SettingsRepository.requireOK(a);
                if(!Topology.object(a,"output").optBoolean("available"))throw new IllegalStateException("WPS is not available. Enable it under Settings → WPS, and note that WPS does not work with WPA3-only security.");
                SettingsRepository.requireOK(s.write(NetworkTools.WPS_START,new JSONObject()));return true;},
                r->{wpsActive=true;wpsStarted=SystemClock.elapsedRealtime();wpsNote="Press the WPS button on your device now.";renderWps();pollWps();}));
    }
    private void pollWps(){
        JnapClient s=primary;if(s==null)return;
        quietPoll("wps",5000,()->s.read(NetworkTools.WPS_STATUS,true),r->{
            NetworkTools.Wps w;try{w=NetworkTools.wps(r);}catch(Exception e){wpsActive=false;wpsNote=safeMessage(e);renderWps();return;}
            if(!w.inProgress||since(wpsStarted)>130){wpsActive=false;wpsNote=w.result.equals("Succeeded")?"A device joined using WPS.":w.result.equals("Failed")?"WPS pairing failed or timed out.":"The pairing window ended without a reported result.";renderWps();return;}
            renderWps();pollWps();
        },e->{wpsActive=false;wpsNote=safeMessage(e);renderWps();});
    }

    private void firmwareUpdate(){
        if(topology==null)return;
        if(topology.demo){message("Demo","Connect to check for and install firmware updates.");return;}
        if(primary==null){message("Connect first","Firmware updates are managed by the primary node.");return;}
        JnapClient s=primary;
        task("Reading firmware status…",()->s.read(NetworkTools.FIRMWARE_STATUS,true),r->{
            detail("Firmware update",parent());screenKey="firmware";
            ui.note(page,"Uses the stock firmware's own Linksys update service for every node, like the stock app. Installing restarts each node; the whole network is offline for several minutes.");
            LinearLayout card=ui.card();ui.heading(card,"Current status");key(card,"Result",SettingsRepository.safeCode(r.optString("result")));showSafeValues(card,r.optJSONObject("output"),0);page.addView(card);
            ui.action(page,"Check for updates now",false,()->firmwareNow(true));
            ui.action(page,"Install available update now…",true,()->firmwareNow(false));
            ui.action(page,"Reload status",false,this::firmwareUpdate);
        });
    }
    private void firmwareNow(boolean onlyCheck){
        if(!controls()||primary==null)return;JnapClient s=primary;
        Runnable send=()->task(onlyCheck?"Asking the router to check for updates…":"Starting the firmware update once…",()->{SettingsRepository.requireOK(s.write(NetworkTools.FIRMWARE_NOW,new JSONObject().put("onlyCheck",onlyCheck)));return true;},
            r->message(onlyCheck?"Update check requested":"Update started",onlyCheck?"The router is contacting the Linksys update service. Reload the status in a minute.":"If an update is available, nodes download and install it, then restart. Keep them powered. The app will lose its connection; reconnect when the network is back."));
        if(onlyCheck)confirm("Check for firmware updates?","The primary asks the Linksys update service whether newer firmware exists. Nothing is installed.",false,send);
        else confirm("Install firmware now?","If newer firmware is available, every node downloads and installs it and restarts. The network is offline for several minutes. Do not unplug any node.",true,send);
    }

    private void routerLogs(){
        if(topology==null)return;
        if(topology.demo){message("Demo","Connect to read the router's logs.");return;}
        if(primary==null){message("Connect first","Logs are read from the primary node.");return;}
        detail("Router logs",parent());screenKey="logs";
        ui.note(page,"Read-only views of the primary's logs. Entries appear only while logging is enabled under Settings → Router logging. Entries can include device IP addresses.");
        for(Map.Entry<String,String> e:NetworkTools.LOGS.entrySet())ui.action(page,e.getKey(),false,()->showLog(e.getKey(),e.getValue()));
        ui.action(page,"Clear router logs…",false,()->{if(!controls())return;JnapClient s=primary;confirm("Clear router logs?","Deletes the log entries stored on the primary.",false,()->task("Clearing router logs…",()->{SettingsRepository.requireOK(s.write(NetworkTools.LOG_DELETE,new JSONObject()));return true;},r->toast("Router logs cleared")));});
    }
    private void showLog(String title,String action){
        JnapClient s=primary;if(s==null)return;
        task("Reading "+title.toLowerCase(Locale.ROOT)+"…",()->NetworkTools.logEntries(s.query(action,new JSONObject().put("firstEntryIndex",1).put("entryCount",100))),entries->{
            detail(title,this::routerLogs);
            if(entries.isEmpty())ui.note(page,"No entries. Check that logging is enabled under Settings → Router logging.");
            for(String line:entries){LinearLayout card=ui.card();ui.text(card,line,13,ui.text);page.addView(card);}
        });
    }

    private void deleteDevice(Topology.Client c){
        if(!controls()||primary==null)return;JnapClient s=primary;
        confirm("Remove "+c.name+"?","Removes this offline device and its custom name from the router's device list, like the stock app. If it connects again it reappears as a new device.",false,()->
            task("Removing device…",()->{SettingsRepository.requireOK(s.write(NetworkTools.DELETE_DEVICE,new JSONObject().put("deviceID",c.id)));return true;},r->{back=null;tab="Devices";refresh();message("Device removed",c.name+" was removed from the router's device list.");}));
    }

    /** Stock "Restart network": a restart of the primary, which the firmware propagates to every child node. */
    private void restartNetwork(){
        if(topology==null)return;
        if(topology.demo){message("Demo","Connect to restart your network.");return;}
        if(primary==null){message("Connect first","Restarting the network goes through the primary node.");return;}
        Topology.Node node=null;for(Topology.Node n:topology.nodes)if(n.primary)node=n;
        if(node==null){node=new Topology.Node();node.id="primary";node.name="Primary";node.ip=primary.host();node.primary=true;}
        reboot(node,primary);
    }
    // ---- Connection drop monitor -------------------------------------------------------------------
    private final Runnable monitorTick=()->{if("monitor".equals(screenKey)&&resumed)monitorScreen();};
    private boolean hasLocation(){return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED;}
    private void monitorScreen(){
        detail("Connection monitor",parent());screenKey="monitor";main.removeCallbacks(monitorTick);
        ui.note(page,"Records your phone's connection every 5 seconds in the background, with a notification, and stops by itself. Each check covers: Wi-Fi state, which node you are on, signal, and whether your main router, the primary Velop, the internet and DNS answer, all over Wi-Fi only. Afterwards it lists every drop and which part failed first. No passwords or router settings are used.");
        ui.note(page,"For complete results keep the phone charging and Wi-Fi on. If Android pauses the app anyway, the gap is marked as a pause, not counted as a drop.");
        if(MonitorService.running){
            LinearLayout card=ui.card();ui.heading(card,"Monitoring");key(card,"Started",DropMonitor.time(MonitorService.startedAt));key(card,"Stops at",DropMonitor.time(MonitorService.endsAt));
            key(card,"Checks so far",String.valueOf(MonitorService.samples));key(card,"Drops so far",String.valueOf(MonitorService.problems));page.addView(card);
            ui.action(page,"Stop monitoring",true,()->{startService(new Intent(this,MonitorService.class).setAction(MonitorService.ACTION_STOP));main.postDelayed(this::monitorScreen,500);});
            main.postDelayed(monitorTick,15000);
        }else{
            ui.action(page,"Monitor for 1 hour",true,()->startMonitor(60));ui.action(page,"Monitor for 2 hours",false,()->startMonitor(120));ui.action(page,"Monitor for 4 hours",false,()->startMonitor(240));
        }
        if(!hasLocation()){LinearLayout card=ui.card();ui.heading(card,"Which node am I on?");
            ui.text(card,"Android only reveals the access point's address to apps with location permission. Allow it to record which node your phone is connected to and when it switches. Location is not stored or used for anything else, and the monitor works without it.",13,ui.muted);
            ui.action(card,"Allow location for node tracking…",false,()->requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION},72));page.addView(card);}
        java.io.File log=MonitorService.file(this);
        if(!log.isFile()){ui.note(page,"No log recorded yet.");return;}
        List<DropMonitor.Sample> samples=new ArrayList<>();
        try(java.io.BufferedReader r=new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(log),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null){DropMonitor.Sample x=DropMonitor.parse(line);if(x!=null)samples.add(x);}}catch(java.io.IOException e){ui.note(page,"The log could not be read.");return;}
        DropMonitor.Report report=DropMonitor.analyse(samples,MonitorService.INTERVAL_MS,this::nodeForBssid);
        LinearLayout summary=ui.card();ui.heading(summary,MonitorService.running?"Results so far":"Last session");ui.text(summary,DropMonitor.summary(report,MonitorService.INTERVAL_MS),14,report.outages>0?ui.warning:ui.accent);page.addView(summary);
        List<DropMonitor.Event> events=new ArrayList<>(report.events);Collections.reverse(events);int shown=0;
        for(DropMonitor.Event e:events){if(++shown>100)break;LinearLayout card=ui.card();
            ui.text(card,DropMonitor.time(e.start)+(e.end>e.start?" – "+DropMonitor.time(e.end)+" ("+DropMonitor.duration(e.seconds())+")":""),12,ui.muted);
            ui.text(card,e.kind,15,e.problem?ui.warning:ui.text);ui.text(card,e.detail,13,ui.text);page.addView(card);}
        if(report.samples>0){
            ui.action(page,"Export log (CSV)",false,()->{try{String csv=new String(java.nio.file.Files.readAllBytes(log.toPath()),StandardCharsets.UTF_8);List<DropMonitor.Sample> all=new ArrayList<>();for(String line:csv.split("\n")){DropMonitor.Sample x=DropMonitor.parse(line);if(x!=null)all.add(x);}
                export(csv+"# "+DropMonitor.summary(DropMonitor.analyse(all,MonitorService.INTERVAL_MS,this::nodeForBssid),MonitorService.INTERVAL_MS)+"\n","Velop-Connection-Monitor-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmm",Locale.ROOT).format(new Date(samples.get(0).time))+".csv","text/csv");}catch(java.io.IOException e){message("Export failed","The log could not be read.");}});
            if(!MonitorService.running)ui.action(page,"Delete log",false,()->{if(log.delete())toast("Log deleted");monitorScreen();});
        }
    }
    private void startMonitor(int minutes){
        if(MonitorService.running)return;
        PrimaryLocator.LocalNetwork network=localNetwork();
        if(network.address.isEmpty()){message("Connect to Wi-Fi","Join your home Wi-Fi first. The monitor checks the Wi-Fi network your phone is on.");return;}
        String velop=primary!=null?primary.host():preferences.getString("lastHost","");
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},71);
        Intent intent=new Intent(this,MonitorService.class).putExtra(MonitorService.EXTRA_MINUTES,minutes).putExtra(MonitorService.EXTRA_GATEWAY,network.gateway).putExtra(MonitorService.EXTRA_PRIMARY,velop);
        startForegroundService(intent);toast("Connection monitor started for "+(minutes/60)+(minutes==60?" hour":" hours"));main.postDelayed(this::monitorScreen,800);
    }
    /** Node name for a phone-side BSSID, using the latest mesh snapshot. */
    private String nodeForBssid(String bssid){
        if(topology==null||bssid.isEmpty())return null;
        for(Topology.Node n:topology.nodes)if(n.macs.contains(bssid))return n.name;
        Topology.Node near=NeighborSignals.nearby(topology,bssid);return near==null?null:near.name;
    }
    private void changePassword(){
        if(!controls())return;JnapClient session=selected();if(session==null){message("Select a node","Choose a connected target in Settings.");return;}LinearLayout body=ui.column();body.setPadding(ui.d(22),0,ui.d(22),0);EditText next=ui.input("New local admin password","",true),again=ui.input("Repeat password","",true),hint=ui.input("Password hint (optional)","",false);body.addView(next);body.addView(again);body.addView(hint);
        AlertDialog d=ui.dialog("Change local password").setMessage("Target "+session.host()+". Changes apply asynchronously; reconnect afterward to verify and remember the new password. "+(session.modernPasswords()?"Use 10–64 printable ASCII characters, uppercase, lowercase, a number and a symbol; no identical consecutive characters.":"This firmware uses the older password policy.")).setView(body).setNegativeButton("Cancel",null).setPositiveButton("Review",null).create();
        d.setOnShowListener(v->d.getButton(-1).setOnClickListener(b->{
            String value=next.getText().toString();if(!value.equals(again.getText().toString())){again.setError("Passwords must match");return;}
            try{AdminPasswordPolicy.validate(value,session.modernPasswords());}catch(IllegalArgumentException e){next.setError(e.getMessage());return;}
            String passwordHint=hint.getText().toString();if(passwordHint.length()>128){hint.setError("Use up to 128 characters");return;}
            char[] secret=value.toCharArray();next.setText("");again.setText("");d.dismiss();
            confirm("Change administrator password?","Apply a new local password to "+session.host()+". Keep it somewhere safe. The password is hidden from logs and previews. This session will disconnect after acceptance; the new password is saved only after a successful reconnection.",true,()->task("Changing the local admin password once…",()->{
                try{SettingsRepository.requireOK(session.write(session.modernPasswords()?"core/SetAdminPassword3":"core/SetAdminPassword2",new JSONObject().put("adminPassword",new String(secret)).put("passwordHint",passwordHint)));vault.forget(session.host());return true;}
                finally{Arrays.fill(secret,'\0');}
            },r->{
                String note="Password change accepted. Allow a few seconds for it to apply, then reconnect using the new password to verify it.";
                if(session==primary){disconnect();login(note);}else{sessions.remove(session.host());session.close();selectedHost=primary==null?"":primary.host();notice=note;back=null;tab="Mesh";showTab();}
            }),()->Arrays.fill(secret,'\0'));
        }));d.show();
    }
    private boolean controls(){if(topology!=null&&topology.demo){message("Connect first","Demo cannot send router commands.");return false;}if(!management){message("Read-only mode","Turn on management controls on the Settings tab to review and submit a change.");return false;}if(busy){toast("Wait for the current operation");return false;}return true;}
    private void confirm(String title,String text,boolean disruptive,Runnable action){confirm(title,text,disruptive,action,()->{});}
    private void confirm(String title,String text,boolean disruptive,Runnable action,Runnable cancelledAction){
        LinearLayout body=ui.column();body.setPadding(ui.d(22),ui.d(8),ui.d(22),0);ui.text(body,text,14,ui.text);EditText word=ui.input("Type APPLY to confirm","",false);if(disruptive)body.addView(word);ScrollView scroll=new ScrollView(this);scroll.addView(body);
        AlertDialog d=ui.dialog(title).setView(scroll).setNegativeButton("Cancel",(x,w)->cancelledAction.run()).setPositiveButton(disruptive?"Apply once":"Confirm",null).setOnCancelListener(x->cancelledAction.run()).create();d.setOnShowListener(v->d.getButton(-1).setOnClickListener(b->{if(disruptive&&!word.getText().toString().equals("APPLY")){word.setError("Type APPLY to continue");return;}if(!management){cancelledAction.run();d.dismiss();message("Controls disabled","Enable management controls before submitting a change.");return;}d.dismiss();action.run();}));d.show();
    }
    private void export(String text,String name){export(text,name,"application/json");}
    private void export(String text,String name,String mime){if(busy){toast("Finish or stop validation before exporting");return;}exportText=text;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.setType(mime);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(intent,70);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==70&&result==RESULT_OK&&data!=null&&data.getData()!=null&&exportText!=null){String content=exportText;exportText=null;Uri uri=data.getData();task("Writing diagnostic log…",()->{try(java.io.OutputStream output=getContentResolver().openOutputStream(uri,"wt")){if(output==null)throw new java.io.IOException();output.write(content.getBytes(StandardCharsets.UTF_8));}return true;},r->toast("File saved"));}else exportText=null;}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if("monitor".equals(screenKey))monitorScreen();}
    private void fillRemembered(String host,EditText input){
        input.setText("");if(!preferences.getBoolean("remember",true))return;char[] saved=vault.load(host,preferences.getString("pin:"+host,""));try{input.setText(new String(saved));}finally{Arrays.fill(saved,'\0');}
    }
    static String hopValue(Topology.Node node){
        if("Wired".equalsIgnoreCase(node.link))return "Not measured (wired firmware placeholder)";
        try{if(Double.parseDouble(node.rate)>0)return node.rate+" Mbps";}catch(Exception ignored){}return "Not reported";
    }
    static String measurementTime(String value){
        if(value==null||value.isEmpty())return "Time not reported";
        try{long seconds=Long.parseLong(value);return DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(seconds*1000));}catch(Exception ignored){return value;}
    }
    private void updates(){
        if(topology!=null)detail("App updates",parent());else{back=()->login("");chrome("App updates",false);}
        showingUpdates=true;
        LinearLayout card=ui.card();ui.heading(card,"Velop Control "+BuildConfig.VERSION_NAME);ui.text(card,"Updates come only from "+ReleaseInfo.REPOSITORY+" releases on GitHub. Router credentials are never sent to GitHub. Android asks you to approve installation.",14,ui.muted);
        Switch automatic=ui.toggle("Check GitHub for updates on launch",preferences.getBoolean("updateChecks",true));card.addView(automatic);automatic.setOnCheckedChangeListener((b,on)->preferences.edit().putBoolean("updateChecks",on).apply());
        ui.action(card,updateBusy?"Update operation in progress…":"Check for updates",true,()->checkUpdates(true));
        if(availableRelease!=null){
            boolean newer=ReleaseInfo.compare(availableRelease.version,BuildConfig.VERSION_NAME)>0;ui.text(card,newer?"Version "+availableRelease.version+" is available":"You have the latest published version",16,ui.accent);
            if(newer){ui.text(card,availableRelease.notes,13,ui.muted);if(downloadedUpdate!=null&&downloadedUpdate.isFile())ui.action(card,"Install verified update",true,this::installUpdate);else ui.action(card,"Download & verify APK",true,this::downloadUpdate);}
        }page.addView(card);
    }
    private void checkUpdates(boolean interactive){
        if(updateBusy){if(interactive)toast("An update operation is already running");return;}updateBusy=true;
        preferences.edit().putLong("lastUpdateCheck",System.currentTimeMillis()).apply();
        updateWorker.execute(()->{try{ReleaseInfo release=new ReleaseUpdater(this).check();main.post(()->{if(isDestroyed())return;updateBusy=false;if(availableRelease==null||!availableRelease.digest.equals(release.digest))downloadedUpdate=null;availableRelease=release;if(interactive&&showingUpdates)updates();else if(ReleaseInfo.compare(release.version,BuildConfig.VERSION_NAME)>0)toast("A Velop Control update is available in Settings → About & updates");});}
            catch(Exception e){main.post(()->{if(isDestroyed())return;updateBusy=false;if(interactive)message("Update check",e instanceof IllegalStateException||e instanceof IllegalArgumentException?e.getMessage():"Unable to check GitHub. Router management remains available locally.");});}});
    }
    private void downloadUpdate(){
        if(updateBusy||availableRelease==null)return;updateBusy=true;ReleaseInfo selected=availableRelease;toast("Downloading and verifying the app update…");
        updateWorker.execute(()->{try{java.io.File file=new ReleaseUpdater(this).download(selected);main.post(()->{if(isDestroyed())return;updateBusy=false;downloadedUpdate=file;availableRelease=selected;if(showingUpdates){updates();message("Update verified","The APK matches GitHub’s checksum and this app’s signing certificate. Tap Install verified update to continue.");}else toast("Update verified. Open Settings → About & updates to install it.");});}
            catch(Exception e){main.post(()->{if(isDestroyed())return;updateBusy=false;downloadedUpdate=null;message("Update not installed",e instanceof SecurityException?e.getMessage():"The download or APK verification failed. The installed app is unchanged.");});}});
    }
    private void installUpdate(){
        if(downloadedUpdate==null||!downloadedUpdate.isFile())return;
        if(!getPackageManager().canRequestPackageInstalls()){
            ui.dialog("Allow app updates").setMessage("Android requires permission for Velop Control to open the package installer. Enable Allow from this source, return here and tap Install verified update again.")
                .setNegativeButton("Cancel",null).setPositiveButton("Open Android settings",(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())))).show();return;
        }
        Intent install=new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://"+getPackageName()+".updates/verified.apk"),"application/vnd.android.package-archive");install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivity(install);}catch(ActivityNotFoundException e){message("Installer unavailable","This Android device does not expose a package installer. The verified APK has not been installed.");}
    }
    private void applyScreenshotPolicy(){if(preferences.getBoolean("screenshots",false))getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);}
    private void key(LinearLayout parent,String label,String value){ui.text(parent,label,12,ui.muted);ui.text(parent,Ui.known(value),15,ui.text);}
    private void message(String title,String text){if(!isFinishing()&&!isDestroyed())ui.dialog(title).setMessage(text).setPositiveButton("OK",null).show();}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    private void schedule(){main.removeCallbacks(poll);if(auto&&resumed&&primary!=null&&!validating)main.postDelayed(poll,30000);}
    private void disconnect(){generation++;cancelled=true;validating=false;main.removeCallbacks(poll);main.removeCallbacks(restartPoll);main.removeCallbacks(onboardingPoll);onboarding=null;onboardingSession=null;onboardingChecking=false;onboardingMessage="";restart=null;restartSession=null;restartChecking=false;screenKey=null;channelScan=false;channelsBefore=null;channelsNow=null;channelNote="";speedRunning=false;speedNow=null;speedHistory=new ArrayList<>();speedNote="";traceRunning=false;traceSession=null;wpsActive=false;wpsNote="";for(JnapClient c:sessions.values())c.close();sessions.clear();primary=null;topology=null;latest.clear();pageStatus.clear();health.clear();selectedHost="";management=false;busy=false;back=null;notice="";nodeFilter="";clientQuery="";scope="All devices";audit=null;exportText=null;}
    @Override public void onBackPressed(){if(back!=null){Runnable action=back;back=null;action.run();}else if(topology!=null&&!tab.equals("Home")){tab="Home";showTab();}else if(topology!=null){ui.dialog("Disconnect from your mesh?").setNegativeButton("Stay",null).setPositiveButton("Disconnect",(d,w)->{disconnect();login("");}).show();}else super.onBackPressed();}
    @Override public void onConfigurationChanged(Configuration configuration){super.onConfigurationChanged(configuration);if(showingOnboarding)onboardingScreen();else if(back==null){if(topology==null)login("");else showTab();}}
    @Override protected void onResume(){super.onResume();resumed=true;schedule();scheduleRestart();scheduleOnboarding();resumeToolPolls();}
    @Override protected void onPause(){resumed=false;main.removeCallbacks(poll);main.removeCallbacks(restartPoll);main.removeCallbacks(onboardingPoll);super.onPause();}
    @Override protected void onDestroy(){if(discovery!=null)discovery.set(true);disconnect();worker.shutdownNow();updateWorker.shutdownNow();discoveryWorker.shutdownNow();history.close();super.onDestroy();}
}
