import com.eleeth.copspotterapp.SigDb;
import java.util.*;

public class TestMain {
    static int pass = 0, fail = 0;

    static SigDb.Hit of(List<SigDb.Hit> hits, int cls) {
        for (SigDb.Hit h : hits) if (h.cls == cls) return h;
        return null;
    }
    static void check(String name, List<SigDb.Hit> hits, int wantCls, boolean wantStrong) {
        SigDb.Hit h = of(hits, wantCls);
        boolean ok = h != null && h.strong == wantStrong;
        System.out.println((ok ? "PASS " : "FAIL ") + name + (h != null ? " -> " + h.kind + " [" + SigDb.clsTag(h.cls) + "] " + h.why + " strong=" + h.strong : " -> no " + SigDb.clsTag(wantCls) + " hit (got " + hits.size() + ")"));
        if (ok) pass++; else fail++;
    }
    static void checkNone(String name, List<SigDb.Hit> hits) {
        boolean ok = hits.isEmpty();
        System.out.println((ok ? "PASS " : "FAIL ") + name + " -> " + hits.size() + " hits");
        if (ok) pass++; else fail++;
    }
    static void checkBool(String name, boolean got, boolean want) {
        boolean ok = got == want;
        System.out.println((ok ? "PASS " : "FAIL ") + name + " -> " + got);
        if (ok) pass++; else fail++;
    }
    static void checkInt(String name, int got, int want) {
        boolean ok = got == want;
        System.out.println((ok ? "PASS " : "FAIL ") + name + " -> " + got + " (want " + want + ")");
        if (ok) pass++; else fail++;
    }

    static final String NEUTRAL = "11:22:33:44:55:66";
    static List<SigDb.Hit> ble(String mac, String name, Map<Integer, byte[]> mfr, Set<Integer> u16,
                               Map<Integer, byte[]> svc, Set<String> u128, byte[] raw) {
        return SigDb.matchBle(mac, name, mfr, u16, svc, u128, raw);
    }
    static Map<Integer, byte[]> mfr(int cid, byte... payload) {
        Map<Integer, byte[]> m = new HashMap<>(); m.put(cid, payload); return m;
    }
    static Set<Integer> uuids(int... us) {
        Set<Integer> s = new HashSet<>(); for (int u : us) s.add(u); return s;
    }

    public static void main(String[] a) {
        // ================= POLICE — carried over from the v2 suite =================
        check("BLE Axon CID strong", ble(NEUTRAL, null, mfr(0x034D, (byte)1, (byte)2), null, null, null, null), SigDb.CLS_POLICE, true);
        check("BLE Motorola CID weak", ble(NEUTRAL, null, mfr(0x04EC, (byte)1), null, null, null, null), SigDb.CLS_POLICE, false);
        check("BLE TASER name strong", ble(NEUTRAL, "TASER 7", null, null, null, null, null), SigDb.CLS_POLICE, true);
        check("BLE Axon UUID strong", ble(NEUTRAL, null, null, uuids(0xFE6B), null, null, null), SigDb.CLS_POLICE, true);
        check("BLE BWCDEVICE payload strong", ble(NEUTRAL, null, null, null, null, null, "xxBWCDEVICEyy".getBytes()), SigDb.CLS_POLICE, true);
        check("BLE ECIVEDCWB reversed payload strong", ble(NEUTRAL, null, null, null, null, null, "xxECIVEDCWBzz".getBytes()), SigDb.CLS_POLICE, true);
        check("BLE Flock OUI strong", ble("B4:1E:52:00:11:22", null, null, null, null, null, null), SigDb.CLS_POLICE, true);
        check("BLE Axon OUI strong", ble("00:25:DF:AA:BB:CC", null, null, null, null, null, null), SigDb.CLS_POLICE, true);
        check("BLE Motorola OUI weak", ble("00:04:7D:AA:BB:CC", null, null, null, null, null, null), SigDb.CLS_POLICE, false);
        check("BLE DJI OUI strong", ble("60:60:1F:AA:BB:CC", null, null, null, null, null, null), SigDb.CLS_POLICE, true);
        check("BLE Flock accessory OUI weak", ble("EC:1B:BD:AA:BB:CC", null, null, null, null, null, null), SigDb.CLS_POLICE, false);
        check("BLE Penguin name strong", ble(NEUTRAL, "Penguin-1234", null, null, null, null, null), SigDb.CLS_POLICE, true);
        checkNone("BLE random device no hit", ble(NEUTRAL, "JBL Flip", null, null, null, null, null));
        check("WIFI AXON- SSID weak", SigDb.matchWifi("AXON-8842", NEUTRAL), SigDb.CLS_POLICE, false);
        check("WIFI Flock- SSID strong", SigDb.matchWifi("Flock-AB12", NEUTRAL), SigDb.CLS_POLICE, true);
        check("WIFI Flock OUI AP strong", SigDb.matchWifi("HomeNet", "B4:1E:52:00:11:22"), SigDb.CLS_POLICE, true);
        check("WIFI DJI- SSID strong", SigDb.matchWifi("DJI-Mavic", NEUTRAL), SigDb.CLS_POLICE, true);
        check("WIFI Cradlepoint OUI strong", SigDb.matchWifi("CopCar", "00:30:44:11:22:33"), SigDb.CLS_POLICE, true);
        checkNone("WIFI home router no hit", SigDb.matchWifi("MyHomeWiFi", NEUTRAL));

        // ================= POLICE — v3 additions (2026-10-17) =================
        check("BLE XUNTONG/Flock CID 0x09C8 strong", ble(NEUTRAL, null, mfr(0x09C8, (byte)1), null, null, null, null), SigDb.CLS_POLICE, true);
        check("BLE Raven UUID 0x3100 strong", ble(NEUTRAL, null, null, uuids(0x3100), null, null, null), SigDb.CLS_POLICE, true);
        check("BLE Raven UUID 0x3500 strong", ble(NEUTRAL, null, null, uuids(0x3500), null, null, null), SigDb.CLS_POLICE, true);
        check("BLE Ray-Ban Meta UUID 0xFD5F strong", ble(NEUTRAL, null, null, uuids(0xFD5F), null, null, null), SigDb.CLS_POLICE, true);
        check("BLE Genetec ALPR OUI 00:BF:15 strong", ble("00:BF:15:AA:BB:CC", null, null, null, null, null, null), SigDb.CLS_POLICE, true);
        check("BLE ShotSpotter OUI D4:11:D6 strong", ble("D4:11:D6:AA:BB:CC", null, null, null, null, null, null), SigDb.CLS_POLICE, true);
        check("BLE Skydio OUI 38:1D:14 strong", ble("38:1D:14:AA:BB:CC", null, null, null, null, null, null), SigDb.CLS_POLICE, true);
        check("BLE Parrot OUI 90:03:B7 strong", ble("90:03:B7:AA:BB:CC", null, null, null, null, null, null), SigDb.CLS_POLICE, true);
        check("BLE FS- serial name strong", ble(NEUTRAL, "FS-1A2B3C4D", null, null, null, null, null), SigDb.CLS_POLICE, true);
        checkNone("BLE FS- too short no hit", ble(NEUTRAL, "FS-12", null, null, null, null, null));
        Set<String> flock128 = new HashSet<>(Arrays.asList(SigDb.FLOCK_GATT_UUID128));
        check("BLE Flock 128-bit UUID strong", ble(NEUTRAL, null, null, null, null, flock128, null), SigDb.CLS_POLICE, true);
        check("WIFI Flock exact SSID strong", SigDb.matchWifi("Flock", NEUTRAL), SigDb.CLS_POLICE, true);
        check("WIFI Flock Camera SSID strong", SigDb.matchWifi("Flock Camera 12", NEUTRAL), SigDb.CLS_POLICE, true);
        check("WIFI Genetec ALPR OUI AP strong", SigDb.matchWifi("CityNet", "0C:BF:15:11:22:33"), SigDb.CLS_POLICE, true);
        check("WIFI Parrot OUI AP strong", SigDb.matchWifi("DroneLink", "A0:14:3D:11:22:33"), SigDb.CLS_POLICE, true);
        check("WIFI TELLO- SSID strong", SigDb.matchWifi("TELLO-AB12CD", NEUTRAL), SigDb.CLS_POLICE, true);
        check("WIFI test_flck SSID strong", SigDb.matchWifi("test_flck", NEUTRAL), SigDb.CLS_POLICE, true);

        // ================= TRACKER =================
        byte[] findMy = new byte[22]; findMy[0] = 0x12; findMy[1] = 0x19;
        check("BLE Find My payload TRACKER", ble(NEUTRAL, null, mfr(0x004C, findMy), null, null, null, null), SigDb.CLS_TRACKER, true);
        checkNone("BLE bare Apple CID (no 12 19) NOTHING", ble(NEUTRAL, null, mfr(0x004C, (byte)0x10, (byte)0x00, (byte)0x55), null, null, null, null));
        checkNone("BLE bare Apple CID (1-byte payload) NOTHING", ble(NEUTRAL, null, mfr(0x004C, (byte)0x12), null, null, null, null));
        check("BLE Tile UUID 0xFEED TRACKER", ble(NEUTRAL, null, null, uuids(0xFEED), null, null, null), SigDb.CLS_TRACKER, true);
        check("BLE Tile UUID 0xFD84 TRACKER", ble(NEUTRAL, null, null, uuids(0xFD84), null, null, null), SigDb.CLS_TRACKER, true);
        check("BLE SmartTag UUID 0xFD5A (service list) TRACKER", ble(NEUTRAL, null, null, uuids(0xFD5A), null, null, null), SigDb.CLS_TRACKER, true);
        Map<Integer, byte[]> svcFd5a = new HashMap<>(); svcFd5a.put(0xFD5A, new byte[]{1});
        check("BLE SmartTag UUID 0xFD5A (service data) TRACKER", ble(NEUTRAL, null, null, uuids(0xFD5A), svcFd5a, null, null), SigDb.CLS_TRACKER, true);
        check("BLE Chipolo CID 0x08C3 TRACKER", ble(NEUTRAL, null, mfr(0x08C3, (byte)1), null, null, null, null), SigDb.CLS_TRACKER, true);
        check("BLE Chipolo UUID 0xFE33 TRACKER", ble(NEUTRAL, null, null, uuids(0xFE33), null, null, null), SigDb.CLS_TRACKER, true);
        check("BLE DULT UUID 0xFCB2 TRACKER", ble(NEUTRAL, null, null, uuids(0xFCB2), null, null, null), SigDb.CLS_TRACKER, true);
        Map<Integer, byte[]> fmdn40 = new HashMap<>(); fmdn40.put(0xFEAA, new byte[]{0x40, 0x11, 0x22});
        check("BLE Find Hub FEAA frame 0x40 TRACKER", ble(NEUTRAL, null, null, null, fmdn40, null, null), SigDb.CLS_TRACKER, true);
        Map<Integer, byte[]> fmdn41 = new HashMap<>(); fmdn41.put(0xFEAA, new byte[]{0x41});
        check("BLE Find Hub FEAA frame 0x41 TRACKER", ble(NEUTRAL, null, null, null, fmdn41, null, null), SigDb.CLS_TRACKER, true);
        Map<Integer, byte[]> fmdnBad = new HashMap<>(); fmdnBad.put(0xFEAA, new byte[]{0x00, 0x11});
        checkNone("BLE FEAA non-tracker frame NOTHING", ble(NEUTRAL, null, null, null, fmdnBad, null, null));

        // ================= SKIMMER =================
        check("BLE HC-05 name SKIMMER", ble(NEUTRAL, "HC-05", null, null, null, null, null), SigDb.CLS_SKIMMER, true);
        check("BLE HC-06 name SKIMMER", ble(NEUTRAL, "HC-06", null, null, null, null, null), SigDb.CLS_SKIMMER, true);
        check("BLE RN42 name SKIMMER", ble(NEUTRAL, "RN42-9F3A", null, null, null, null, null), SigDb.CLS_SKIMMER, true);
        check("BLE BT04-A name SKIMMER", ble(NEUTRAL, "BT04-A", null, null, null, null, null), SigDb.CLS_SKIMMER, true);
        check("BLE SPP UUID 0x1101 SKIMMER", ble(NEUTRAL, null, null, uuids(0x1101), null, null, null), SigDb.CLS_SKIMMER, true);
        check("BLE CSR OUI SKIMMER", ble("00:1A:7D:AA:BB:CC", null, null, null, null, null, null), SigDb.CLS_SKIMMER, true);
        check("BLE Linvor OUI SKIMMER", ble("20:13:00:AA:BB:CC", null, null, null, null, null, null), SigDb.CLS_SKIMMER, true);
        check("BLE Bolutek OUI SKIMMER", ble("98:D3:31:AA:BB:CC", null, null, null, null, null, null), SigDb.CLS_SKIMMER, true);
        check("WIFI Bolutek OUI SKIMMER", SigDb.matchWifi("SerialLink", "98:D3:31:11:22:33"), SigDb.CLS_SKIMMER, true);

        // ================= multi-class (the box-beacon collision) =================
        List<SigDb.Hit> dual = ble("00:1A:7D:DA:71:13", null, mfr(0x004C, findMy), null, null, null, null);
        check("BLE box advert -> SKIMMER half", dual, SigDb.CLS_SKIMMER, true);
        check("BLE box advert -> TRACKER half", dual, SigDb.CLS_TRACKER, true);
        checkBool("BLE box advert -> no POLICE half", of(dual, SigDb.CLS_POLICE) == null, true);

        // ================= random phone advert =================
        checkNone("BLE random phone (Google CID, A2DP uuid) NOTHING",
            ble(NEUTRAL, "Galaxy Buds2", mfr(0x00E0, (byte)1), uuids(0x110B), null, null, null));

        // ================= alert gates (firmware numbers) =================
        checkBool("gate POLICE @-80 true",  SigDb.alertGate(SigDb.CLS_POLICE, -80), true);
        checkBool("gate POLICE @-81 false", SigDb.alertGate(SigDb.CLS_POLICE, -81), false);
        checkBool("gate POLICE @-45 true",  SigDb.alertGate(SigDb.CLS_POLICE, -45), true);
        checkBool("gate SKIMMER @-70 true",  SigDb.alertGate(SigDb.CLS_SKIMMER, -70), true);
        checkBool("gate SKIMMER @-71 false", SigDb.alertGate(SigDb.CLS_SKIMMER, -71), false);
        checkBool("gate TRACKER @-70 true",  SigDb.alertGate(SigDb.CLS_TRACKER, -70), true);
        checkBool("gate TRACKER @-85 false", SigDb.alertGate(SigDb.CLS_TRACKER, -85), false);
        checkBool("gate TRACKER @-41 true",  SigDb.alertGate(SigDb.CLS_TRACKER, -41), true);
        checkInt("foxhunt period @-70 = 1100", SigDb.trackerPeriodMs(-70), 1100);
        checkInt("foxhunt period @-55 = 650",  SigDb.trackerPeriodMs(-55), 650);
        checkInt("foxhunt period @-40 = 200",  SigDb.trackerPeriodMs(-40), 200);
        checkInt("foxhunt period @-30 clamps 180", SigDb.trackerPeriodMs(-30), 180);

        System.out.println("== " + pass + " passed, " + fail + " failed ==");
        if (fail > 0) System.exit(1);
    }
}
