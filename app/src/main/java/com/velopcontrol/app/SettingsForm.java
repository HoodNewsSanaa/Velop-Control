package com.velopcontrol.app;

import android.app.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.function.Supplier;

/** Native controls over an allowlisted settings projection. Secrets never enter a WebView. */
public final class SettingsForm {
    private final Ui ui;
    private final SettingsRepository.Snapshot snapshot;
    private final SettingsCatalog.Spec spec;
    private final boolean editable;
    private final JSONObject draft;
    private final Map<String,Supplier<Object>> readers=new LinkedHashMap<>();
    public final LinearLayout view;
    public SettingsForm(Ui ui,SettingsRepository.Snapshot snapshot,boolean editable){
        this.ui=ui;this.snapshot=snapshot;this.spec=snapshot.spec;this.editable=editable&&spec.editable();
        draft=snapshot.editable();view=ui.column();render();
    }
    public JSONObject collect(){
        for(Map.Entry<String,Supplier<Object>> e:readers.entrySet())Json.set(draft,e.getKey(),e.getValue().get());
        return Json.copy(draft);
    }
    private void render(){
        readers.clear();view.removeAllViews();
        if(!spec.editable()){display(view,snapshot.raw,"",0,new int[]{0});return;}
        LinearLayout general=ui.card();int count=0;
        for(JSONObject field:spec.fields)if(!field.optString("path").contains(".*")&&visible(field,field.optString("path"))){field(general,field,field.optString("path"));count++;}
        if(count>0)view.addView(general);
        Set<String> prefixes=new LinkedHashSet<>();
        for(JSONObject f:spec.fields){String p=f.optString("path");int at=p.indexOf(".*");if(at>0)prefixes.add(p.substring(0,at));}
        for(String prefix:prefixes){
            Object a=Json.get(draft,prefix);JSONArray list=a instanceof JSONArray?(JSONArray)a:new JSONArray();
            JSONObject definition=null;for(JSONObject def:spec.arrays)if(def.optString("path").equals(prefix))definition=def;
            String group=definition==null?Json.label(prefix):definition.optString("label");ui.heading(view,group);
            for(int i=0;i<list.length();i++){
                LinearLayout card=ui.card();String identity="";Object row=list.opt(i);
                if(row instanceof JSONObject)identity=((JSONObject)row).optString("radioID");
                ui.heading(card,identity.isEmpty()?"Entry "+(i+1):radioLabel(identity));
                for(JSONObject f:spec.fields)if(f.optString("path").startsWith(prefix+".*.")){
                    String path=prefix+"."+i+f.optString("path").substring(prefix.length()+2);
                    if(visible(f,path))field(card,f,path);
                }
                if(editable&&definition!=null){final int index=i;ui.action(card,"Remove entry",false,()->ui.dialog("Remove this entry?").setMessage("This edits the draft. Review changes before applying.")
                    .setNegativeButton("Keep",null).setPositiveButton("Remove",(d,w)->{try{collect();JSONArray rows=(JSONArray)Json.get(draft,prefix);rows.remove(index);render();}catch(Exception e){error(e);}}).show());}
                view.addView(card);
            }
            if(editable&&definition!=null){final JSONObject def=definition;ui.action(view,"+ Add "+group.toLowerCase(Locale.ROOT),false,()->{
                try{collect();Object current=Json.get(draft,prefix);JSONArray rows=current instanceof JSONArray?(JSONArray)current:new JSONArray();
                    int max=snapshot.raw.optInt(def.optString("maxField"),100);if(rows.length()>=max)throw new IllegalArgumentException("The router permits at most "+max+" entries");
                    rows.put(Json.copy(def.getJSONObject("template")));Json.set(draft,prefix,rows);render();
                }catch(Exception e){error(e);}
            });}
        }
        if(!editable)ui.note(view,"Viewing current values. Enable management controls in Tools to edit settings.");
    }
    private boolean visible(JSONObject f,String path){
        if(spec.id.equals("wan")){
            String type=draft.optString("wanType");
            if(path.startsWith("staticSettings.")&&!type.equals("Static"))return false;
            if(path.startsWith("pppoeSettings.")&&!type.equals("PPPoE"))return false;
            if(path.startsWith("tpSettings.")&&!type.equals("PPTP")&&!type.equals("L2TP"))return false;
            if(path.startsWith("bridgeSettings.")&&!type.equals("Bridge"))return false;
            if(path.contains(".staticSettings.")&&!Boolean.TRUE.equals(Json.get(draft,path.substring(0,path.indexOf(".staticSettings."))+".useStaticSettings")))return false;
            if(path.startsWith("staticSettings.")||path.startsWith("pppoeSettings.")||path.startsWith("tpSettings.")||path.startsWith("bridgeSettings."))return true;
        }
        if(path.contains(".settings.wpaPersonalSettings.")&&!String.valueOf(Json.get(draft,path.substring(0,path.indexOf(".wpaPersonalSettings."))+".security")).toLowerCase(Locale.ROOT).matches(".*(personal|psk).*"))return false;
        if(path.contains(".settings.wpaEnterpriseSettings.")&&!String.valueOf(Json.get(draft,path.substring(0,path.indexOf(".wpaEnterpriseSettings."))+".security")).toLowerCase(Locale.ROOT).contains("enterprise"))return false;
        if(path.contains(".settings.wepSettings.")&&!"WEP".equals(Json.get(draft,path.substring(0,path.indexOf(".wepSettings."))+".security")))return false;
        if(path.contains(".settings.wpaPersonalSettings.")||path.contains(".settings.wpaEnterpriseSettings."))return true;
        if(spec.id.equals("ipv6")&&path.startsWith("ipv6AutomaticSettings."))return draft.optString("wanType").equals("Automatic");
        if(Json.get(draft,path)!=null)return true;
        if(f.optString("type").equals("ipoptional")){int at=path.lastIndexOf('.');return at>0&&Json.get(draft,path.substring(0,at)) instanceof JSONObject;}
        return false;
    }
    private void field(LinearLayout parent,JSONObject f,String path){
        String label=f.optString("label"),type=f.optString("type");Object value=Json.get(draft,path);
        if(type.equals("identity")){ui.text(parent,label+": "+displayValue(value,path),13,ui.muted);return;}
        if(type.equals("preserve")){ui.text(parent,label+": "+(value instanceof JSONArray?((JSONArray)value).length()+" existing entries":"Preserved"),13,ui.muted);return;}
        if(!editable){ui.text(parent,label,12,ui.muted);ui.text(parent,displayValue(value,path),15,ui.text);return;}
        if(type.equals("bool")){
            Switch s=ui.toggle(label,Boolean.TRUE.equals(value));parent.addView(s);readers.put(path,s::isChecked);
            if(path.endsWith("useStaticSettings"))s.setOnCheckedChangeListener((b,on)->rebuild());return;
        }
        ui.text(parent,label,13,ui.muted);
        if(type.equals("schedule")){
            String initial=value==null?"111111111111111111111111111111111111111111111111":String.valueOf(value);final String[] selected={initial};
            Button b=ui.button(scheduleLabel(initial),false,()->schedule(label,selected,()->{}));
            b.setOnClickListener(v->schedule(label,selected,()->b.setText(scheduleLabel(selected[0]))));parent.addView(b);readers.put(path,()->selected[0]);return;
        }
        if(type.equals("radiolist")){
            JSONArray chosen=value instanceof JSONArray?(JSONArray)value:new JSONArray();final Set<String> selected=new LinkedHashSet<>();
            for(int i=0;i<chosen.length();i++)selected.add(Json.canonical(chosen.opt(i)));
            JSONArray options=snapshot.raw.optJSONArray("configurableRadios");if(options==null)options=chosen;
            final JSONArray available=options;
            for(int i=0;i<options.length();i++){
                Object option=options.opt(i);String key=Json.canonical(option);JSONObject o=option instanceof JSONObject?(JSONObject)option:new JSONObject();
                Switch s=ui.toggle(radioLabel(o.optString("radioID"))+(o.optBoolean("isGuestRadio")?" · Guest":" · Main"),selected.contains(key));parent.addView(s);
                s.setOnCheckedChangeListener((b,on)->{if(on)selected.add(key);else selected.remove(key);});
            }
            readers.put(path,()->{JSONArray out=new JSONArray();for(int i=0;i<available.length();i++)if(selected.contains(Json.canonical(available.opt(i))))out.put(available.opt(i));return out;});return;
        }
        List<String> options=options(f,path,value);
        if((type.equals("enum")||f.has("dynamic"))&&!options.isEmpty()){
            List<String> labels=new ArrayList<>(options);
            if(f.optString("dynamic").equals("timezone"))for(int i=0;i<options.size();i++)for(JSONObject zone:Topology.entries(snapshot.raw,"supportedTimeZones"))if(zone.optString("timeZoneID").equals(options.get(i))&&!zone.optString("description").isEmpty())labels.set(i,zone.optString("description"));
            Spinner spinner=new Spinner(ui.context);ArrayAdapter<String> adapter=new ArrayAdapter<>(ui.dialogContext(),android.R.layout.simple_spinner_item,labels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(adapter);spinner.setMinimumHeight(ui.d(48));
            int current=options.indexOf(value==null?"":String.valueOf(value));spinner.setSelection(Math.max(0,current));parent.addView(spinner);
            readers.put(path,()->type.equals("number")?Long.parseLong(options.get(spinner.getSelectedItemPosition())):options.get(spinner.getSelectedItemPosition()));
            if(path.equals("wanType")||path.endsWith(".security")||path.endsWith(".channelWidth")){
                final int initial=spinner.getSelectedItemPosition();final boolean[] armed={false};
                spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(!armed[0]){armed[0]=true;if(pos==initial)return;}rebuild();}});
            }
            return;
        }
        String text=value==null?"":String.valueOf(value);
        if(value instanceof JSONArray){List<String> lines=new ArrayList<>();JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)lines.add(a.optString(i));text=String.join("\n",lines);}
        EditText input=ui.input(label,text,type.equals("password"));
        if(type.equals("number"))input.setInputType(InputType.TYPE_CLASS_NUMBER);
        if(type.equals("ip")||type.equals("ipoptional"))input.setInputType(InputType.TYPE_CLASS_PHONE);
        if(type.equals("maclist")||type.equals("stringlist")){input.setSingleLine(false);input.setMinLines(2);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);}
        parent.addView(input);
        if(type.equals("password")){
            Switch show=ui.toggle("Show password",false);parent.addView(show);show.setOnCheckedChangeListener((b,on)->{int at=input.getSelectionStart();input.setTransformationMethod(on?null:android.text.method.PasswordTransformationMethod.getInstance());if(at>=0)input.setSelection(at);});
        }
        readers.put(path,()->{
            String s=input.getText().toString();
            if(type.equals("number")){try{return Long.parseLong(s);}catch(Exception e){throw new IllegalArgumentException(label+": enter a whole number");}}
            if(type.equals("maclist")||type.equals("stringlist")){JSONArray a=new JSONArray();for(String line:s.split("[\\r\\n]+"))if(!line.trim().isEmpty())a.put(line.trim());return a;}
            return type.equals("password")||type.equals("ssid")||type.equals("text")?s:s.trim();
        });
    }
    private List<String> options(JSONObject f,String path,Object current){
        JSONArray options=f.optJSONArray("options");String dynamic=f.optString("dynamic");
        if(path.startsWith("radios.")){
            String[] p=path.split("\\.");JSONObject radio=snapshot.raw.optJSONArray("radios").optJSONObject(Integer.parseInt(p[1]));
            if(dynamic.equals("mode"))options=radio.optJSONArray("supportedModes");
            if(dynamic.equals("security"))options=radio.optJSONArray("supportedSecurityTypes");
            if(dynamic.equals("width")||dynamic.equals("channel")){
                options=new JSONArray();for(JSONObject width:Topology.entries(radio,"supportedChannelsForChannelWidths")){
                    if(dynamic.equals("width"))options.put(width.optString("channelWidth"));
                    else if(width.optString("channelWidth").equals(Json.get(draft,"radios."+p[1]+".settings.channelWidth"))){JSONArray channels=width.optJSONArray("channels");if(channels!=null)for(int j=0;j<channels.length();j++)options.put(channels.opt(j));}
                }
            }
        }
        if(dynamic.equals("timezone"))options=snapshot.raw.optJSONArray("supportedTimeZones");
        if(path.equals("wanType")&&(spec.id.equals("wan")||spec.id.equals("ipv6"))){
            JSONArray advertised=snapshot.raw.optJSONArray(spec.id.equals("wan")?"supportedWANTypes":"supportedIPv6WANTypes");
            options=new JSONArray();if(advertised!=null)for(int i=0;i<advertised.length();i++){
                String mode=advertised.optString(i);if(mode.matches(spec.id.equals("wan")?"DHCP|Static|PPPoE|PPTP|L2TP|Bridge":"Automatic|PPPoE|Pass-through"))options.put(mode);
            }
        }
        List<String> out=new ArrayList<>();if(options!=null)for(int i=0;i<options.length();i++){Object item=options.opt(i);String s=item instanceof JSONObject?((JSONObject)item).optString("timeZoneID"):String.valueOf(item);if(!s.isEmpty()&&!out.contains(s))out.add(s);}
        if(current!=null&&!out.contains(String.valueOf(current)))out.add(0,String.valueOf(current));return out;
    }
    private void rebuild(){try{collect();render();}catch(Exception e){error(e);}}
    private void error(Exception e){ui.dialog("Check the draft").setMessage(e.getMessage()).setPositiveButton("OK",null).show();}
    private void schedule(String label,String[] selected,Runnable updated){
        ScrollView scroll=new ScrollView(ui.context);LinearLayout body=ui.column();body.setPadding(ui.d(20),0,ui.d(20),0);scroll.addView(body);
        ui.text(body,"Checked = allowed. Each slot covers 30 minutes, using the router’s local time.",13,ui.muted);List<CheckBox> slots=new ArrayList<>();
        for(int i=0;i<48;i++){CheckBox check=new CheckBox(ui.context);check.setText(String.format(Locale.ROOT,"%02d:%02d – %02d:%02d",i/2,i%2*30,(i+1)/2%24,(i+1)%2*30));check.setTextColor(ui.text);check.setChecked(selected[0].length()==48&&selected[0].charAt(i)=='1');slots.add(check);body.addView(check);}
        ui.action(body,"Allow all",false,()->slots.forEach(s->s.setChecked(true)));ui.action(body,"Block all",false,()->slots.forEach(s->s.setChecked(false)));
        ui.dialog(label).setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Use schedule",(d,w)->{StringBuilder b=new StringBuilder();slots.forEach(s->b.append(s.isChecked()?'1':'0'));selected[0]=b.toString();updated.run();}).show();
    }
    private static String scheduleLabel(String s){long allowed=s.chars().filter(c->c=='1').count();return allowed==48?"Allowed all day":allowed==0?"Blocked all day":(allowed/2.0)+" hours allowed · Edit";}
    public static String radioLabel(String id){return Topology.radioLabel(id);}
    private String displayValue(Object v,String path){
        if(Json.secret(path))return v==null?"Not reported":"Stored on router";
        if(v==null)return "Not reported";if(v instanceof Boolean)return (Boolean)v?"On":"Off";
        if(v instanceof JSONArray)return ((JSONArray)v).length()+" entries";if(v instanceof JSONObject)return "Reported";
        String text=String.valueOf(v);return text.length()>200?text.substring(0,200)+"…":text;
    }
    private void display(LinearLayout parent,Object value,String path,int depth,int[] count){
        if(count[0]++>240||depth>8)return;
        if(value instanceof JSONObject){for(String key:Json.keys((JSONObject)value)){if(Json.secret(key))continue;Object v=((JSONObject)value).opt(key);if(v instanceof JSONArray||v instanceof JSONObject)ui.heading(parent,Json.label(key));else ui.text(parent,Json.label(key)+": "+displayValue(v,key),14,ui.text);displayChildren(parent,v,key,depth,count);}}
        else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;if(a.length()==0)ui.text(parent,"No entries reported",14,ui.muted);for(int i=0;i<a.length();i++){ui.text(parent,"Entry "+(i+1),12,ui.accent);Object v=a.opt(i);if(v instanceof JSONObject||v instanceof JSONArray)display(parent,v,path,depth+1,count);else ui.text(parent,displayValue(v,path),14,ui.text);}}
    }
    private void displayChildren(LinearLayout parent,Object v,String key,int depth,int[] count){if(v instanceof JSONObject||v instanceof JSONArray)display(parent,v,key,depth+1,count);}
}
