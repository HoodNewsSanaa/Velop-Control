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
    private RestartMonitor restart;
    private JnapClient restartSession;
    private boolean restartChecking,showingUpdates;
    private final Runnable restartPoll=this::checkRestart;
    private android.content.SharedPreferences preferences;
    private Ui ui;private LinearLayout page,frame;private TextView status;private ProgressBar progress;
    private JnapClient primary;private Topology topology;private HistoryStore history;private CredentialVault vault;
    private ReleaseInfo availableRelease;private java.io.File downloadedUpdate;private boolean updateBusy;
    private final ExecutorService updateWorker=Executors.newSingleThreadExecutor();
    private String selectedHost="",tab="Overview",notice="",scope="All devices",nodeFilter="",clientQuery="";
    private boolean busy,resumed,auto,management,bridge,bridgeKnown,validating;
    private volatile boolean cancelled;
    private volatile int generation;private AuditReport audit;private String exportText;
    private Runnable back;
    private static final String[] TABS={"Overview","Nodes","Devices","Settings","Tools"};
    private final Runnable poll=()->{if(auto&&resumed&&!busy&&primary!=null&&back==null&&!restartActive())refresh();else schedule();};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        preferences=getSharedPreferences("preferences",MODE_PRIVATE);ui=new Ui(this,preferences.getBoolean("dark",true));history=new HistoryStore(this);vault=new CredentialVault(this);
        try(java.io.InputStream in=getAssets().open("settings.json")){SettingsCatalog.load(in);}catch(Exception e){new AlertDialog.Builder(this).setMessage("Settings catalog could not be loaded").setPositiveButton("Close",(d,w)->finish()).show();return;}
        auto=preferences.getBoolean("auto",false);login("");
        if(preferences.getBoolean("updateChecks",true)&&System.currentTimeMillis()-preferences.getLong("lastUpdateCheck",0)>8L*3600000)checkUpdates(false);
    }
    private void chrome(String title,boolean navigation){
        showingUpdates=false;
        ui=new Ui(this,preferences.getBoolean("dark",true));frame=ui.column();frame.setBackgroundColor(ui.bg);setContentView(frame);
        frame.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        getWindow().setStatusBarColor(ui.bg);getWindow().setNavigationBarColor(ui.surface);
        getWindow().getDecorView().setSystemUiVisibility(ui.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout header=ui.row();header.setPadding(ui.d(20),ui.d(9),ui.d(16),ui.d(6));
        LinearLayout identity=ui.column();ui.text(identity,"VELOP CONTROL",11,ui.accent);TextView heading=ui.label(title,23,ui.text,true);identity.addView(heading);header.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
        if(navigation){Button b=ui.button("Refresh",false,this::refresh);header.addView(b,new LinearLayout.LayoutParams(-2,ui.d(46)));}frame.addView(header);
        status=ui.label(busy?"Working…":connectionLabel(),12,ui.muted,false);status.setPadding(ui.d(20),0,ui.d(20),ui.d(6));frame.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);progress.setVisibility(busy?View.VISIBLE:View.GONE);frame.addView(progress,new LinearLayout.LayoutParams(-1,ui.d(3)));
        LinearLayout middle=ui.row();middle.setGravity(Gravity.TOP);frame.addView(middle,new LinearLayout.LayoutParams(-1,0,1));
        boolean wide=getResources().getConfiguration().screenWidthDp>=720;
        if(navigation&&wide)middle.addView(navigation(true),new LinearLayout.LayoutParams(ui.d(150),-1));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);page=ui.column();page.setPadding(ui.d(20),ui.d(14),ui.d(20),ui.d(28));scroll.addView(page,new ScrollView.LayoutParams(-1,-2));middle.addView(scroll,new LinearLayout.LayoutParams(0,-1,1));
        if(navigation&&!wide)frame.addView(navigation(false),new LinearLayout.LayoutParams(-1,ui.d(64)));
    }
    private View navigation(boolean vertical){
        LinearLayout nav=vertical?ui.column():ui.row();nav.setPadding(ui.d(5),ui.d(5),ui.d(5),ui.d(5));nav.setBackgroundColor(ui.surface);
        for(String name:TABS){TextView item=ui.label(name,vertical?15:11,name.equals(tab)?ui.accent:ui.muted,name.equals(tab));item.setGravity(Gravity.CENTER);item.setMinHeight(ui.d(52));item.setContentDescription("Open "+name);item.setBackground(ui.shape(name.equals(tab)?ui.bg:ui.surface,12));
            if(vertical){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,ui.d(58));p.bottomMargin=ui.d(5);nav.addView(item,p);}else nav.addView(item,new LinearLayout.LayoutParams(0,-1,1));
            item.setOnClickListener(v->{tab=name;back=null;showTab();schedule();});
        }return nav;
    }
    private String connectionLabel(){if(topology!=null&&topology.demo)return "DEMO · Sample data · No router connected";if(primary==null)return "Local Android app · Stock Linksys firmware";return primary.host()+" · "+(bridge?"Bridge mode":"Local mesh")+(management?" · Controls enabled":" · Read-only");}
    private void setBusy(boolean value,String message){busy=value;if(progress!=null)progress.setVisibility(value?View.VISIBLE:View.GONE);if(status!=null)status.setText(value?message:connectionLabel());}
    private <T> void task(String message,Callable<T> work,Consumer<T> done){
        if(busy){toast("Wait for the current operation to finish");return;}main.removeCallbacks(poll);setBusy(true,message);int ticket=generation;
        worker.execute(()->{try{if(ticket!=generation)return;T result=work.call();main.post(()->{if(ticket!=generation||isDestroyed()){if(result instanceof AutoCloseable)try{((AutoCloseable)result).close();}catch(Exception ignored){}return;}setBusy(false,"");done.accept(result);schedule();});}
            catch(Exception error){main.post(()->{if(ticket!=generation||isDestroyed())return;setBusy(false,"");auto=false;if(topology!=null&&!topology.demo&&back==null){notice="The last operation failed. Automatic refresh is paused; the previous mesh snapshot is retained.";showTab();}message("Unable to complete",safeMessage(error));schedule();});}});
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
        EditText address=ui.input("Primary Velop IP",preferences.getString("lastHost","192.168.1.1"),false);address.setInputType(android.text.InputType.TYPE_CLASS_PHONE);card.addView(address);
        EditText password=ui.input("Local administrator password","",true);fillRemembered(address.getText().toString(),password);card.addView(password);
        address.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence t,int a,int c,int f){}public void afterTextChanged(Editable e){fillRemembered(e.toString(),password);}public void onTextChanged(CharSequence t,int a,int b,int c){}});
        Switch remember=ui.toggle("Remember passwords on this device",preferences.getBoolean("remember",true));card.addView(remember);remember.setOnCheckedChangeListener((button,on)->{preferences.edit().putBoolean("remember",on).apply();if(!on)vault.forgetAll();});
        ui.action(card,"Connect securely",true,()->connect(address,password,true));ui.text(card,"Remembered passwords are encrypted on this device. Router access stays local; app update checks use GitHub.",12,ui.muted);page.addView(card);
        if(!info.isEmpty())ui.note(page,info);
        ui.action(page,"Explore demo",false,()->{if(busy)return;topology=Topology.demo();tab="Overview";showTab();});
        ui.action(page,"App updates",false,this::updates);ui.text(page,"Android "+BuildConfig.VERSION_NAME+" · Local router access",12,ui.muted);
    }
    private void connect(EditText address,EditText input,boolean asPrimary){
        if(busy)return;final String host;try{host=ReadPolicy.privateAddress(address.getText().toString());}catch(Exception e){address.setError(e.getMessage());return;}
        if(input.length()==0){input.setError("Enter this node’s local administrator password");return;}
        char[] secret=new char[input.length()];input.getText().getChars(0,input.length(),secret,0);input.setText("");
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        int ticket=generation;setBusy(true,"Inspecting local HTTPS certificate…");worker.execute(()->{
            try{JnapClient.Identity identity=JnapClient.inspect(host);main.post(()->{
                if(ticket!=generation||isDestroyed()){Arrays.fill(secret,'\0');return;}setBusy(false,"");
                String known=preferences.getString("pin:"+host,"");Runnable establish=()->{
                    try{JnapClient next=new JnapClient(host,secret,identity.fingerprint);Arrays.fill(secret,'\0');
                        task("Checking local administrator access…",()->{try{JSONObject identityReply=next.read(ReadPolicy.INFO,false);SettingsRepository.requireOK(identityReply);next.authenticate(identityReply.getJSONObject("output"));
                            if(asPrimary)SettingsRepository.requireOK(next.read(ReadPolicy.DEVICES,true));
                            if(preferences.getBoolean("remember",true)){char[] remembered=next.passwordCopy();try{vault.save(host,identity.formatted(),remembered);}catch(Exception e){main.post(()->toast("Connected; password could not be saved on this device"));}finally{Arrays.fill(remembered,'\0');}}return next;}catch(Exception e){next.close();throw e;}},accepted->{
                            JnapClient old=sessions.put(host,accepted);if(old!=null)old.close();selectedHost=host;
                            preferences.edit().putString("pin:"+host,identity.formatted()).apply();
                            if(asPrimary||primary!=null&&host.equals(primary.host())){primary=accepted;preferences.edit().putString("lastHost",host).apply();topology=null;latest.clear();tab="Overview";back=null;refresh();}
                            else{notice="Connected directly to "+host+". Other node sessions are unchanged.";back=null;tab="Nodes";showTab();}
                        });
                    }catch(Exception e){Arrays.fill(secret,'\0');message("Connection failed",safeMessage(e));}
                };
                if(known.equals(identity.formatted()))establish.run();else ui.dialog(known.isEmpty()?"Trust this local router?":"Router certificate changed")
                    .setMessage("Address: "+host+"\nCertificate: "+identity.subject+"\n\nSHA-256\n"+identity.formatted()+"\n\nConfirm this is your router on a trusted local network. Credentials have not been sent. This certificate will be pinned for future connections.")
                    .setNegativeButton("Cancel",(d,w)->Arrays.fill(secret,'\0')).setPositiveButton("Trust & connect",(d,w)->establish.run()).setOnCancelListener(d->Arrays.fill(secret,'\0')).show();
            });}catch(Exception e){Arrays.fill(secret,'\0');main.post(()->{if(ticket==generation&&!isDestroyed()){setBusy(false,"");message("Could not connect",safeMessage(e));}});}
        });
    }
    private void connectNode(Topology.Node node){
        if(node.ip==null||node.ip.isEmpty()){message("Address unavailable","Refresh the inventory to find this node’s address.");return;}
        LinearLayout fields=ui.column();fields.setPadding(ui.d(22),ui.d(8),ui.d(22),0);ui.text(fields,"Use the local router admin password. Saved credentials are specific to this node and certificate. A failed connection leaves the primary session connected.",14,ui.muted);
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
        if(topology==null){if(primary!=null){chrome(tab,true);ui.note(page,"Read the mesh inventory to populate this view.");ui.action(page,"Read mesh",true,this::refresh);}else login("");return;}
        chrome(tab,true);if(!notice.isEmpty())ui.note(page,notice);
        if(!topology.demo)ui.text(page,"Snapshot read "+DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(topology.observedAt)),12,ui.muted);
        restartCard();
        switch(tab){case "Overview":overview();break;case "Nodes":nodes();break;case "Devices":devices();break;case "Settings":settings();break;default:tools();}
    }
    private void overview(){
        int seen=(int)topology.clients.stream().filter(c->c.seen).count();int active=(int)topology.nodes.stream().filter(n->n.seen).count();
        List<View> tiles=new ArrayList<>();LinearLayout mesh=ui.card();ui.metric(mesh,"Nodes reported",active+" / "+topology.nodes.size());ui.text(mesh,"Stock Intelligent Mesh",13,ui.muted);tiles.add(mesh);
        LinearLayout devices=ui.card();ui.metric(devices,"Clients with connection reports",String.valueOf(seen));ui.text(devices,topology.clients.size()+" known devices in inventory",13,ui.muted);tiles.add(devices);LinearLayout metrics=ui.row();metrics.setGravity(Gravity.TOP);for(int i=0;i<tiles.size();i++){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.bottomMargin=ui.d(10);if(i==0)p.rightMargin=ui.d(6);else p.leftMargin=ui.d(6);metrics.addView(tiles.get(i),p);}page.addView(metrics);
        if(topology.demo)ui.text(page,"Sample planned network",12,ui.muted);
        LinearLayout graph=ui.card();ui.heading(graph,"Mesh connections");graph.addView(new TopologyView(this,topology,this::node),new LinearLayout.LayoutParams(-1,ui.d(Math.max(220,topology.nodes.size()*86))));ui.text(graph,"Blue dashed: wired · Green: wireless\nLinks show the parent reported by firmware. Cable paths and automatic parent selection stay under Linksys control.",12,ui.muted);page.addView(graph);
        int weak=0;for(Topology.Node n:topology.nodes){try{if(Double.parseDouble(n.rssi)<=-75)weak++;}catch(Exception ignored){}}
        LinearLayout health=ui.card();ui.heading(health,"Network insights");ui.text(health,weak>0?weak+" weak wireless backhaul report"+(weak>1?"s":"")+". Check node placement and the reported parent.":"No weak wireless backhaul signal in the available reports.",15,weak>0?ui.warning:ui.text);
        int missing=(int)topology.nodes.stream().filter(n->!n.wirelessGroupReported).count();if(missing>0)ui.text(health,missing+" node(s) missing from the central wireless-client groups. This does not mean they have zero clients.",13,ui.muted);
        ui.action(health,"Inspect nodes",false,()->{tab="Nodes";showTab();});page.addView(health);
        ui.note(page,"Connection counts and signals are firmware snapshots. Missing values stay unknown; wireless throughput is a past child-to-parent test, not an internet speed test.");
    }
    private void nodes(){
        ui.text(page,"Tap a node for its parent, firmware and attached devices.",14,ui.muted);List<View> cards=new ArrayList<>();
        for(Topology.Node n:topology.nodes){LinearLayout card=ui.card();ui.text(card,n.primary?"PRIMARY MESH NODE":"MESH NODE",11,ui.accent);ui.heading(card,n.name);ui.text(card,Ui.known(n.ip)+" · "+Ui.known(n.model),14,ui.text);
            ui.text(card,(n.seen?"Reported in snapshot":"Status not reported")+" · "+(n.primary?"Mesh coordinator":Ui.known(n.link)),13,ui.muted);
            if(!n.primary)ui.text(card,("Wired".equalsIgnoreCase(n.link)?"Firmware parent (unverified cable path): ":"Reported parent: ")+(n.parentId==null?Ui.known(n.parentIp):topology.nodeName(n.parentId)),13,ui.muted);
            if("Wireless".equalsIgnoreCase(n.link)){ui.text(card,Topology.backhaulRadio(n),14,ui.accent);if(n.rssi!=null&&!n.rssi.isEmpty())ui.text(card,"Backhaul signal "+n.rssi+" dBm",14,ui.accent);ui.text(card,"Link rate: "+(n.linkRate.isEmpty()?"Unavailable":n.linkRate+" Mbps"),13,ui.muted);ui.text(card,"Child → parent test: "+hopValue(n)+" · "+measurementTime(n.backhaulTimestamp),12,ui.muted);}
            ui.text(card,n.clients+" assigned client reports"+(n.wirelessGroupReported?"":" · Group incomplete"),12,ui.muted);ui.action(card,"View node",false,()->node(n));cards.add(card);
        }ui.tiles(page,cards);
    }
    private void detail(String title,Runnable previous){back=previous;chrome(title,true);ui.action(page,"‹ Back",false,()->{Runnable action=back;back=null;action.run();});}
    private void node(Topology.Node n){
        detail(n.name,this::showTab);LinearLayout card=ui.card();ui.heading(card,"Node details");key(card,"Status",n.seen?"Reported in snapshot":"Not currently reported");key(card,"Address",n.ip);key(card,"Model",n.model);key(card,"Firmware",n.firmware);key(card,"Connection",n.primary?"Primary mesh node":n.link);if(!n.primary)key(card,"Parent",n.parentId==null?n.parentIp:topology.nodeName(n.parentId));key(card,"Backhaul signal",n.rssi==null||n.rssi.isEmpty()?"Not reported":n.rssi+" dBm");key(card,"Wireless backhaul radio",Topology.backhaulRadio(n));key(card,"Reported wireless link rate",n.linkRate.isEmpty()?"Not available from firmware":n.linkRate+" Mbps");if(!n.linkRateSource.isEmpty()){key(card,"Link-rate source",n.linkRateSource);key(card,"Link-rate report time",measurementTime(n.linkRateTimestamp));}key(card,"Last throughput measurement",measurementTime(n.backhaulTimestamp));key(card,"Child → parent throughput",hopValue(n));key(card,"Assigned client reports",n.clients+(n.wirelessGroupReported?"":" (central group missing)"));page.addView(card);
        ui.action(page,"View devices on this node",true,()->{nodeFilter=n.id;scope="All devices";tab="Devices";back=null;showTab();});
        NodeHealth observation=health.get(n.ip);
        key(card,"Uptime at last direct read",observation==null?"Not read yet":formatUptime(observation.uptime));
        if(observation!=null)key(card,"Status read",DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(observation.readAt)));
        if(!topology.demo){
            JnapClient direct=sessions.get(n.ip);if(direct==null)ui.action(page,"Connect directly to this node",false,()->connectNode(n));
            else{ui.action(page,"Read current uptime",false,()->task("Reading node uptime…",()->{JSONObject r=direct.read("diagnostics/GetSystemStats",true);SettingsRepository.requireOK(r);return RestartMonitor.uptime(r);},uptime->{if(uptime>=0)health.put(n.ip,new NodeHealth(uptime));node(n);if(uptime<0)message("Uptime unavailable","This firmware did not return a valid uptime value.");}));ui.action(page,"Reconnect / replace password",false,()->connectNode(n));ui.action(page,"Settings for this node",false,()->{selectedHost=n.ip;tab="Settings";back=null;showTab();});ui.action(page,"Read node diagnostics",false,()->nodeDiagnostics(n,direct));ui.action(page,n.primary?"Restart primary node…":"Restart this node…",false,()->reboot(n,direct));}
            ui.action(page,"Rename node…",false,()->rename(n));
            if(direct!=null&&"Wired".equalsIgnoreCase(n.link))ui.action(page,"Inspect wired neighbors (experimental)",false,()->wiredNeighbors(n,direct));
        }
        ui.note(page,"Manual parent assignment is unavailable on the confirmed stock Intelligent Mesh interface. Automatic steering and backhaul selection remain under firmware control.");
    }
    private void devices(){
        EditText search=ui.input("Search name, IP, MAC or access point",clientQuery,false);page.addView(search);LinearLayout filters=ui.row();Spinner state=new Spinner(ui.dialogContext());state.setAdapter(new ArrayAdapter<>(ui.dialogContext(),android.R.layout.simple_spinner_dropdown_item,new String[]{"All devices","Reported connected","No connection report","Wireless","Wired"}));
        state.setSelection(Arrays.asList("All devices","Reported connected","No connection report","Wireless","Wired").indexOf(scope));filters.addView(state,new LinearLayout.LayoutParams(0,ui.d(50),1));
        List<String> names=new ArrayList<>(),ids=new ArrayList<>();names.add("All nodes");ids.add("");for(Topology.Node n:topology.nodes){names.add(n.name);ids.add(n.id);}Spinner accessPoint=new Spinner(ui.dialogContext());accessPoint.setAdapter(new ArrayAdapter<>(ui.dialogContext(),android.R.layout.simple_spinner_dropdown_item,names));accessPoint.setSelection(Math.max(0,ids.indexOf(nodeFilter)));filters.addView(accessPoint,new LinearLayout.LayoutParams(0,ui.d(50),1));page.addView(filters);
        TextView count=ui.text(page,"",12,ui.muted);LinearLayout list=ui.column();page.addView(list);
        List<Topology.Client> sorted=new ArrayList<>(topology.clients);sorted.sort(Comparator.comparing((Topology.Client c)->!c.seen).thenComparing(c->c.name.toLowerCase(Locale.ROOT)));
        Runnable fill=()->{list.removeAllViews();clientQuery=search.getText().toString();String q=clientQuery.toLowerCase(Locale.ROOT);scope=String.valueOf(state.getSelectedItem());nodeFilter=ids.get(accessPoint.getSelectedItemPosition());int shown=0;
            for(Topology.Client c:sorted){String text=c.name+" "+c.ip+" "+c.mac+" "+topology.nodeName(c.nodeId);if(!text.toLowerCase(Locale.ROOT).contains(q)||!nodeFilter.isEmpty()&&!nodeFilter.equals(c.nodeId))continue;
                if(scope.equals("Reported connected")&&!c.seen||scope.equals("No connection report")&&c.seen||scope.equals("Wireless")&&!"Wireless".equalsIgnoreCase(c.type)||scope.equals("Wired")&&!"Wired".equalsIgnoreCase(c.type))continue;
                LinearLayout card=ui.card();ui.heading(card,c.name);ui.text(card,Ui.known(c.ip)+" · "+Ui.known(c.type),14,ui.text);ui.text(card,topology.nodeName(c.nodeId)+" · "+(c.seen?"Connection reported":"No current connection report"),12,ui.muted);
                if(!c.rssi.isEmpty()||!c.rate.isEmpty())ui.text(card,(c.rssi.isEmpty()?"":c.rssi+" dBm")+(c.rssi.isEmpty()||c.rate.isEmpty()?"":" · ")+(c.rate.isEmpty()?"":c.rate+" Mbps negotiated"),13,ui.accent);ui.action(card,"Device details",false,()->device(c));list.addView(card);shown++;
            }count.setText(shown+" of "+topology.clients.size()+" known devices");if(shown==0)ui.note(list,"No devices match these filters.");
        };
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){fill.run();}});
        AdapterView.OnItemSelectedListener listener=new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){fill.run();}};state.setOnItemSelectedListener(listener);accessPoint.setOnItemSelectedListener(listener);fill.run();
    }
    private void device(Topology.Client c){
        detail(c.name,this::showTab);LinearLayout card=ui.card();key(card,"IP address",c.ip);key(card,"MAC address",c.mac);key(card,"Access point",topology.nodeName(c.nodeId));key(card,"Connection",c.type);key(card,"Status",c.seen?"Reported connected":"No current connection report");key(card,"Signal",c.rssi==null||c.rssi.isEmpty()?"Not reported":c.rssi+" dBm");key(card,"Negotiated rate",c.rate==null||c.rate.isEmpty()?"Not reported":c.rate+" Mbps");key(card,"Router observation time",c.timestamp);page.addView(card);
        if(preferences.getBoolean("history",false)&&!topology.demo)ui.action(page,"Connection history",false,()->showHistory(c.mac));ui.note(page,"Signal, rate and association are last reported values. Stock firmware does not provide confirmed per-device internet usage counters here.");
    }
    private JnapClient selected(){return sessions.get(selectedHost);}
    private void settings(){
        targetPicker();ui.note(page,"Read and write support varies by model, node password and firmware. Writes in this preview have not been validated on your hardware. Start with Tools → Validate read-only APIs.");
        EditText search=ui.input("Find a setting","",false);page.addView(search);LinearLayout list=ui.column();page.addView(list);
        Runnable fill=()->{list.removeAllViews();String q=search.getText().toString().toLowerCase(Locale.ROOT),group="";
            for(SettingsCatalog.Spec spec:SettingsCatalog.ALL){if(!(spec.title+" "+spec.group+" "+spec.note).toLowerCase(Locale.ROOT).contains(q))continue;if(!spec.group.equals(group)){group=spec.group;ui.heading(list,group);}
                LinearLayout card=ui.card();ui.text(card,spec.title,16,ui.text);String state=pageStatus.get(selectedHost+":"+spec.id);ui.text(card,spec.read.isEmpty()?"Unavailable on confirmed stock interfaces":state!=null?state:spec.editable()?"Supported protocol · Read to validate":"Read-only information",12,ui.muted);
                if(spec.routerOnly&&(bridge||!bridgeKnown))ui.text(card,bridge?"Managed by your main router in bridge mode":"Routing mode unconfirmed · View only",12,ui.warning);ui.action(card,spec.read.isEmpty()?"Details":"Open",false,()->openSettings(spec));list.addView(card);
            }
        };fill.run();search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){fill.run();}});
    }
    private void targetPicker(){
        LinearLayout card=ui.card();ui.text(card,"SETTINGS TARGET",11,ui.accent);
        if(topology.demo){ui.text(card,"Demo · Connect to read actual settings",14,ui.text);page.addView(card);return;}
        List<String> hosts=new ArrayList<>(sessions.keySet()),labels=new ArrayList<>();for(String host:hosts){String name=host;for(Topology.Node n:topology.nodes)if(host.equals(n.ip))name=n.name+" · "+host;labels.add(name);}
        Spinner selector=new Spinner(ui.dialogContext());selector.setAdapter(new ArrayAdapter<>(ui.dialogContext(),android.R.layout.simple_spinner_dropdown_item,labels));selector.setSelection(Math.max(0,hosts.indexOf(selectedHost)));card.addView(selector);selector.setMinimumHeight(ui.d(48));
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int i,long id){if(i<hosts.size()&&!selectedHost.equals(hosts.get(i))){selectedHost=hosts.get(i);pageStatus.clear();showTab();}}});ui.text(card,"To add another target, open Nodes and connect directly to that node.",12,ui.muted);page.addView(card);
    }
    private void openSettings(SettingsCatalog.Spec spec){
        if(spec.read.isEmpty()){message(spec.title,spec.note);return;}if(topology.demo){demoSettings(spec);return;}
        JnapClient session=selected();if(session==null){message("Connect first","Select a connected node or connect directly from Nodes.");return;}
        task("Reading "+spec.title.toLowerCase(Locale.ROOT)+"…",()->new SettingsRepository(session).load(spec),snapshot->{pageStatus.put(session.host()+":"+spec.id,"Read validated · Writes untested");edit(snapshot,session);});
    }
    private void edit(SettingsRepository.Snapshot snapshot,JnapClient session){
        SettingsCatalog.Spec spec=snapshot.spec;detail(spec.title,this::showTab);ui.text(page,"Target "+session.host(),12,ui.accent);boolean allowed=management&&(!spec.routerOnly||bridgeKnown&&!bridge);
        if(!spec.note.isEmpty())ui.note(page,spec.note);if(bridge&&spec.routerOnly)ui.note(page,"This mesh is in bridge mode. Configure routing features on your OpenWrt or OPNsense main router.");
        if(!bridgeKnown&&spec.routerOnly)ui.note(page,"Routing mode could not be confirmed. Return to a main tab and refresh before enabling these controls.");
        SettingsForm form=new SettingsForm(ui,snapshot,allowed);page.addView(form.view);
        if(spec.editable()&&allowed)ui.action(page,"Review changes",true,()->{try{SettingsRepository repo=new SettingsRepository(session);SettingsRepository.Change change=repo.prepare(snapshot,form.collect());confirm("Apply "+spec.title.toLowerCase(Locale.ROOT)+"?",change.preview+"\n\n"+spec.impact,spec.disruption,()->{
            if(!management){message("Controls disabled","Enable management controls in Tools first.");return;}
            task("Applying once, then verifying…",()->repo.apply(change,true),outcome->{message("Router response",outcome.message);notice=outcome.message;back=null;showTab();});
        });}catch(Exception e){message("Check the changes",safeMessage(e));}});
        ui.action(page,"Reload from router",false,()->openSettings(spec));
    }
    private void demoSettings(SettingsCatalog.Spec spec){
        detail(spec.title,this::showTab);ui.note(page,"Demo uses sample data. Connect to your router to validate and manage this page.");
        LinearLayout card=ui.card();ui.heading(card,"Available controls");for(JSONObject f:spec.fields)if(!f.optString("type").equals("identity")&&!f.optString("type").equals("preserve"))ui.text(card,f.optString("label"),14,ui.text);if(spec.fields.isEmpty())ui.text(card,"Read firmware-reported information",14,ui.muted);page.addView(card);if(!spec.note.isEmpty())ui.note(page,spec.note);
    }
    private void tools(){
        LinearLayout checks=ui.card();ui.heading(checks,"Validate your firmware");ui.text(checks,"Runs read-only requests one at a time on connected node sessions. Authentication failure stops tests for that node. No settings are changed.",14,ui.muted);
        if(audit!=null)ui.text(checks,audit.summary(),14,ui.accent);ui.action(checks,validating?"Validation is running…":"Run sequential validation",true,this::validateAll);if(validating)ui.action(checks,"Stop after current request",false,()->{cancelled=true;toast("Stopping after the current read");});
        if(audit!=null)ui.action(checks,"Export diagnostic log",false,()->export(audit.json(),"Velop-Validation-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date())+".json"));page.addView(checks);
        LinearLayout app=ui.card();ui.heading(app,"App preferences");Switch controls=ui.toggle("Enable management controls",management);app.addView(controls);controls.setOnCheckedChangeListener((b,on)->{if(on){controls.setChecked(false);ui.dialog("Enable management controls?").setMessage("This preview can send supported configuration commands. Writes have not been tested on your hardware. Every change requires a separate review. Controls turn off when you disconnect.").setNegativeButton("Keep read-only",null).setPositiveButton("Enable controls",(d,w)->{management=true;showTab();}).show();}else{management=false;}});
        Switch polling=ui.toggle("Refresh every 30 seconds while open",auto);app.addView(polling);polling.setOnCheckedChangeListener((b,on)->{auto=on;preferences.edit().putBoolean("auto",on).apply();schedule();});
        Switch dark=ui.toggle("Dark appearance",ui.dark);app.addView(dark);dark.setOnCheckedChangeListener((b,on)->{preferences.edit().putBoolean("dark",on).apply();showTab();});
        Switch historical=ui.toggle("Save local connection history (7 days)",preferences.getBoolean("history",false));app.addView(historical);historical.setOnCheckedChangeListener((b,on)->preferences.edit().putBoolean("history",on).apply());ui.text(app,"History stores device labels, MAC addresses and association reports inside this app. Exported validation logs exclude these identifiers.",12,ui.muted);
        ui.action(app,"View connection history",false,()->showHistory(null));ui.action(app,"Clear local history",false,()->ui.dialog("Clear connection history?").setNegativeButton("Keep",null).setPositiveButton("Clear",(d,w)->task("Clearing local history…",()->{history.clear();return true;},r->toast("Local history cleared"))).show());Switch remembered=ui.toggle("Remember passwords on this device",preferences.getBoolean("remember",true));app.addView(remembered);remembered.setOnCheckedChangeListener((button,on)->{preferences.edit().putBoolean("remember",on).apply();if(!on)vault.forgetAll();});
        ui.action(app,"Forget saved passwords",false,()->ui.dialog("Forget saved passwords?").setMessage("Current sessions stay connected. You will need the passwords on your next connection.").setNegativeButton("Keep",null).setPositiveButton("Forget",(d,w)->{vault.forgetAll();toast("Saved passwords removed");}).show());
        ui.action(app,"App updates",false,this::updates);page.addView(app);
        LinearLayout diagnostics=ui.card();ui.heading(diagnostics,"Network tools");ui.action(diagnostics,"Ping from selected router",false,this::ping);ui.action(diagnostics,"Optimize wireless channels…",false,this::optimize);ui.action(diagnostics,"Change local admin password…",false,this::changePassword);ui.action(diagnostics,"API capability results",false,this::capabilities);page.addView(diagnostics);
        LinearLayout about=ui.card();ui.heading(about,"Velop Control "+BuildConfig.VERSION_NAME);ui.text(about,"Independent Android client for stock Linksys mesh routers. Local JNAP over pinned HTTPS. GitHub is contacted only for app updates. No advertisements, WebView or separate backend.",14,ui.muted);ui.text(about,"Read support is firmware-dependent. Manual parent locking, node onboarding, full backup/restore and cloud subscription features are unavailable in this build.",13,ui.muted);ui.action(about,"Disconnect",false,()->{disconnect();login("");});page.addView(about);
    }
    private void validateAll(){
        if(topology.demo||primary==null){message("Connect to your mesh","Validation needs a local router session.");return;}if(busy){toast("Wait for the current operation");return;}
        audit=new AuditReport();cancelled=false;validating=true;main.removeCallbacks(poll);setBusy(true,"Starting sequential read-only validation…");showTab();int ticket=generation;List<JnapClient> targets=new ArrayList<>(sessions.values());
        List<String> actions=new ArrayList<>(Arrays.asList(ReadPolicy.INFO,ReadPolicy.DEVICES,ReadPolicy.BACKHAUL,ReadPolicy.NODE_CONNECTIONS,ReadPolicy.CONNECTIONS,ReadPolicy.MODE,"diagnostics/GetSystemStats","nodes/setup/GetSelectedChannels","router/GetEthernetPortConnections"));for(SettingsCatalog.Spec s:SettingsCatalog.ALL)if(!s.read.isEmpty()&&!actions.contains(s.read))actions.add(s.read);
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
            main.post(()->{if(ticket!=generation||isDestroyed())return;validating=false;setBusy(false,"");pageStatus.putAll(checked);notice=(cancelled?"Validation stopped. ":"Validation finished. ")+report.summary();back=null;tab="Tools";showTab();schedule();});
        });
    }
    private void nodeDiagnostics(Topology.Node node,JnapClient session){
        task("Reading diagnostics for "+node.name+"…",()->{
            Map<String,JSONObject> data=new LinkedHashMap<>();for(String a:new String[]{ReadPolicy.INFO,ReadPolicy.MODE,"diagnostics/GetSystemStats","router/GetEthernetPortConnections","nodes/setup/GetSelectedChannels"}){try{data.put(a,session.read(a,!a.equals(ReadPolicy.INFO)));}catch(SecurityException e){throw e;}catch(Exception e){data.put(a,new JSONObject().put("result","Unavailable"));}}return data;
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
    private void capabilities(){detail("API capability results",this::showTab);for(Map.Entry<String,String> e:topology.capabilities.entrySet()){LinearLayout card=ui.card();ui.text(card,e.getKey(),13,ui.text);ui.text(card,e.getValue(),13,ui.accent);page.addView(card);}ui.note(page,"A successful read confirms that read request only. It does not validate a matching write command.");}
    private void showHistory(String device){if(topology.demo){message("Demo","Connect to a router and enable local history to record association changes.");return;}task("Reading local history…",()->history.recent(device),events->{detail("Connection history",this::showTab);ui.note(page,"App observations only. Gaps while the app was closed are unknown. History is retained for seven days.");if(events.isEmpty())ui.note(page,"No recorded observations yet. Enable local history in Tools, then refresh the mesh.");for(String event:events){LinearLayout card=ui.card();ui.text(card,event,14,ui.text);page.addView(card);}});}
    private void reboot(Topology.Node node,JnapClient session){
        if(!controls())return;
        if(restartActive()){message("Restart monitoring in progress","Wait for the current observation to finish before requesting another restart.");return;}
        confirm("Restart "+node.name+"?","This restarts the selected node at "+session.host()+". Devices and child nodes using it may disconnect until the mesh recovers. The app sends the command once, then watches for recovery while open.",true,()->task("Reading identity and uptime, then sending restart once…",()->{
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
        },monitor->{restart=monitor;restartSession=session;if(monitor.uptimeBefore>=0)health.put(session.host(),new NodeHealth(monitor.uptimeBefore));back=null;tab="Nodes";showTab();scheduleRestart();}));
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
                if(back==null&&(tab.equals("Nodes")||tab.equals("Overview")))showTab();
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
    private void pollPing(JnapClient session,int count){main.postDelayed(()->{if(isDestroyed()||!sessions.containsValue(session))return;task("Reading router ping result…",()->session.read("diagnostics/GetPingStatus",true),r->{detail("Router ping",this::showTab);showSafeValues(page,r.optJSONObject("output"),0);if(count<3)ui.action(page,"Read ping status again",false,()->pollPing(session,count+1));});},1500);}
    private void optimize(){
        if(!controls())return;if(primary==null)return;confirm("Optimize wireless channels?","The primary mesh node will start automatic channel selection. Wireless clients and backhaul links may disconnect while the firmware scans and retunes radios. Parent selection stays automatic.",true,()->task("Starting channel optimization once…",()->{SettingsRepository.requireOK(primary.write("nodes/setup/StartAutoChannelSelection",new JSONObject()));return true;},r->message("Optimization requested","Allow the mesh to settle, then refresh. The app will not repeat the request.")));
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
                if(session==primary){disconnect();login(note);}else{sessions.remove(session.host());session.close();selectedHost=primary==null?"":primary.host();notice=note;back=null;tab="Nodes";showTab();}
            }),()->Arrays.fill(secret,'\0'));
        }));d.show();
    }
    private boolean controls(){if(topology!=null&&topology.demo){message("Connect first","Demo cannot send router commands.");return false;}if(!management){message("Read-only mode","Enable management controls in Tools to review and submit a change.");return false;}if(busy){toast("Wait for the current operation");return false;}return true;}
    private void confirm(String title,String text,boolean disruptive,Runnable action){confirm(title,text,disruptive,action,()->{});}
    private void confirm(String title,String text,boolean disruptive,Runnable action,Runnable cancelledAction){
        LinearLayout body=ui.column();body.setPadding(ui.d(22),ui.d(8),ui.d(22),0);ui.text(body,text,14,ui.text);EditText word=ui.input("Type APPLY to confirm","",false);if(disruptive)body.addView(word);ScrollView scroll=new ScrollView(this);scroll.addView(body);
        AlertDialog d=ui.dialog(title).setView(scroll).setNegativeButton("Cancel",(x,w)->cancelledAction.run()).setPositiveButton(disruptive?"Apply once":"Confirm",null).setOnCancelListener(x->cancelledAction.run()).create();d.setOnShowListener(v->d.getButton(-1).setOnClickListener(b->{if(disruptive&&!word.getText().toString().equals("APPLY")){word.setError("Type APPLY to continue");return;}if(!management){cancelledAction.run();d.dismiss();message("Controls disabled","Enable management controls before submitting a change.");return;}d.dismiss();action.run();}));d.show();
    }
    private void export(String text,String name){if(busy){toast("Finish or stop validation before exporting");return;}exportText=text;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.setType("application/json");intent.addCategory(Intent.CATEGORY_OPENABLE);intent.putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(intent,70);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==70&&result==RESULT_OK&&data!=null&&data.getData()!=null&&exportText!=null){String content=exportText;exportText=null;Uri uri=data.getData();task("Writing diagnostic log…",()->{try(java.io.OutputStream output=getContentResolver().openOutputStream(uri,"wt")){if(output==null)throw new java.io.IOException();output.write(content.getBytes(StandardCharsets.UTF_8));}return true;},r->toast("Diagnostic log saved"));}else exportText=null;}
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
        if(topology!=null)detail("App updates",this::showTab);else{back=()->login("");chrome("App updates",false);ui.action(page,"‹ Back",false,()->{back=null;login("");});}
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
        updateWorker.execute(()->{try{ReleaseInfo release=new ReleaseUpdater(this).check();main.post(()->{if(isDestroyed())return;updateBusy=false;if(availableRelease==null||!availableRelease.digest.equals(release.digest))downloadedUpdate=null;availableRelease=release;if(interactive&&showingUpdates)updates();else if(ReleaseInfo.compare(release.version,BuildConfig.VERSION_NAME)>0)toast("A Velop Control update is available in Tools → App updates");});}
            catch(Exception e){main.post(()->{if(isDestroyed())return;updateBusy=false;if(interactive)message("Update check",e instanceof IllegalStateException||e instanceof IllegalArgumentException?e.getMessage():"Unable to check GitHub. Router management remains available locally.");});}});
    }
    private void downloadUpdate(){
        if(updateBusy||availableRelease==null)return;updateBusy=true;ReleaseInfo selected=availableRelease;toast("Downloading and verifying the app update…");
        updateWorker.execute(()->{try{java.io.File file=new ReleaseUpdater(this).download(selected);main.post(()->{if(isDestroyed())return;updateBusy=false;downloadedUpdate=file;availableRelease=selected;if(showingUpdates){updates();message("Update verified","The APK matches GitHub’s checksum and this app’s signing certificate. Tap Install verified update to continue.");}else toast("Update verified. Open Tools → App updates to install it.");});}
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
    private void key(LinearLayout parent,String label,String value){ui.text(parent,label,12,ui.muted);ui.text(parent,Ui.known(value),15,ui.text);}
    private void message(String title,String text){if(!isFinishing()&&!isDestroyed())ui.dialog(title).setMessage(text).setPositiveButton("OK",null).show();}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    private void schedule(){main.removeCallbacks(poll);if(auto&&resumed&&primary!=null&&!validating)main.postDelayed(poll,30000);}
    private void disconnect(){generation++;cancelled=true;validating=false;main.removeCallbacks(poll);main.removeCallbacks(restartPoll);restart=null;restartSession=null;restartChecking=false;for(JnapClient c:sessions.values())c.close();sessions.clear();primary=null;topology=null;latest.clear();pageStatus.clear();health.clear();selectedHost="";management=false;busy=false;back=null;notice="";nodeFilter="";clientQuery="";scope="All devices";audit=null;exportText=null;}
    @Override public void onBackPressed(){if(back!=null){Runnable action=back;back=null;action.run();}else if(topology!=null&&!tab.equals("Overview")){tab="Overview";showTab();}else if(topology!=null){ui.dialog("Disconnect from your mesh?").setNegativeButton("Stay",null).setPositiveButton("Disconnect",(d,w)->{disconnect();login("");}).show();}else super.onBackPressed();}
    @Override public void onConfigurationChanged(Configuration configuration){super.onConfigurationChanged(configuration);if(back==null){if(topology==null)login("");else showTab();}}
    @Override protected void onResume(){super.onResume();resumed=true;schedule();scheduleRestart();}
    @Override protected void onPause(){resumed=false;main.removeCallbacks(poll);main.removeCallbacks(restartPoll);super.onPause();}
    @Override protected void onDestroy(){disconnect();worker.shutdownNow();updateWorker.shutdownNow();history.close();super.onDestroy();}
}
