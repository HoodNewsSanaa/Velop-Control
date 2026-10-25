package com.velopcontrol.app;

import android.app.AlertDialog;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import java.io.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NavigationTest {
    private ActivityController<ControlActivity> controller;private ControlActivity activity;
    @Before public void start(){RuntimeEnvironment.getApplication().getSharedPreferences("preferences",0).edit().putBoolean("updateChecks",false).apply();controller=Robolectric.buildActivity(ControlActivity.class).setup();activity=controller.get();}
    @After public void finish(){controller.pause().stop().destroy();}
    private TextView find(View root,String text){
        if(root instanceof TextView&&((TextView)root).getText().toString().equals(text))return (TextView)root;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){TextView found=find(group.getChildAt(i),text);if(found!=null)return found;}}return null;
    }
    private View root(){return activity.findViewById(android.R.id.content);}
    private void click(String label){TextView view=find(root(),label);assertNotNull("Missing control: "+label,view);view.performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
    private void screenshot(String name) throws Exception {
        View view=activity.getWindow().getDecorView();float density=activity.getResources().getDisplayMetrics().density;int width=(int)(activity.getResources().getConfiguration().screenWidthDp*density),height=(int)(activity.getResources().getConfiguration().screenHeightDp*density);view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));File folder=new File("build/screenshots");folder.mkdirs();try(OutputStream output=new FileOutputStream(new File(folder,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}
    }
    @Test public void phoneNavigationSearchAndUnsupportedFeatureAreUsable() throws Exception {
        screenshot("login-phone");click("Explore demo");assertNotNull(find(root(),"Mesh connections"));screenshot("overview-phone");
        click("Nodes");assertNotNull(find(root(),"A · Primary"));screenshot("nodes-phone");
        click("Devices");assertNotNull(find(root(),"Pixel phone"));EditText query=(EditText)findHint(root(),"Search name, IP, MAC or access point");assertNotNull(query);query.setText("Pixel");assertNotNull(find(root(),"Pixel phone"));assertNull(find(root(),"Work laptop"));screenshot("devices-phone");
        click("Settings");assertNotNull(find(root(),"Wi-Fi networks"));click("Open");assertNotNull(find(root(),"Wi-Fi name"));click("‹ Back");
        click("Tools");assertNotNull(find(root(),"Run sequential validation"));click("Run sequential validation");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);assertTrue(dialog.isShowing());assertNotNull(find(dialog.getWindow().getDecorView(),"Connect to your mesh"));dialog.dismiss();screenshot("tools-phone");
    }
    private View findHint(View view,String text){if(view instanceof EditText&&text.contentEquals(((EditText)view).getHint()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findHint(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
    @Test @Config(qualifiers="w900dp-h650dp-land-xhdpi") public void tabletNavigationAndAppearanceCanChange() throws Exception {
        click("Explore demo");screenshot("overview-tablet");click("Tools");Switch appearance=(Switch)find(root(),"Dark appearance");assertNotNull(appearance);appearance.setChecked(false);assertFalse(activity.getSharedPreferences("preferences",0).getBoolean("dark",true));screenshot("tools-tablet-light");click("Settings");assertNotNull(find(root(),"Internet & bridge mode"));
    }
    @Test public void formPreservesRadioIdsAndCollectsNativePasswordEdits() throws Exception {
        JSONObject raw=new JSONObject("{\"radios\":[{\"radioID\":\"RADIO_5GHz\",\"supportedModes\":[\"Mixed\"],\"supportedSecurityTypes\":[\"WPA2-Personal\"],\"supportedChannelsForChannelWidths\":[{\"channelWidth\":\"Auto\",\"channels\":[0,36]}],\"settings\":{\"isEnabled\":true,\"ssid\":\"Home\",\"broadcastSSID\":true,\"mode\":\"Mixed\",\"channelWidth\":\"Auto\",\"channel\":0,\"security\":\"WPA2-Personal\",\"wpaPersonalSettings\":{\"passphrase\":\"old-password\"}}}]}" );
        SettingsRepository.Snapshot snapshot=new SettingsRepository.Snapshot(SettingsCatalog.find("wifi"),raw,"192.168.1.1");SettingsForm form=new SettingsForm(new Ui(activity,true),snapshot,true);
        EditText password=(EditText)findHint(form.view,"Wi-Fi password");assertNotNull(password);password.setText("new-password");JSONObject draft=form.collect();assertEquals("RADIO_5GHz",Json.get(draft,"radios.0.radioID"));assertEquals("new-password",Json.get(draft,"radios.0.settings.wpaPersonalSettings.passphrase"));assertNull(Json.get(draft,"radios.0.supportedModes"));SettingsValidation.check(snapshot.spec,draft,raw);
    }
}
