package com.eleeth.copspotterapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.SparseArray;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cop Spotter field scanner v2: BLE advertisements + WiFi scans, matched
 * against the v3 firmware signature DB (103 signatures, three classes).
 * Alert policy mirrors the hardware exactly:
 *   POLICE  — RSSI >= -80, 30 s strobe cooldown, red/blue strobe + pager beep
 *   SKIMMER — close range only (RSSI >= -70), 10 s cooldown, amber alert
 *   TRACKER — close range only (RSSI >= -70), 4 s cooldown, purple alert +
 *             foxhunt pulse whose cadence quickens with signal strength
 * DISCREET mirrors the hardware BOOT button: lights/sound/vibration dark,
 * scanning + logging continue, state persisted across restarts.
 * Presence is real-time: a contact is ACTIVE only while heard in the last 6 s.
 */
public class ScannerService extends Service {

    public static final class HitEvent {
        public final long timeMs; public final String kind, why, mac;
        public final int rssi; public final boolean strong; public final int cls;
        HitEvent(long t, String k, String w, String m, int r, boolean s, int c) {
            timeMs = t; kind = k; why = w; mac = m; rssi = r; strong = s; cls = c;
        }
        public String line() {
            SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return "[" + f.format(new Date(timeMs)) + "Z] " + kind
                 + " [" + SigDb.clsTag(cls) + "] " + why
                 + "  " + mac + "  " + rssi + " dBm" + (strong ? "  ** STRONG **" : "");
        }
    }

    public interface Listener {
        void onHit(HitEvent e, boolean alerted);
        void onState();
    }

    public static volatile boolean running = false;
    public static volatile boolean uiForeground = false;
    public static volatile String btState = "IDLE", wifiState = "IDLE";
    public static volatile long startedAt = 0;
    public static volatile int alertCount = 0, packetCount = 0;
    public static volatile int seenCount = 0;
    public static volatile String lastSeen = "";

    // ---- DISCREET (mirrors the hardware button; persisted) ----
    public static volatile boolean discreet = false;
    private static final String PREFS = "copspotter";
    private static ScannerService instance = null;

    public static void loadDiscreet(Context c) {
        discreet = c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("discreet", false);
    }
    public static void setDiscreet(Context c, boolean on) {
        discreet = on;
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("discreet", on).apply();
        if (instance != null) instance.updateRunNotification();
        if (listener != null) listener.onState();
    }

    // ---- real-time presence: a contact is ACTIVE only while its signal keeps arriving ----
    public static final class ActiveContact {
        public final String kind, why, mac;
        public final int cls;
        public volatile int rssi;
        public volatile long lastSeenMs;
        public final boolean strong;
        ActiveContact(String k, String w, String m, int r, boolean s, int c, long t) {
            kind = k; why = w; mac = m; rssi = r; strong = s; cls = c; lastSeenMs = t;
        }
    }
    private static final long PRESENCE_MS = 6_000;
    private static final ConcurrentHashMap<String, ActiveContact> PRESENCE =
        new ConcurrentHashMap<>();

    /**
     * The contact to present: highest class priority (police > skimmer >
     * tracker, the firmware order), freshest within the class — heard within
     * the presence window, or null.
     */
    public static ActiveContact activeContact() {
        long now = System.currentTimeMillis();
        ActiveContact best = null;
        for (ActiveContact c : PRESENCE.values()) {
            if (now - c.lastSeenMs > PRESENCE_MS) continue;
            if (best == null || c.cls > best.cls
                    || (c.cls == best.cls && c.lastSeenMs > best.lastSeenMs)) best = c;
        }
        return best;
    }
    /** Freshest present TRACKER contact inside the foxhunt gate, or null. */
    static ActiveContact activeTracker() {
        long now = System.currentTimeMillis();
        ActiveContact best = null;
        for (ActiveContact c : PRESENCE.values()) {
            if (c.cls != SigDb.CLS_TRACKER) continue;
            if (now - c.lastSeenMs > PRESENCE_MS) continue;
            if (!SigDb.alertGate(SigDb.CLS_TRACKER, c.rssi)) continue;
            if (best == null || c.lastSeenMs > best.lastSeenMs) best = c;
        }
        return best;
    }
    public static volatile HitEvent lastHit = null;
    public static final CopyOnWriteArrayList<HitEvent> LOG = new CopyOnWriteArrayList<>();
    public static volatile Listener listener = null;

    private static final String CHAN_RUN = "scanner";
    private static final String CHAN_ALERT = "alerts";
    private static final long LOG_DEDUPE_MS = 20_000;

    // per-class alert cooldowns (firmware alert_trigger)
    private static volatile long lastStrobeMs = 0, lastSkimmerMs = 0;
    private static final ConcurrentHashMap<String, Long> lastTrackerAlert = new ConcurrentHashMap<>();
    // Banner arbitration (firmware s_led_busy_until / s_led_busy_prio): while a
    // class alert is on screen, equal-or-lower priority alerts are dropped
    // WITHOUT consuming their cooldowns — one advertiser matching two classes
    // can never stomp the higher class's banner.
    private static volatile long bannerBusyUntilMs = 0;
    private static volatile int bannerBusyPrio = 0;
    private static final long ALERT_POLICE_MS = 3_600, ALERT_SKIMMER_MS = 1_200, ALERT_TRACKER_MS = 6_000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private WifiManager wifi;
    private final Map<String, Long> lastLogged = new HashMap<>();
    private boolean wifiLoopOn = false;
    private long nextFoxTickMs = 0;

    // ---------------- lifecycle ----------------
    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createChannels();
        loadDiscreet(this);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        loadLog();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopScan();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        loadDiscreet(this);
        startForeground(1, buildRunNotification());
        startScan();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() { stopScan(); if (instance == this) instance = null; super.onDestroy(); }

    // ---------------- scanning ----------------
    private void startBleScan() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad == null) { btState = "NO BT"; }
        else if (!ad.isEnabled()) { btState = "BT OFF"; }
        else {
            try {
                scanner = ad.getBluetoothLeScanner();
                ScanSettings s = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build();
                scanner.startScan(null, s, bleCallback);
                btState = "SCANNING";
            } catch (SecurityException e) { btState = "NO PERM"; scanner = null; }
              catch (Exception e) { btState = "ERR"; scanner = null; }
        }
    }

    // Android silently starves very long BLE scan sessions; re-arm every 10 min to stay real-time.
    private final Runnable rearmLoop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try { if (scanner != null) scanner.stopScan(bleCallback); } catch (Exception ignored) {}
            startBleScan();
            notifyState();
            h.postDelayed(this, 600_000);
        }
    };

    private void startScan() {
        if (running) return;
        startBleScan();
        if (!wifiLoopOn) { wifiLoopOn = true; h.post(wifiLoop); }
        running = true;
        startedAt = System.currentTimeMillis();
        h.removeCallbacks(rearmLoop);
        h.postDelayed(rearmLoop, 600_000);
        h.removeCallbacks(foxLoop);
        h.postDelayed(foxLoop, 300);
        notifyState();
    }

    private void stopScan() {
        try { if (scanner != null) scanner.stopScan(bleCallback); } catch (Exception ignored) {}
        scanner = null;
        wifiLoopOn = false;
        h.removeCallbacks(wifiLoop);
        h.removeCallbacks(rearmLoop);
        h.removeCallbacks(foxLoop);
        PRESENCE.clear();
        running = false;
        btState = "IDLE"; wifiState = "IDLE";
        notifyState();
    }

    private final Runnable wifiLoop = new Runnable() {
        @Override public void run() {
            if (!wifiLoopOn) return;
            pollWifi();
            h.postDelayed(this, 45_000);
        }
    };

    private void pollWifi() {
        if (wifi == null) { wifiState = "NO WIFI"; return; }
        try {
            if (!wifi.isWifiEnabled()) { wifiState = "WIFI OFF"; return; }
        } catch (SecurityException e) { wifiState = "NO PERM(STATE)"; return; }
          catch (Exception e) { wifiState = "ERR(STATE)"; return; }
        try { wifi.startScan(); } catch (Exception ignored) {} // throttled by Android; results read regardless
        try {
            List<android.net.wifi.ScanResult> rs = wifi.getScanResults();
            wifiState = "POLLING";
            if (rs != null) {
                for (android.net.wifi.ScanResult r : rs) {
                    for (SigDb.Hit hit : SigDb.matchWifi(r.SSID, r.BSSID))
                        onMatch(hit, r.BSSID.toUpperCase(Locale.US), r.level);
                }
            }
        } catch (SecurityException e) { wifiState = "NO PERM(RESULTS)"; }
          catch (Exception e) { wifiState = "ERR(RESULTS)"; }
    }

    private final ScanCallback bleCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            try {
                ScanRecord rec = result.getScanRecord();
                String mac = result.getDevice().getAddress();
                if (rec == null || mac == null) return;
                Map<Integer, byte[]> mfr = null;
                SparseArray<byte[]> sa = rec.getManufacturerSpecificData();
                if (sa != null && sa.size() > 0) {
                    mfr = new HashMap<>();
                    for (int i = 0; i < sa.size(); i++) mfr.put(sa.keyAt(i), sa.valueAt(i));
                }
                Set<Integer> uuids = new HashSet<>();
                Set<String> uuid128s = new HashSet<>();
                List<ParcelUuid> us = rec.getServiceUuids();
                if (us != null) for (ParcelUuid u : us) collectUuid(uuids, uuid128s, u);
                Map<Integer, byte[]> svcData = new HashMap<>();
                Map<ParcelUuid, byte[]> sd = rec.getServiceData();
                if (sd != null) for (Map.Entry<ParcelUuid, byte[]> e : sd.entrySet()) {
                    collectUuid(uuids, uuid128s, e.getKey());
                    Integer u16 = uuid16Of(e.getKey());
                    if (u16 != null) svcData.put(u16, e.getValue());
                }
                String nm = rec.getDeviceName();
                if (nm == null) nm = parseNameFromBytes(rec.getBytes());
                seenCount++;
                if (nm != null) lastSeen = nm + " @ " + mac;
                android.util.Log.d("CSWATCH", "res mac=" + mac + " name=" + nm + " rssi=" + result.getRssi());
                for (SigDb.Hit hit : SigDb.matchBle(mac, nm, mfr, uuids, svcData, uuid128s, rec.getBytes()))
                    onMatch(hit, mac, result.getRssi());
            } catch (Exception ignored) {}
        }
        @Override public void onScanFailed(int errorCode) {
            btState = "SCAN FAIL " + errorCode;
            notifyState();
            h.postDelayed(() -> { if (running) { try {
                BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
                if (bm != null && bm.getAdapter() != null && bm.getAdapter().isEnabled()) {
                    scanner = bm.getAdapter().getBluetoothLeScanner();
                    ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
                    scanner.startScan(null, s, bleCallback);
                    btState = "SCANNING";
                    notifyState();
                }
            } catch (Exception ignored) {} } }, 5_000);
        }
    };

    /** Walk raw AD structures for a local name (0x08/0x09) — the firmware reads names this way. */
    static String parseNameFromBytes(byte[] b) {
        if (b == null) return null;
        int i = 0;
        while (i + 1 < b.length) {
            int alen = b[i] & 0xFF;
            if (alen == 0 || i + alen >= b.length + 1) break;
            int type = b[i + 1] & 0xFF;
            if (type == 0x08 || type == 0x09) {
                try { return new String(b, i + 2, alen - 1, "UTF-8"); }
                catch (Exception e) { return null; }
            }
            i += alen + 1;
        }
        return null;
    }

    /** Split a ParcelUuid into the 16-bit set and/or the canonical 128-bit set. */
    private static void collectUuid(Set<Integer> u16s, Set<String> u128s, ParcelUuid u) {
        UUID id = u.getUuid();
        u128s.add(id.toString().toLowerCase(Locale.US));
        Integer v = uuid16Of(u);
        if (v != null) u16s.add(v);
    }
    private static Integer uuid16Of(ParcelUuid u) {
        // 16-bit UUIDs ride the Bluetooth base UUID: 0000XXXX-0000-1000-8000-00805F9B34FB
        String s = u.getUuid().toString();
        if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb") && s.length() == 36) {
            try { return Integer.parseInt(s.substring(4, 8), 16); } catch (Exception ignored) {}
        }
        return null;
    }

    // ---------------- hit engine ----------------
    private void onMatch(SigDb.Hit hit, String mac, int rssi) {
        packetCount++;
        long now = System.currentTimeMillis();
        String pkey = mac + "|" + hit.cls;
        ActiveContact ac = PRESENCE.get(pkey);
        if (ac == null) PRESENCE.put(pkey, new ActiveContact(hit.kind, hit.why, mac, rssi, hit.strong, hit.cls, now));
        else { ac.rssi = rssi; ac.lastSeenMs = now; }
        String key = mac + "|" + hit.cls + "|" + hit.why;
        Long prev = lastLogged.get(key);
        boolean alerted = false;
        HitEvent logged = null;
        if (prev == null || now - prev > LOG_DEDUPE_MS) {
            lastLogged.put(key, now);
            logged = new HitEvent(now, hit.kind, hit.why, mac, rssi, hit.strong, hit.cls);
            LOG.add(logged);
            while (LOG.size() > 200) LOG.remove(0);
            lastHit = logged;
            saveLog();
            if (listener != null) listener.onHit(logged, false); // log line first
        }
        if (!discreet && SigDb.alertGate(hit.cls, rssi)
                && !(now < bannerBusyUntilMs && hit.cls <= bannerBusyPrio)) {
            if (hit.cls == SigDb.CLS_POLICE) {
                if (lastStrobeMs == 0 || now - lastStrobeMs > SigDb.POLICE_COOLDOWN_MS) {
                    lastStrobeMs = now; alerted = true;
                }
            } else if (hit.cls == SigDb.CLS_SKIMMER) {
                if (lastSkimmerMs == 0 || now - lastSkimmerMs > SigDb.SKIMMER_COOLDOWN_MS) {
                    lastSkimmerMs = now; alerted = true;
                }
            } else { // TRACKER — per-device 4 s cooldown, like the firmware's re-trigger gate
                Long lt = lastTrackerAlert.get(mac);
                if (lt == null || now - lt > SigDb.TRACKER_COOLDOWN_MS) {
                    lastTrackerAlert.put(mac, now); alerted = true;
                }
            }
            if (alerted) {
                bannerBusyPrio = hit.cls;
                bannerBusyUntilMs = now + (hit.cls == SigDb.CLS_POLICE ? ALERT_POLICE_MS
                    : hit.cls == SigDb.CLS_SKIMMER ? ALERT_SKIMMER_MS : ALERT_TRACKER_MS);
            }
        }
        if (alerted) {
            alertCount++;
            fireAlert(hit, mac, rssi);
            HitEvent ev = logged != null ? logged : lastHit;
            if (listener != null && ev != null) listener.onHit(ev, true);
        }
        updateRunNotification();
        notifyState();
    }

    // Foxhunt: while a tracker sits inside the close gate, tick at the
    // firmware's RSSI-scaled period — walk closer, the pulse quickens.
    private final Runnable foxLoop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (!discreet) {
                ActiveContact t = activeTracker();
                long now = System.currentTimeMillis();
                if (t != null && now >= nextFoxTickMs) {
                    foxTick();
                    nextFoxTickMs = now + SigDb.trackerPeriodMs(t.rssi);
                }
            }
            h.postDelayed(this, 200);
        }
    };

    private void foxTick() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(45, 120));
                else v.vibrate(45);
            }
        } catch (Exception ignored) {}
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_ALARM, 55);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 55);
            h.postDelayed(() -> { try { tg.release(); } catch (Exception ignored) {} }, 150);
        } catch (Exception ignored) {}
    }

    private void fireAlert(SigDb.Hit hit, String mac, int rssi) {
        // class-signature buzz: police = pager beep + long buzz; skimmer =
        // double-tap; tracker = triple soft tap (the foxhunt loop does the rest)
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                long[] pat = hit.cls == SigDb.CLS_POLICE
                    ? new long[]{0, 150, 80, 150, 80, 450}
                    : hit.cls == SigDb.CLS_SKIMMER
                    ? new long[]{0, 120, 90, 120}
                    : new long[]{0, 80, 70, 80, 70, 80};
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pat, -1));
                else v.vibrate(pat, -1);
            }
        } catch (Exception ignored) {}
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            tg.startTone(hit.cls == SigDb.CLS_POLICE ? ToneGenerator.TONE_PROP_BEEP2
                       : hit.cls == SigDb.CLS_SKIMMER ? ToneGenerator.TONE_PROP_ACK
                       : ToneGenerator.TONE_PROP_BEEP, 400);
            h.postDelayed(() -> { try { tg.release(); } catch (Exception ignored) {} }, 600);
        } catch (Exception ignored) {}
        // class banner screen (full-screen intent; direct start when our UI is up)
        try {
            Intent si = new Intent(this, StrobeActivity.class);
            si.putExtra("why", hit.why);
            si.putExtra("mac", mac);
            si.putExtra("rssi", rssi);
            si.putExtra("cls", hit.cls);
            si.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 2, si,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String title = hit.cls == SigDb.CLS_POLICE ? "⚠ POLICE GEAR ⚠"
                         : hit.cls == SigDb.CLS_SKIMMER ? "⚠ SKIMMER CLOSE ⚠"
                         : "◉ TRACKER CLOSE ◉";
            Notification n = new Notification.Builder(this, CHAN_ALERT)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title + " — " + hit.why)
                .setContentText(mac + "  " + rssi + " dBm")
                .setPriority(Notification.PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(2, n);
            if (uiForeground) startActivity(si);
        } catch (Exception ignored) {}
    }

    // ---------------- notifications / state ----------------
    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHAN_RUN, "Scanner", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel a = new NotificationChannel(CHAN_ALERT, "Contact alerts", NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("Cop Spotter contact alerts");
        nm.createNotificationChannel(a);
    }

    private Notification buildRunNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ScannerService.class).setAction("STOP");
        PendingIntent psi = PendingIntent.getService(this, 1, stop,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String txt = "BT " + btState + " · WIFI " + wifiState + " · contacts " + alertCount;
        return new Notification.Builder(this, CHAN_RUN)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(discreet ? "COP SPOTTER — ON DUTY (DISCREET)" : "COP SPOTTER — ON DUTY")
            .setContentText(txt)
            .setContentIntent(pi)
            .addAction(0, "STOP", psi)
            .setOngoing(true)
            .build();
    }

    private void updateRunNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && running) nm.notify(1, buildRunNotification());
    }

    private void notifyState() { if (listener != null) listener.onState(); }

    // ---------------- pager log persistence ----------------
    private void saveLog() {
        try {
            StringBuilder sb = new StringBuilder();
            for (HitEvent e : LOG) {
                sb.append(e.timeMs).append('\t').append(e.kind).append('\t').append(e.why)
                  .append('\t').append(e.mac).append('\t').append(e.rssi).append('\t').append(e.strong)
                  .append('\t').append(e.cls).append('\n');
            }
            getSharedPreferences("pager", MODE_PRIVATE).edit().putString("log", sb.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void loadLog() {
        try {
            String s = getSharedPreferences("pager", MODE_PRIVATE).getString("log", "");
            if (s == null || s.isEmpty()) return;
            for (String line : s.split("\n")) {
                String[] p = line.split("\t");
                if (p.length == 6 || p.length == 7) { // v1.0.3 lines have no class field (all police)
                    int cls = p.length == 7 ? Integer.parseInt(p[6]) : SigDb.CLS_POLICE;
                    LOG.add(new HitEvent(Long.parseLong(p[0]), p[1], p[2], p[3],
                        Integer.parseInt(p[4]), Boolean.parseBoolean(p[5]), cls));
                }
            }
            if (!LOG.isEmpty()) lastHit = LOG.get(LOG.size() - 1);
        } catch (Exception ignored) {}
    }

    public static void clearLog(Context c) {
        LOG.clear();
        lastHit = null;
        alertCount = 0; packetCount = 0;
        lastStrobeMs = 0; lastSkimmerMs = 0; lastTrackerAlert.clear();
        bannerBusyUntilMs = 0; bannerBusyPrio = 0;
        try { c.getSharedPreferences("pager", MODE_PRIVATE).edit().remove("log").apply(); }
        catch (Exception ignored) {}
    }
}
