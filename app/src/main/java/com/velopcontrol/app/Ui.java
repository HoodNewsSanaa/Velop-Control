package com.velopcontrol.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.content.res.ColorStateList;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Small native design system. No WebView, web fonts, image requests or UI runtime. */
public final class Ui {
    public final Context context;
    public final boolean dark;
    public final int bg,surface,text,muted,accent,line,warning;
    public Ui(Context context,boolean dark){
        this.context=context;this.dark=dark;
        bg=c(dark?"#091720":"#F2F7F7");surface=c(dark?"#142832":"#FFFFFF");text=c(dark?"#ECF5F7":"#16323A");
        muted=c(dark?"#9DB7C1":"#526D77");accent=c(dark?"#69DFC2":"#006C60");line=c(dark?"#2B4651":"#D9E6E8");warning=c(dark?"#FFCF88":"#865000");
    }
    public static int c(String value){return Color.parseColor(value);}
    public int d(float v){return (int)(v*context.getResources().getDisplayMetrics().density+.5f);}
    public GradientDrawable shape(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(d(radius));return g;}
    public LinearLayout column(){LinearLayout l=new LinearLayout(context);l.setOrientation(LinearLayout.VERTICAL);return l;}
    public LinearLayout row(){LinearLayout l=new LinearLayout(context);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    public LinearLayout card(){
        LinearLayout l=column();l.setPadding(d(18),d(16),d(18),d(16));l.setBackground(shape(surface,18));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=d(12);l.setLayoutParams(p);return l;
    }
    public TextView label(String value,int size,int color,boolean bold){
        TextView t=new TextView(context);t.setText(value);t.setTextColor(color);t.setTextSize(size);t.setLineSpacing(d(2),1);
        t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));t.setPadding(0,d(3),0,d(5));return t;
    }
    public TextView text(LinearLayout parent,String value,int size,int color){TextView t=label(value,size,color,false);parent.addView(t);return t;}
    public void title(LinearLayout parent,String value){parent.addView(label(value,24,text,true));}
    public void heading(LinearLayout parent,String value){TextView t=label(value,17,text,true);t.setPadding(0,d(10),0,d(10));parent.addView(t);}
    public Button button(String value,boolean primary,Runnable action){
        Button b=new Button(context);b.setText(value);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(primary?bg:accent);
        b.setBackground(shape(primary?accent:surface,12));b.setMinHeight(d(48));b.setPadding(d(14),d(8),d(14),d(8));
        b.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,d(50));p.topMargin=d(8);p.bottomMargin=d(4);b.setLayoutParams(p);
        return b;
    }
    public void action(LinearLayout parent,String value,boolean primary,Runnable action){parent.addView(button(value,primary,action));}
    public EditText input(String hint,String value,boolean password){
        EditText e=new EditText(context);e.setText(value);e.setHint(hint);e.setTextColor(text);e.setHintTextColor(muted);e.setTextSize(15);e.setSingleLine(true);
        e.setSaveEnabled(false);e.setPadding(d(12),d(10),d(12),d(10));e.setBackground(shape(bg,10));e.setMinHeight(d(48));
        e.setInputType(password?129:android.text.InputType.TYPE_CLASS_TEXT);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        e.setContentDescription(hint);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=d(3);p.bottomMargin=d(10);e.setLayoutParams(p);return e;
    }
    public Switch toggle(String label,boolean checked){
        Switch s=new Switch(context);s.setText(label);s.setTextColor(text);s.setTextSize(15);s.setChecked(checked);s.setPadding(0,d(12),0,d(12));s.setMinHeight(d(52));
        GradientDrawable track=shape(line,20);track.setSize(d(44),d(24));GradientDrawable thumb=shape(accent,20);thumb.setSize(d(26),d(26));s.setTrackDrawable(track);s.setThumbDrawable(thumb);s.setSplitTrack(false);
        s.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{dark?c("#306B63"):c("#BAE9DE"),line}));
        s.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{accent,muted}));return s;
    }
    public Context dialogContext(){return new ContextThemeWrapper(context,dark?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert);}
    public AlertDialog.Builder dialog(String title){return new AlertDialog.Builder(dialogContext()).setTitle(title);}
    public void note(LinearLayout parent,String value){LinearLayout l=card();text(l,value,13,muted);parent.addView(l);}
    public void metric(LinearLayout parent,String name,String value){TextView t=label(value,29,accent,true);parent.addView(t);text(parent,name,12,muted);}
    public void chips(LinearLayout parent,List<String> values){
        for(String s:values){TextView v=label(s,12,muted,false);v.setPadding(d(8),d(4),d(8),d(4));parent.addView(v);}
    }
    public void tiles(LinearLayout parent,List<View> cards){
        boolean wide=context.getResources().getConfiguration().screenWidthDp>=720;
        if(!wide){for(View v:cards)parent.addView(v);return;}
        for(int i=0;i<cards.size();i+=2){
            LinearLayout r=row();r.setGravity(Gravity.TOP);
            for(int j=i;j<Math.min(i+2,cards.size());j++){
                View v=cards.get(j);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.bottomMargin=d(12);if(j%2==0)p.rightMargin=d(6);else p.leftMargin=d(6);r.addView(v,p);
            }
            if(i+1==cards.size())r.addView(new View(context),new LinearLayout.LayoutParams(0,1,1));
            parent.addView(r);
        }
    }
    public ImageView icon(int res,int color,int sizeDp){
        ImageView v=new ImageView(context);v.setImageResource(res);v.setColorFilter(color);v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        v.setLayoutParams(new LinearLayout.LayoutParams(d(sizeDp),d(sizeDp)));return v;
    }
    /** A titled card that holds list rows (see {@link #item}). */
    public LinearLayout group(LinearLayout parent,String title){
        if(title!=null&&!title.isEmpty()){TextView t=label(title.toUpperCase(Locale.ROOT),12,muted,true);t.setLetterSpacing(0.06f);t.setPadding(d(6),d(14),0,d(6));parent.addView(t);}
        LinearLayout card=column();card.setBackground(shape(surface,18));card.setPadding(0,d(4),0,d(4));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=d(8);parent.addView(card,p);return card;
    }
    /** A tappable list row with optional icon, subtitle and chevron. */
    public LinearLayout item(LinearLayout group,String title,String subtitle,int iconRes,Runnable action){
        if(group.getChildCount()>0){View divider=new View(context);divider.setBackgroundColor(line);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Math.max(1,d(0.5f)));lp.leftMargin=d(iconRes!=0?58:18);group.addView(divider,lp);}
        LinearLayout r=row();r.setPadding(d(18),d(11),d(14),d(11));r.setMinimumHeight(d(58));
        if(iconRes!=0){ImageView ic=icon(iconRes,accent,22);LinearLayout.LayoutParams ip=(LinearLayout.LayoutParams)ic.getLayoutParams();ip.rightMargin=d(18);r.addView(ic);}
        LinearLayout texts=column();TextView t=label(title,16,text,false);t.setPadding(0,0,0,0);texts.addView(t);
        if(subtitle!=null&&!subtitle.isEmpty()){TextView sub=label(subtitle,13,muted,false);sub.setPadding(0,d(2),0,0);texts.addView(sub);}
        r.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        if(action!=null){
            TextView chevron=label("›",24,muted,false);chevron.setPadding(d(10),0,0,d(2));chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);r.addView(chevron);
            r.setForeground(selectable());r.setClickable(true);r.setOnClickListener(v->action.run());t.setOnClickListener(v->action.run());
            r.setContentDescription(title+(subtitle==null||subtitle.isEmpty()?"":". "+subtitle));
        }
        group.addView(r);return r;
    }
    /** A large shortcut tile for grids. */
    public LinearLayout tile(String title,String subtitle,int iconRes,Runnable action){
        LinearLayout c=column();c.setPadding(d(16),d(16),d(16),d(14));c.setBackground(shape(surface,18));c.setMinimumHeight(d(118));
        LinearLayout badge=row();badge.setGravity(Gravity.CENTER);badge.setBackground(shape(dark?c("#1E4A47"):c("#D5F1EA"),14));badge.addView(icon(iconRes,accent,22));c.addView(badge,new LinearLayout.LayoutParams(d(42),d(42)));
        TextView t=label(title,15,text,true);t.setPadding(0,d(12),0,0);c.addView(t);
        if(subtitle!=null&&!subtitle.isEmpty())text(c,subtitle,12,muted).setPadding(0,d(2),0,0);
        c.setForeground(selectable());c.setClickable(true);c.setOnClickListener(v->action.run());t.setOnClickListener(v->action.run());c.setContentDescription(title);return c;
    }
    public void grid(LinearLayout parent,List<View> cells){
        int columns=context.getResources().getConfiguration().screenWidthDp>=720?3:2;
        for(int i=0;i<cells.size();i+=columns){
            LinearLayout r=row();r.setGravity(Gravity.TOP);
            for(int j=0;j<columns;j++){
                View v=j+i<cells.size()?cells.get(i+j):new View(context);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.bottomMargin=d(10);
                if(j>0)p.leftMargin=d(5);if(j<columns-1)p.rightMargin=d(5);r.addView(v,p);
            }
            parent.addView(r);
        }
    }
    private android.graphics.drawable.Drawable selectable(){
        android.util.TypedValue tv=new android.util.TypedValue();context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,tv,true);return context.getDrawable(tv.resourceId);
    }
    public static String known(String value){return value==null||value.isEmpty()?"Not reported":value;}
}
