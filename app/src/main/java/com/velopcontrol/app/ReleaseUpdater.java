package com.velopcontrol.app;

import android.content.*;
import android.content.pm.*;
import android.os.Build;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Separate public HTTPS transport; no headers or objects from JNAP are reused. */
public final class ReleaseUpdater {
    private final Context context;
    public ReleaseUpdater(Context context){this.context=context.getApplicationContext();}
    public ReleaseInfo check() throws Exception {
        HttpURLConnection c=open(ReleaseInfo.API,true);
        try{int status=c.getResponseCode();if(status==404)throw new IllegalStateException("No published GitHub release is available yet.");if(status==403||status==429)throw new IllegalStateException("GitHub’s update rate limit was reached. Try again later.");if(status!=200)throw new IOException("Update check failed");
            ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream input=c.getInputStream()){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1){if(out.size()+n>512*1024)throw new IOException("Release metadata is too large");out.write(b,0,n);}}
            return ReleaseInfo.parse(new JSONObject(out.toString("UTF-8")));
        }finally{c.disconnect();}
    }
    private HttpURLConnection open(String url,boolean metadata) throws Exception {
        if(metadata?!url.equals(ReleaseInfo.API):!ReleaseInfo.allowedDownload(url))throw new SecurityException("Update host blocked");
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(8000);c.setReadTimeout(20000);c.setUseCaches(false);c.setRequestProperty("User-Agent","Velop-Control-Android/"+BuildConfig.VERSION_NAME);c.setRequestProperty("Accept",metadata?"application/vnd.github+json":"application/octet-stream");return c;
    }
    public File download(ReleaseInfo release) throws Exception {
        File folder=new File(context.getFilesDir(),"updates");if(!folder.exists()&&!folder.mkdirs())throw new IOException("Unable to create update directory");
        File partial=new File(folder,"pending-download.apk"),verified=new File(folder,"verified.apk");if(verified.exists()&&!verified.delete())throw new IOException("Unable to replace previous update");
        try{String url=release.url;HttpURLConnection connection=null;
            try{for(int redirects=0;redirects<6;redirects++){
                connection=open(url,false);int status=connection.getResponseCode();
                if(status>=300&&status<400){String next=connection.getHeaderField("Location");connection.disconnect();connection=null;if(next==null)throw new IOException("Missing download location");url=new URL(new URL(url),next).toString();if(!ReleaseInfo.allowedDownload(url))throw new SecurityException("Update redirect blocked");continue;}
                if(status!=200)throw new IOException("APK download failed");MessageDigest hash=MessageDigest.getInstance("SHA-256");long total=0;
                try(InputStream in=connection.getInputStream();OutputStream out=new FileOutputStream(partial)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){total+=n;if(total>release.size||total>64L*1024*1024)throw new IOException("APK exceeds expected size");hash.update(b,0,n);out.write(b,0,n);}}
                if(total!=release.size||!hex(hash.digest()).equals(release.digest))throw new SecurityException("Downloaded APK failed its SHA-256 check");verifyPackage(partial,release.version);
                if(!partial.renameTo(verified))throw new IOException("Unable to prepare update");return verified;
            }throw new IOException("Too many APK redirects");}finally{if(connection!=null)connection.disconnect();}
        }finally{if(partial.exists())partial.delete();}
    }
    @SuppressWarnings("deprecation") private void verifyPackage(File file,String version) throws Exception {
        PackageManager manager=context.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo next=manager.getPackageArchiveInfo(file.getAbsolutePath(),flags),current=manager.getPackageInfo(context.getPackageName(),flags);
        if(next==null||!context.getPackageName().equals(next.packageName)||!version.equals(next.versionName)||next.versionCode<=current.versionCode)throw new SecurityException("The APK is not a newer build of this app");
        Signature[] installed=Build.VERSION.SDK_INT>=28?current.signingInfo.getApkContentsSigners():current.signatures;
        Signature[] downloaded=Build.VERSION.SDK_INT>=28?next.signingInfo.getApkContentsSigners():next.signatures;
        if(installed==null||downloaded==null||installed.length!=downloaded.length||installed.length==0)throw new SecurityException("The APK signing identity could not be verified");
        for(int i=0;i<installed.length;i++)if(!MessageDigest.isEqual(installed[i].toByteArray(),downloaded[i].toByteArray()))throw new SecurityException("The APK was signed by a different developer key");
    }
    private static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return b.toString();}
}
