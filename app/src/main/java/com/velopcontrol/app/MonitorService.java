package com.velopcontrol.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/**
 * Foreground connection monitor. Every interval it records the phone's Wi-Fi state and whether the
 * gateway, the primary Velop, the internet and DNS answer, using sockets bound to the Wi-Fi network so
 * mobile data cannot hide a drop. It sends no credentials and no router API requests.
 */
public final class MonitorService extends Service {
    static final String ACTION_STOP = "com.velopcontrol.app.monitor.STOP";
    static final String EXTRA_MINUTES = "minutes", EXTRA_GATEWAY = "gateway", EXTRA_PRIMARY = "primary";
    static final long INTERVAL_MS = 5000;
    private static final String CHANNEL = "monitor"; private static final int NOTIFICATION = 41;

    // Read by the activity (same process).
    static volatile boolean running; static volatile long startedAt, endsAt; static volatile int samples, problems;

    private ScheduledExecutorService executor; private PowerManager.WakeLock wake; private long lastNotified; private boolean lastProblem;
    private String gateway = "", primary = "";

    static File file(Context c) { return new File(new File(c.getFilesDir(), "monitor"), "session.csv"); }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        int minutes = intent == null ? 60 : Math.max(5, Math.min(240, intent.getIntExtra(EXTRA_MINUTES, 60)));
        gateway = safe(intent == null ? null : intent.getStringExtra(EXTRA_GATEWAY));
        primary = safe(intent == null ? null : intent.getStringExtra(EXTRA_PRIMARY));
        startedAt = System.currentTimeMillis(); endsAt = startedAt + minutes * 60_000L; samples = 0; problems = 0; lastProblem = false;
        File f = file(this); f.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8)) {
            w.write("# Velop Control connection monitor · started " + startedAt + " · interval " + INTERVAL_MS + " ms · gateway " + gateway + " · primary " + primary + "\n" + DropMonitor.HEADER + "\n");
        } catch (IOException e) { stopSelf(); return START_NOT_STICKY; }
        Notification n = notification("Starting…");
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); else startForeground(NOTIFICATION, n);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VelopControl:monitor"); wake.setReferenceCounted(false); wake.acquire(minutes * 60_000L + 60_000L);
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::probe, 0, INTERVAL_MS, TimeUnit.MILLISECONDS);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (executor != null) executor.shutdownNow();
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    private static String safe(String host) {
        if (host == null) return "";
        try { return ReadPolicy.privateAddress(host); } catch (IllegalArgumentException e) { return ""; }
    }

    @SuppressWarnings("deprecation")
    private void probe() {
        if (System.currentTimeMillis() >= endsAt) { new Handler(Looper.getMainLooper()).post(this::stopSelf); return; }
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network wifiNet = null;
        for (Network n : cm.getAllNetworks()) { NetworkCapabilities c = cm.getNetworkCapabilities(n); if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { wifiNet = n; break; } }
        DropMonitor.Sample s;
        if (wifiNet == null) s = new DropMonitor.Sample(System.currentTimeMillis(), false, "", null, null, null, null, null, null, null);
        else {
            WifiInfo info = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE)).getConnectionInfo();
            String bssid = info == null || info.getBSSID() == null ? "" : info.getBSSID().toUpperCase(java.util.Locale.ROOT);
            if (bssid.equals("02:00:00:00:00:00")) bssid = ""; // hidden without location permission
            Integer rssi = info == null || info.getRssi() <= -127 ? null : info.getRssi();
            Integer link = info == null || info.getLinkSpeed() <= 0 ? null : info.getLinkSpeed();
            Integer freq = info == null || info.getFrequency() <= 0 ? null : info.getFrequency();
            // In router mode the gateway is the primary Velop: check it once, as the Velop.
            Integer gw = gateway.isEmpty() || gateway.equals(primary) ? null : tcp(wifiNet, gateway, 53);
            Integer velop = primary.isEmpty() ? null : tcp(wifiNet, primary, 443);
            Integer internet = tcp(wifiNet, "1.1.1.1", 443);
            if (internet < 0) internet = tcp(wifiNet, "8.8.8.8", 443);
            Integer dns = dns(wifiNet);
            s = new DropMonitor.Sample(System.currentTimeMillis(), true, bssid, rssi, link, freq, gw, velop, internet, dns);
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file(this), true), StandardCharsets.UTF_8)) { w.write(s.csv() + "\n"); } catch (IOException ignored) {}
        samples++;
        boolean problem = s.problem() != null;
        if (problem && !lastProblem) problems++;
        lastProblem = problem;
        long now = System.currentTimeMillis();
        if (now - lastNotified > 30_000 || problem) {
            lastNotified = now;
            String text = (problem ? "Now: " + s.problem() : "Connection OK") + " · " + problems + (problems == 1 ? " drop" : " drops") + " · ends " + DropMonitor.time(endsAt);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification(text));
        }
    }

    /** TCP connect time in ms over the Wi-Fi network; a refused connection still proves the host answered. -1 on failure. */
    private static int tcp(Network network, String host, int port) {
        long start = SystemClock.elapsedRealtime();
        try (Socket socket = network.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), 2000);
        } catch (ConnectException e) {
            String m = String.valueOf(e.getMessage());
            if (!m.contains("refused")) return -1;
        } catch (IOException e) { return -1; }
        return (int) Math.max(1, SystemClock.elapsedRealtime() - start);
    }

    /** Uncached DNS lookup over Wi-Fi (Android 10+); null when unavailable on this Android version. */
    private static Integer dns(Network network) {
        if (Build.VERSION.SDK_INT < 29) return null;
        long start = SystemClock.elapsedRealtime();
        CountDownLatch done = new CountDownLatch(1); int[] result = {-1};
        android.net.DnsResolver.getInstance().query(network, "connectivitycheck.gstatic.com", android.net.DnsResolver.FLAG_NO_CACHE_LOOKUP, Runnable::run, null,
            new android.net.DnsResolver.Callback<java.util.List<InetAddress>>() {
                public void onAnswer(java.util.List<InetAddress> answer, int rcode) { if (rcode == 0 && !answer.isEmpty()) result[0] = (int) Math.max(1, SystemClock.elapsedRealtime() - start); done.countDown(); }
                public void onError(android.net.DnsResolver.DnsException error) { done.countDown(); }
            });
        try { if (!done.await(3, TimeUnit.SECONDS)) return -1; } catch (InterruptedException e) { Thread.currentThread().interrupt(); return -1; }
        return result[0];
    }

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Connection monitor", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, ControlActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, MonitorService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_velop).setContentTitle("Velop Control · connection monitor")
            .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    }
}
