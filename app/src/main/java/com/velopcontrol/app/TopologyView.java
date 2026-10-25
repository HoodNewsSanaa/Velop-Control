package com.velopcontrol.app;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;
import java.util.function.Consumer;

/** Cached, cycle-bounded tree drawing. Native node cards provide accessible details. */
public final class TopologyView extends View {
    private final Topology topology;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final List<PointF> positions=new ArrayList<>();
    private final List<Path> paths=new ArrayList<>();
    private final List<String> titles=new ArrayList<>();
    private final DashPathEffect dash;
    private final Typeface face=Typeface.create("sans-serif-medium",Typeface.NORMAL);
    private final Ui ui;
    private final Consumer<Topology.Node> listener;
    public TopologyView(Context context){this(context,new Topology(),null);}
    public TopologyView(Context context,Topology topology){this(context,topology,null);}
    public TopologyView(Context context,Topology topology,Consumer<Topology.Node> listener){
        super(context);this.topology=topology;this.listener=listener;density=getResources().getDisplayMetrics().density;
        ui=new Ui(context,context.getSharedPreferences("preferences",Context.MODE_PRIVATE).getBoolean("dark",true));dash=new DashPathEffect(new float[]{d(5),d(4)},0);
        setContentDescription("Reported mesh topology. Tap a node for details, or use the Nodes tab.");setMinimumHeight((int)(Math.max(220,topology.nodes.size()*86)*density));setClickable(listener!=null);
        Map<String,Topology.Node> byId=new HashMap<>();Map<String,PointF> byPosition=new HashMap<>();for(Topology.Node n:topology.nodes)byId.put(n.id,n);
        for(int i=0;i<topology.nodes.size();i++){
            Topology.Node node=topology.nodes.get(i),cursor=node;Set<String> visited=new HashSet<>();visited.add(node.id);int depth=0;
            while(cursor.parentId!=null&&byId.containsKey(cursor.parentId)&&visited.add(cursor.parentId)){cursor=byId.get(cursor.parentId);depth++;}
            PointF p=new PointF(d(12+Math.min(depth,3)*22),d(10+i*86));positions.add(p);byPosition.put(node.id,p);
        }
        for(int i=0;i<topology.nodes.size();i++){
            Topology.Node node=topology.nodes.get(i);PointF child=positions.get(i),parent=byPosition.get(node.parentId);Path path=new Path();
            if(parent!=null){path.moveTo(parent.x+d(8),parent.y+d(42));path.lineTo(child.x-d(10),parent.y+d(42));path.lineTo(child.x-d(10),child.y+d(24));path.lineTo(child.x,child.y+d(24));}paths.add(path);
        }
    }
    private float d(float v){return v*density;}
    private boolean wired(Topology.Node n){return "Wired".equalsIgnoreCase(n.link)||"Ethernet".equalsIgnoreCase(n.link);}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        titles.clear();paint.setTypeface(face);paint.setTextSize(d(16));for(int i=0;i<topology.nodes.size();i++){
            String full=topology.nodes.get(i).name,title=full;float available=w-positions.get(i).x-d(36);while(title.length()>1&&paint.measureText(title)>available)title=title.substring(0,title.length()-1);if(title.length()<full.length())title+="…";titles.add(title);
        }
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(d(2));
        for(int i=0;i<paths.size();i++){Topology.Node n=topology.nodes.get(i);paint.setColor(wired(n)?Ui.c("#62A0E7"):ui.accent);paint.setPathEffect(wired(n)?dash:null);canvas.drawPath(paths.get(i),paint);}
        paint.setPathEffect(null);paint.setStyle(Paint.Style.FILL);paint.setTypeface(face);
        for(int i=0;i<topology.nodes.size();i++){
            Topology.Node n=topology.nodes.get(i);PointF p=positions.get(i);paint.setColor(ui.dark?Ui.c("#203945"):Ui.c("#E8F2F2"));canvas.drawRoundRect(p.x,p.y,getWidth()-d(4),p.y+d(64),d(12),d(12),paint);
            paint.setColor(ui.text);paint.setTextSize(d(16));canvas.drawText(titles.size()>i?titles.get(i):n.name,p.x+d(12),p.y+d(25),paint);paint.setTextSize(d(12));paint.setColor(ui.muted);canvas.drawText(n.primary?"Primary mesh node":(wired(n)?"Wired · unverified parent":Topology.backhaulRadio(n)),p.x+d(12),p.y+d(47),paint);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(listener==null)return super.onTouchEvent(event);if(event.getAction()==MotionEvent.ACTION_UP){performClick();for(int i=0;i<positions.size();i++){PointF p=positions.get(i);if(event.getY()>=p.y&&event.getY()<=p.y+d(64)&&event.getX()>=p.x){listener.accept(topology.nodes.get(i));return true;}}}return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    static String label(String s){return s==null||s.isEmpty()?"Unknown":s;}
}
