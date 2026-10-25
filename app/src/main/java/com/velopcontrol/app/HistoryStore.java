package com.velopcontrol.app;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import java.util.*;

/** Optional, app-private seven-day association history. No router passwords or settings. */
public final class HistoryStore extends SQLiteOpenHelper {
    private final Map<String,String> previous=new HashMap<>();
    public HistoryStore(Context c){super(c,"mesh-history.db",null,1);}
    public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE events (ts INTEGER, device TEXT, label TEXT, node TEXT, state TEXT)");db.execSQL("CREATE INDEX event_time ON events(ts)");db.execSQL("CREATE TABLE samples (ts INTEGER, nodes INTEGER, clients INTEGER)");}
    public void onUpgrade(SQLiteDatabase db,int old,int now){}
    public synchronized void record(Topology t){
        if(t.demo)return;SQLiteDatabase db=getWritableDatabase();long now=System.currentTimeMillis();long cutoff=now-7L*86400000;
        Map<String,String> recorded=new HashMap<>();db.beginTransaction();try{
            ContentValues sample=new ContentValues();sample.put("ts",now);sample.put("nodes",(int)t.nodes.stream().filter(n->n.seen).count());sample.put("clients",(int)t.clients.stream().filter(c->c.seen).count());db.insert("samples",null,sample);
            for(Topology.Client c:t.clients){String key=c.mac==null?"":c.mac;if(key.isEmpty())continue;String state=c.seen?"Reported connected":"No connection reported";String node=c.nodeId==null?"":c.nodeId;String signature=state+":"+node;
                if(!signature.equals(previous.get(key))){ContentValues event=new ContentValues();event.put("ts",now);event.put("device",key);event.put("label",c.name);event.put("node",c.seen?t.nodeName(c.nodeId):"");event.put("state",state);db.insertOrThrow("events",null,event);recorded.put(key,signature);}
            }
            db.delete("events","ts < ?",new String[]{String.valueOf(cutoff)});db.delete("samples","ts < ?",new String[]{String.valueOf(cutoff)});db.setTransactionSuccessful();
        }finally{db.endTransaction();}previous.putAll(recorded);
    }
    public synchronized List<String> recent(String device){
        List<String> out=new ArrayList<>();String cutoff=String.valueOf(System.currentTimeMillis()-7L*86400000);String filter=device==null?"ts >= ?":"ts >= ? AND device = ?";String[] args=device==null?new String[]{cutoff}:new String[]{cutoff,device};
        try(Cursor c=getReadableDatabase().query("events",new String[]{"ts","label","node","state"},filter,args,null,null,"ts DESC","80")){
            java.text.DateFormat format=new java.text.SimpleDateFormat("MMM d, HH:mm",Locale.getDefault());
            while(c.moveToNext())out.add(format.format(new Date(c.getLong(0)))+" · "+c.getString(1)+"\n"+c.getString(3)+(c.getString(2).isEmpty()?"":" · "+c.getString(2)));
        }return out;
    }
    public synchronized void clear(){SQLiteDatabase db=getWritableDatabase();db.delete("events",null,null);db.delete("samples",null,null);previous.clear();}
}
