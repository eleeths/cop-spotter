package com.eleeth.copspotterapp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cop Spotter signature engine v3 — a faithful port of the ESP32-C6 firmware's
 * matcher (box ~/cop-spotter/main/main.c, v3, 2026-10-17 tables).
 * Three classes, same tables, same priority order, same gates:
 *   POLICE  (84 sigs) — alert at RSSI >= -80, 30 s strobe cooldown
 *   SKIMMER ( 9 sigs) — close range only, RSSI >= -70, 10 s cooldown
 *   TRACKER (10 sigs) — close range only, RSSI >= -70, 4 s cooldown,
 *                       foxhunt pulse period scales with signal strength
 * Pure Java (no Android types) so it can be host-tested on a plain JVM.
 */
public final class SigDb {

    public static final int CLS_TRACKER = 1;
    public static final int CLS_SKIMMER = 2;
    public static final int CLS_POLICE  = 3;

    public static final int POLICE_COUNT = 84;
    public static final int TRACKER_COUNT = 10;
    public static final int SKIMMER_COUNT = 9;
    public static final int TOTAL_COUNT = POLICE_COUNT + TRACKER_COUNT + SKIMMER_COUNT;

    public static String clsTag(int cls) {
        switch (cls) {
            case CLS_POLICE:  return "POLICE";
            case CLS_SKIMMER: return "SKIMMER";
            default:          return "TRACKER";
        }
    }

    public static final class Hit {
        public final String kind;   // "BLE" or "WIFI"
        public final int cls;       // CLS_POLICE / CLS_SKIMMER / CLS_TRACKER
        public final String why;    // firmware-style reason string
        public final boolean strong;
        Hit(String kind, int cls, String why, boolean strong) {
            this.kind = kind; this.cls = cls; this.why = why; this.strong = strong;
        }
    }

    private static final class U16 {
        final int id; final String label; final boolean strong;
        U16(int id, String label, boolean strong) { this.id = id; this.label = label; this.strong = strong; }
    }
    private static final class Oui {
        final byte[] oui; final String label; final boolean strong;
        Oui(int a, int b, int c, String label, boolean strong) {
            this.oui = new byte[]{(byte) a, (byte) b, (byte) c};
            this.label = label; this.strong = strong;
        }
    }

    // ================= POLICE =================
    // ---- BLE company IDs (firmware CID_SIGS) ----
    private static final U16[] CID_SIGS = {
        new U16(0x034D, "Axon", true),
        new U16(0x04EC, "Motorola Solutions", false),
        new U16(0x0A5F, "Stryker LIFEPAK", true),
        new U16(0x08AA, "DJI drone", true),
        new U16(0x0275, "Geotab fleet telematics", true),
        new U16(0x09C8, "Flock Safety radio (XUNTONG)", true),
    };
    // ---- BLE 16-bit service UUIDs (firmware UUID_SIGS) ----
    private static final U16[] UUID_SIGS = {
        new U16(0xFE6B, "Axon", true), new U16(0xFE6C, "Axon", true), new U16(0xFC81, "Axon", true),
        new U16(0xFD8E, "Motorola", false), new U16(0xFE04, "Motorola", false),
        new U16(0xFC6E, "Stryker LIFEPAK", true),
        new U16(0xFFFA, "Open Drone ID (drone)", true),
        new U16(0x3100, "Flock Raven gunshot sensor", true), new U16(0x3200, "Flock Raven gunshot sensor", true),
        new U16(0x3300, "Flock Raven gunshot sensor", true), new U16(0x3400, "Flock Raven gunshot sensor", true),
        new U16(0x3500, "Flock Raven gunshot sensor", true),
        new U16(0xFD5F, "Ray-Ban Meta glasses", true),
    };
    // ---- Extra OUIs (firmware EXTRA_OUIS) ----
    private static final Oui[] EXTRA_OUIS = {
        new Oui(0x00, 0x19, 0x86, "WatchGuard OUI 00:19:86", false),
        new Oui(0x00, 0x1A, 0xE9, "WatchGuard OUI 00:1A:E9", false),
        new Oui(0x00, 0x11, 0x24, "Digital Ally OUI 00:11:24", false),
        new Oui(0x00, 0x1B, 0x63, "Digital Ally OUI 00:1B:63", false),
        new Oui(0x00, 0x30, 0x44, "Cradlepoint vehicle router", true),
        new Oui(0x00, 0xE0, 0x1C, "Cradlepoint vehicle router", true),
        new Oui(0x60, 0x60, 0x1F, "DJI OUI (drone)", true),
        new Oui(0x34, 0xD2, 0x62, "DJI OUI (drone)", true),
        new Oui(0x48, 0x1C, 0xB9, "DJI OUI (drone)", true),
        new Oui(0xE4, 0x7A, 0x2C, "DJI OUI (drone)", true),
        new Oui(0x58, 0xB8, 0x58, "DJI OUI (drone)", true),
        new Oui(0x04, 0xA8, 0x5A, "DJI OUI (drone)", true),
        new Oui(0x8C, 0x58, 0x23, "DJI OUI (drone)", true),
        new Oui(0x0C, 0x9A, 0xE6, "DJI OUI (drone)", true),
        new Oui(0x88, 0x29, 0x85, "DJI OUI (drone)", true),
        new Oui(0x4C, 0x43, 0xF6, "DJI OUI (drone)", true),
    };
    private static final byte[] AXON_OUI       = {(byte) 0x00, (byte) 0x25, (byte) 0xDF};
    private static final byte[] VIEVU_OUI      = {(byte) 0xFC, (byte) 0x01, (byte) 0x9E};
    private static final byte[] FLOCK_OUI      = {(byte) 0xB4, (byte) 0x1E, (byte) 0x52};
    private static final byte[] WATCHGUARD_OUI = {(byte) 0x00, (byte) 0x1D, (byte) 0x96};
    private static final byte[] DIGITALALLY_OUI = {(byte) 0x00, (byte) 0x23, (byte) 0xBD};
    // ALPR / gunshot-sensor OUIs: Genetec AutoVu + ShotSpotter (firmware ALPR_OUIS, strong)
    private static final byte[][] ALPR_OUIS = {
        {0x00, (byte) 0xBF, 0x15}, {0x0C, (byte) 0xBF, 0x15}, {(byte) 0xD4, 0x11, (byte) 0xD6},
    };
    private static final byte[][] MOTO_OUIS = {
        {0x00, 0x04, 0x7D}, {0x00, 0x18, (byte) 0x85}, {0x00, 0x1F, (byte) 0x92}, {0x4C, (byte) 0xCC, 0x34},
        {0x10, 0x74, 0x6F}, {(byte) 0xB8, (byte) 0xE2, (byte) 0x8C}, {(byte) 0x9C, (byte) 0x86, 0x2B},
    };
    // Flock Penguin accessory OUIs (weak: accessory-class, log-tier)
    private static final byte[][] FLOCK_ACC_OUIS = {
        {(byte) 0xEC, 0x1B, (byte) 0xBD}, {0x58, (byte) 0x8E, (byte) 0x81}, {(byte) 0x90, 0x35, (byte) 0xEA},
        {(byte) 0xB4, (byte) 0xE3, (byte) 0xF9}, {0x04, 0x0D, (byte) 0x84}, {(byte) 0xF0, (byte) 0x82, (byte) 0xC0},
        {0x1C, 0x34, (byte) 0xF1}, {0x38, 0x5B, 0x44}, {(byte) 0x94, 0x34, 0x69},
    };
    // Drone OUIs beyond DJI: Skydio + Parrot (firmware DRONE_OUIS, strong)
    private static final byte[][] DRONE_OUIS = {
        {0x38, 0x1D, 0x14},
        {(byte) 0x90, 0x03, (byte) 0xB7}, {(byte) 0x90, 0x3A, (byte) 0xE6}, {(byte) 0xA0, 0x14, 0x3D},
        {0x00, 0x12, 0x1C}, {0x00, 0x26, 0x7E},
    };
    // Flock camera 128-bit GATT service UUID (canonical string form + on-air LSB-first bytes)
    public static final String FLOCK_GATT_UUID128 = "e8ccbb38-9532-46a8-9fe5-1814df172e6f";
    private static final byte[] FLOCK_GATT_UUID128_AIR = {
        0x6f, 0x17, (byte) 0xd4, (byte) 0xf1, (byte) 0xdf, 0x18, (byte) 0xe5, (byte) 0x9f,
        (byte) 0xa8, 0x46, 0x32, (byte) 0x95, 0x38, (byte) 0xbb, (byte) 0xcc, (byte) 0xe8,
    };
    private static final String[] COP_NAME_FRAGS = {
        "BWCDEVICE", "TASER", "FS Ext Battery", "Pigvision", "Penguin-", "Flock", "BodyWorn Remote",
    };
    private static final String[] WEAK_SSID_PREFIX = {"AB2-", "AB3-", "AB4-", "AXON-", "VISTA_"};

    // ================= TRACKER =================
    private static final U16[] TRACKER_UUIDS = {
        new U16(0xFEED, "Tile tracker", true), new U16(0xFEEC, "Tile tracker", true),
        new U16(0xFD84, "Tile tracker", true),
        new U16(0xFD5A, "Samsung SmartTag", true),
        new U16(0xFE33, "Chipolo tracker", true), new U16(0xFE65, "Chipolo tracker", true),
        new U16(0xFCB2, "DULT-standard tracker", true),
    };
    private static final U16[] TRACKER_CIDS = {
        new U16(0x08C3, "Chipolo tracker", true),
    };

    // ================= SKIMMER =================
    private static final U16[] SKIMMER_UUIDS = {
        new U16(0x1101, "serial-port module (skimmer class)", true),
    };
    private static final String[] SKIMMER_NAMES = {"HC-03", "HC-05", "HC-06", "RN42", "BT04-A"};
    private static final Oui[] SKIMMER_OUIS = {
        new Oui(0x20, 0x13, 0x00, "Linvor module OUI (skimmer class, LOW)", false),
        new Oui(0x98, 0xD3, 0x31, "Bolutek module OUI (skimmer class, LOW)", false),
        new Oui(0x00, 0x1A, 0x7D, "CSR module OUI (skimmer class, LOW)", false),
    };

    // ================= alert policy (firmware alert_trigger) =================
    public static final int RSSI_STRONG = -80;        // police alert floor
    public static final int CLOSE_RSSI_GATE = -70;    // tracker/skimmer close-range gate
    public static final long POLICE_COOLDOWN_MS  = 30_000;
    public static final long SKIMMER_COOLDOWN_MS = 10_000;
    public static final long TRACKER_COOLDOWN_MS = 4_000;

    /** Firmware gate: does a hit of this class at this RSSI qualify to alert? */
    public static boolean alertGate(int cls, int rssi) {
        if (cls == CLS_POLICE) return rssi >= RSSI_STRONG;
        return rssi >= CLOSE_RSSI_GATE;
    }

    /** Foxhunt: purple period 1100 ms @ -70 dBm down to 180 ms @ -40 dBm. */
    public static int trackerPeriodMs(int rssi) {
        int period = 1100 - (rssi - CLOSE_RSSI_GATE) * 30;
        return period < 180 ? 180 : period;
    }

    // ================= helpers =================
    private static byte[] macBytes(String mac) {
        if (mac == null) return null;
        String[] p = mac.split(":");
        if (p.length != 6) return null;
        byte[] b = new byte[6];
        try {
            for (int i = 0; i < 6; i++) b[i] = (byte) Integer.parseInt(p[i], 16);
        } catch (NumberFormatException e) { return null; }
        return b;
    }
    private static boolean ouiEq(byte[] mac, byte[] oui) {
        return mac != null && mac[0] == oui[0] && mac[1] == oui[1] && mac[2] == oui[2];
    }
    private static boolean containsBytes(byte[] buf, byte[] needle) {
        if (buf == null || buf.length < needle.length) return false;
        outer:
        for (int i = 0; i + needle.length <= buf.length; i++) {
            for (int j = 0; j < needle.length; j++) if (buf[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }
    /** "FS-" + 4-12 hex digits = Flock accessory BLE name (firmware is_flock_fs_name). */
    static boolean isFlockFsName(String name) {
        if (name == null || !name.startsWith("FS-")) return false;
        int n = 0;
        for (int i = 3; i < name.length(); i++, n++) {
            char c = name.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f'))) return false;
        }
        return n >= 4 && n <= 12;
    }

    /**
     * BLE match — mirrors ble_scan_cb() in the firmware. Returns one Hit per
     * matched class (firmware reports every class that matched; the alert
     * priority police > skimmer > tracker is enforced by the caller).
     * @param mac       advertiser MAC "AA:BB:CC:DD:EE:FF"
     * @param name      advertised local name (may be null)
     * @param mfrData   company ID -> manufacturer payload (payload excludes the CID bytes)
     * @param uuid16s   all advertised 16-bit service UUIDs (lists + service-data keys)
     * @param svcData16 16-bit service UUID -> service-data payload
     * @param uuid128s  all advertised 128-bit service UUIDs, canonical lowercase strings
     * @param raw       raw advertisement bytes (for the BWCDEVICE / UUID128 payload checks)
     */
    public static List<Hit> matchBle(String mac, String name, Map<Integer, byte[]> mfrData,
                                     Set<Integer> uuid16s, Map<Integer, byte[]> svcData16,
                                     Set<String> uuid128s, byte[] raw) {
        byte[] m = macBytes(mac);
        List<Hit> out = new ArrayList<>();
        String why = null; boolean strong = false, any = false;
        String trkWhy = null, skimWhy = null;

        // ---- OUI tables (police first, firmware order) ----
        if (ouiEq(m, AXON_OUI))            { why = "Axon OUI 00:25:DF"; strong = any = true; }
        else if (ouiEq(m, VIEVU_OUI))      { why = "VIEVU/Axon OUI FC:01:9E"; strong = any = true; }
        else if (ouiEq(m, FLOCK_OUI))      { why = "Flock OUI B4:1E:52"; strong = any = true; }
        else if (ouiEq(m, WATCHGUARD_OUI)) { why = "WatchGuard OUI 00:1D:96"; any = true; }
        else if (ouiEq(m, DIGITALALLY_OUI)) { why = "Digital Ally OUI 00:23:BD"; any = true; }
        else {
            for (byte[] o : MOTO_OUIS) if (ouiEq(m, o)) { why = "Motorola OUI"; any = true; break; }
            if (!any) for (byte[] o : FLOCK_ACC_OUIS) if (ouiEq(m, o)) { why = "Flock accessory OUI"; any = true; break; }
            if (!any) for (byte[] o : ALPR_OUIS) if (ouiEq(m, o)) {
                why = String.format("ALPR/sensor OUI %02X:%02X:%02X", o[0] & 0xFF, o[1] & 0xFF, o[2] & 0xFF);
                strong = any = true; break;
            }
            if (!any) for (byte[] o : DRONE_OUIS) if (ouiEq(m, o)) {
                why = String.format("drone OUI %02X:%02X:%02X (Skydio/Parrot)", o[0] & 0xFF, o[1] & 0xFF, o[2] & 0xFF);
                strong = any = true; break;
            }
            if (!any) for (Oui o : EXTRA_OUIS) if (ouiEq(m, o.oui)) {
                why = o.label; any = true; if (o.strong) strong = true; break;
            }
        }
        for (Oui o : SKIMMER_OUIS) if (ouiEq(m, o.oui)) { skimWhy = o.label; break; }

        // The money signature can sit anywhere in the payload; in the field it
        // rides inside a 128-bit UUID, so the reversed order counts too.
        if (containsBytes(raw, new byte[]{'B', 'W', 'C', 'D', 'E', 'V', 'I', 'C', 'E'})
                || containsBytes(raw, new byte[]{'E', 'C', 'I', 'V', 'E', 'D', 'C', 'W', 'B'})) {
            why = "BWCDEVICE in payload (Axon bodycam)";
            strong = any = true;
        }
        // Flock 128-bit GATT UUID rides the raw bytes in on-air (LSB-first) order.
        if (containsBytes(raw, FLOCK_GATT_UUID128_AIR)
                || (uuid128s != null && uuid128s.contains(FLOCK_GATT_UUID128))) {
            why = "Flock GATT service UUID (128-bit)";
            strong = any = true;
        }

        // ---- manufacturer data (company IDs) ----
        if (mfrData != null) {
            for (Map.Entry<Integer, byte[]> e : mfrData.entrySet()) {
                int cid = e.getKey();
                byte[] payload = e.getValue();
                for (U16 s : CID_SIGS) if (cid == s.id) {
                    if (s.strong) { why = String.format("company ID 0x%04X (%s)", cid, s.label); strong = any = true; }
                    else if (!any) { why = String.format("company ID 0x%04X (%s)", cid, s.label); any = true; }
                }
                for (U16 s : TRACKER_CIDS) if (cid == s.id) {
                    trkWhy = String.format("company ID 0x%04X (%s)", cid, s.label);
                }
                // Apple offline-finding advert (AirTag-class): Apple CID + the
                // 12 19 status bytes. CID alone would fire on every iPhone.
                if (cid == 0x004C && payload != null && payload.length >= 2
                        && (payload[0] & 0xFF) == 0x12 && (payload[1] & 0xFF) == 0x19) {
                    trkWhy = "Apple Find My offline-finding advert (AirTag-class tracker)";
                }
            }
        }

        // ---- 16-bit service UUIDs (lists + service data) ----
        if (uuid16s != null) {
            for (int u : uuid16s) {
                for (U16 s : UUID_SIGS) if (u == s.id) {
                    if (s.strong) { why = String.format("UUID 0x%04X (%s)", u, s.label); strong = any = true; }
                    else if (!any) { why = String.format("UUID 0x%04X (%s)", u, s.label); any = true; }
                }
                for (U16 s : TRACKER_UUIDS) if (u == s.id) {
                    trkWhy = String.format("UUID 0x%04X (%s)", u, s.label);
                }
                for (U16 s : SKIMMER_UUIDS) if (u == s.id) {
                    skimWhy = String.format("UUID 0x%04X (%s)", u, s.label);
                }
            }
        }

        // ---- service data payloads: Google Find Hub (FMDN) frame rule ----
        if (svcData16 != null) {
            byte[] feaa = svcData16.get(0xFEAA);
            if (feaa != null && feaa.length >= 1 && (feaa[0] == 0x40 || feaa[0] == 0x41)) {
                trkWhy = String.format("Google Find Hub tracker frame (FEAA/0x%02X)", feaa[0] & 0xFF);
            }
        }

        // ---- local name ----
        if (name != null) {
            for (String frag : COP_NAME_FRAGS) {
                if (name.contains(frag)) {
                    why = "name '" + name + "' (frag '" + frag + "')";
                    strong = any = true;
                }
            }
            if (isFlockFsName(name)) {
                why = "name '" + name + "' (Flock FS- serial)";
                strong = any = true;
            }
            for (String frag : SKIMMER_NAMES) {
                if (name.contains(frag)) {
                    skimWhy = "name '" + name + "' (serial module, skimmer class)";
                }
            }
        }

        if (any)     out.add(new Hit("BLE", CLS_POLICE, why, strong));
        if (skimWhy != null) out.add(new Hit("BLE", CLS_SKIMMER, skimWhy, true));
        if (trkWhy != null)  out.add(new Hit("BLE", CLS_TRACKER, trkWhy, true));
        return out;
    }

    /** WiFi match — mirrors the AP loop in wifi_scan_task(). One Hit per matched class. */
    public static List<Hit> matchWifi(String ssid, String bssid) {
        byte[] b = macBytes(bssid);
        List<Hit> out = new ArrayList<>();
        String why = null; boolean strong = false, any = false;
        String skimWhy = null;

        if (ouiEq(b, FLOCK_OUI))           { why = "Flock OUI B4:1E:52 (AP)"; strong = any = true; }
        else if (ouiEq(b, AXON_OUI))       { why = "Axon OUI 00:25:DF (AP)"; strong = any = true; }
        else if (ouiEq(b, WATCHGUARD_OUI)) { why = "WatchGuard OUI (AP)"; any = true; }
        else if (ouiEq(b, DIGITALALLY_OUI)) { why = "Digital Ally OUI (AP)"; any = true; }
        else {
            for (byte[] o : ALPR_OUIS) if (ouiEq(b, o)) { why = "ALPR/sensor OUI (AP)"; strong = any = true; break; }
            if (!any) for (byte[] o : DRONE_OUIS) if (ouiEq(b, o)) { why = "drone OUI (Skydio/Parrot) (AP)"; strong = any = true; break; }
            if (!any) for (Oui o : EXTRA_OUIS) if (ouiEq(b, o.oui)) {
                why = o.label + " (AP)"; any = true; if (o.strong) strong = true; break;
            }
        }
        for (Oui o : SKIMMER_OUIS) if (ouiEq(b, o.oui)) { skimWhy = o.label + " (AP)"; break; }

        if (ssid != null && !ssid.isEmpty()) {
            if (ssid.startsWith("Flock-"))        { why = "Flock setup SSID '" + ssid + "'"; strong = any = true; }
            else if (ssid.equals("test_flck"))    { why = "Flock test SSID"; strong = any = true; }
            else if (ssid.equals("Flock"))        { why = "Flock exact SSID"; strong = any = true; }
            else if (ssid.startsWith("Flock Camera")) { why = "Flock Camera SSID '" + ssid + "'"; strong = any = true; }
            else if (ssid.startsWith("DJI-"))     { why = "DJI drone SSID '" + ssid + "'"; strong = any = true; }
            else if (ssid.startsWith("TELLO-"))   { why = "DJI Tello SSID '" + ssid + "'"; strong = any = true; }
            else for (String p : WEAK_SSID_PREFIX) if (ssid.startsWith(p)) {
                if (!any) why = "weak SSID '" + ssid + "'";
                any = true;
            }
        }
        if (any) out.add(new Hit("WIFI", CLS_POLICE, why, strong));
        if (skimWhy != null) out.add(new Hit("WIFI", CLS_SKIMMER, skimWhy, true));
        return out;
    }
}
