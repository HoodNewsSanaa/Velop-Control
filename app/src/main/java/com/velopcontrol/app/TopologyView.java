package com.velopcontrol.app;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;
import java.util.function.Consumer;

/** Indented tree of firmware-reported parents, drawn in parent → child order. Native node cards provide accessible details. */
public final class TopologyView extends View {
    static final int ROW_DP = 86, BOX_DP = 64, INDENT_DP = 28, MAX_DEPTH = 5;
    private final Topology topology;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    /** Nodes in drawing order, with their tree depth and drawn parent (null for roots). */
    private final List<Topology.Node> order=new ArrayList<>();
    private final List<Integer> depths=new ArrayList<>();
    private final List<Integer> parents=new ArrayList<>();
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
        setContentDescription("Reported mesh topology. Tap a node for details, or use the Nodes tab.");setMinimumHeight((int)(Math.max(220,topology.nodes.size()*ROW_DP)*density));setClickable(listener!=null);
        layout(topology,order,depths,parents);
    }

    /**
     * Depth-first order: the primary's tree first, then any other roots (nodes whose parent is not
     * reported or not in the inventory). Children follow their parent in the topology's name order.
     * A reported cycle is broken where it would revisit a node, so every node is drawn exactly once.
     */
    static void layout(Topology topology,List<Topology.Node> order,List<Integer> depths,List<Integer> parents){
        Map<String,Topology.Node> byId=new HashMap<>();for(Topology.Node n:topology.nodes)byId.put(n.id,n);
        Map<String,List<Topology.Node>> children=new HashMap<>();List<Topology.Node> roots=new ArrayList<>();
        for(Topology.Node n:topology.nodes){
            if(n.parentId!=null&&byId.containsKey(n.parentId)&&!n.primary)children.computeIfAbsent(n.parentId,k->new ArrayList<>()).add(n);else roots.add(n);
        }
        roots.sort(Comparator.comparing((Topology.Node n)->!n.primary));
        Set<String> placed=new HashSet<>();
        for(Topology.Node root:roots)visit(root,0,-1,children,placed,order,depths,parents);
        // Nodes only reachable through a cycle: draw them as roots.
        for(Topology.Node n:topology.nodes)if(!placed.contains(n.id))visit(n,0,-1,children,placed,order,depths,parents);
    }
    private static void visit(Topology.Node node,int depth,int parent,Map<String,List<Topology.Node>> children,Set<String> placed,List<Topology.Node> order,List<Integer> depths,List<Integer> parents){
        if(!placed.add(node.id))return;
        int index=order.size();order.add(node);depths.add(depth);parents.add(parent);
        for(Topology.Node child:children.getOrDefault(node.id,Collections.emptyList()))visit(child,depth+1,index,children,placed,order,depths,parents);
    }

    private float d(float v){return v*density;}
    private float x(int i){return d(4+Math.min(depths.get(i),MAX_DEPTH)*INDENT_DP);}
    private float y(int i){return d(10+i*ROW_DP);}
    private boolean wired(Topology.Node n){return "Wired".equalsIgnoreCase(n.link)||"Ethernet".equalsIgnoreCase(n.link);}
    private String subtitle(int i){
        Topology.Node n=order.get(i);
        if(n.primary)return "Primary mesh node";
        if(parents.get(i)<0)return "Parent not reported";
        return wired(n)?"Wired · unverified cable path":Topology.backhaulRadio(n);
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        titles.clear();paint.setTypeface(face);paint.setTextSize(d(16));for(int i=0;i<order.size();i++){
            String full=order.get(i).name,title=full;float available=w-x(i)-d(36);while(title.length()>1&&paint.measureText(title)>available)title=title.substring(0,title.length()-1);if(title.length()<full.length())title+="…";titles.add(title);
        }
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(d(2));
        for(int i=0;i<order.size();i++){
            int p=parents.get(i);if(p<0)continue;Topology.Node n=order.get(i);
            // Neutral trunk down the parent's gutter (shared by siblings); the stub into each child shows its link type.
            float trunk=x(p)+d(14),childMid=y(i)+d(BOX_DP/2f);
            paint.setColor(ui.muted);paint.setPathEffect(null);canvas.drawLine(trunk,y(p)+d(BOX_DP),trunk,childMid,paint);
            paint.setColor(wired(n)?Ui.c("#62A0E7"):ui.accent);paint.setPathEffect(wired(n)?dash:null);paint.setStrokeWidth(d(3));canvas.drawLine(trunk,childMid,x(i),childMid,paint);paint.setStrokeWidth(d(2));
        }
        paint.setPathEffect(null);paint.setStyle(Paint.Style.FILL);paint.setTypeface(face);
        for(int i=0;i<order.size();i++){
            Topology.Node n=order.get(i);float x=x(i),y=y(i);paint.setColor(ui.dark?Ui.c("#203945"):Ui.c("#E8F2F2"));canvas.drawRoundRect(x,y,getWidth()-d(4),y+d(BOX_DP),d(12),d(12),paint);
            paint.setColor(ui.text);paint.setTextSize(d(16));canvas.drawText(titles.size()>i?titles.get(i):n.name,x+d(12),y+d(25),paint);
            paint.setTextSize(d(12));paint.setColor(parents.get(i)<0&&!n.primary?ui.warning:ui.muted);canvas.drawText(subtitle(i),x+d(12),y+d(47),paint);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(listener==null)return super.onTouchEvent(event);if(event.getAction()==MotionEvent.ACTION_UP){performClick();for(int i=0;i<order.size();i++){if(event.getY()>=y(i)&&event.getY()<=y(i)+d(BOX_DP)&&event.getX()>=x(i)){listener.accept(order.get(i));return true;}}}return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    static String label(String s){return s==null||s.isEmpty()?"Unknown":s;}
}
