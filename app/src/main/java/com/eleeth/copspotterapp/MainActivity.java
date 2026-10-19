package com.eleeth.copspotterapp;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Cop Spotter v2 — old-school Motorola pager fused with military field gear.
 * Gunmetal body, corner rivets, etched spec plate, and a backlit pager-LCD
 * centerpiece (dot-matrix monospace, segmented signal meter). The phone's own
 * radios do the listening (ScannerService + SigDb v3: 103 signatures,
 * three classes). Alerts break the monochrome in class colors.
 */
public class MainActivity extends Activity implements ScannerService.Listener {

    // ---- body palette ----
    private static final int STEEL_HI = Color.parseColor("#3A4046");
    private static final int STEEL_LO = Color.parseColor("#22262B");
    private static final int PANEL_LINE = Color.parseColor("#4A5058");
    private static final int ETCH = Color.parseColor("#8A9298");
    private static final int CREAM = Color.parseColor("#E6E2C8");
    private static final int OLIVE_HI = Color.parseColor("#4E563C");
    private static final int OLIVE_LO = Color.parseColor("#363D29");
    // ---- pager LCD palette ----
    private static final int LCD_HI = Color.parseColor("#AEBB7E");
    private static final int LCD_LO = Color.parseColor("#95A55F");
    private static final int LCD_DIM_HI = Color.parseColor("#79855A");
    private static final int LCD_DIM_LO = Color.parseColor("#66744A");
    private static final int LCD_INK = Color.parseColor("#18230E");
    private static final int LCD_INK_DIM = Color.parseColor("#4A5A30");
    private static final int LCD_SEG_OFF = Color.parseColor("#8A9A5F");
    // ---- class colors (function first) ----
    private static final int CLS_POLICE = Color.parseColor("#FF4747");
    private static final int CLS_TRACKER = Color.parseColor("#9B5DE5");
    private static final int CLS_SKIMMER = Color.parseColor("#FFB703");
    // ---- log phosphor ----
    private static final int LOG_BG = Color.parseColor("#070905");
    private static final int LOG_TEXT = Color.parseColor("#93A487");
    // ---- LEDs ----
    private static final int LED_GREEN = Color.parseColor("#59D977");
    private static final int LED_AMBER = Color.parseColor("#FFB703");
    private static final int LED_OFF = Color.parseColor("#2A2F26");

    private TextView clockView, statusBig, statusSub, radioLine, meterDbm, logText;
    private TextView classChip, ledScanDot, ledDiscDot, discreetBtnLabel;
    private Button toggleBtn, discreetBtn;
    private ScrollView logScroll;
    private FrameLayout screenRoot;
    private LinearLayout lcdPanel, lcdBezel;
    private GradientDrawable bezDrawable;
    private final View[] segs = new View[10];
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean flashing = false;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView stencil(String text, float size, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setLetterSpacing(0.14f);
        return t;
    }
    private TextView lcd(String text, float size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(LCD_INK);
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setLetterSpacing(0.05f);
        return t;
    }

    private GradientDrawable grad(int hi, int lo, int stroke, int strokeW, int radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{hi, lo});
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(strokeW), stroke);
        return g;
    }
    private Drawable chunky(int hi, int lo, int stroke) {
        GradientDrawable normal = grad(hi, lo, stroke, 2, 8);
        GradientDrawable pressed = grad(lo, hi, stroke, 2, 8);
        LayerDrawable ln = new LayerDrawable(new Drawable[]{normal});
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{}, ln);
        return s;
    }
    private Drawable ledDot() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(LED_OFF);
        g.setStroke(dp(1), Color.parseColor("#14161A"));
        return g;
    }

    /** Corner screw/rivet for the body shell. */
    private static final class Rivet extends View {
        private final float angle;
        Rivet(Context c, float a) { super(c); angle = a; }
        @Override protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - 1;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.parseColor("#565E66"));
            cv.drawCircle(cx, cy, r, p);
            p.setColor(Color.parseColor("#2E3338"));
            cv.drawCircle(cx, cy, r * 0.58f, p);
            p.setColor(Color.parseColor("#14161A"));
            cv.save();
            cv.rotate(angle, cx, cy);
            cv.drawRect(cx - r * 0.52f, cy - r * 0.13f, cx + r * 0.52f, cy + r * 0.13f, p);
            cv.restore();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
            p.setColor(Color.parseColor("#14161A"));
            cv.drawCircle(cx, cy, r, p);
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ScannerService.loadDiscreet(this);

        screenRoot = new FrameLayout(this);
        screenRoot.setBackground(grad(Color.parseColor("#31363C"), Color.parseColor("#1B1E23"), 0, 0, 0));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(14), dp(16), dp(12));
        screenRoot.addView(body, new FrameLayout.LayoutParams(-1, -1));

        // corner rivets
        int[][] corners = {{Gravity.TOP | Gravity.LEFT, 23}, {Gravity.TOP | Gravity.RIGHT, 71},
                           {Gravity.BOTTOM | Gravity.LEFT, 48}, {Gravity.BOTTOM | Gravity.RIGHT, 97}};
        for (int[] c : corners) {
            Rivet r = new Rivet(this, c[1]);
            FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(15), dp(15));
            rp.gravity = c[0];
            rp.setMargins(dp(5), dp(5), dp(5), dp(5));
            screenRoot.addView(r, rp);
        }

        // ---- brand deck ----
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brandCol = new LinearLayout(this);
        brandCol.setOrientation(LinearLayout.VERTICAL);
        TextView brand = stencil("COP SPOTTER", 21, CREAM);
        TextView brandSub = stencil("PROXIMITY PAGER ▸ FIELD ISSUE", 8.5f, ETCH);
        brandCol.addView(brand);
        brandCol.addView(brandSub);
        head.addView(brandCol, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout idCol = new LinearLayout(this);
        idCol.setOrientation(LinearLayout.VERTICAL);
        TextView unit = stencil("FIELD UNIT 01", 9, ETCH);
        unit.setGravity(Gravity.RIGHT);
        clockView = stencil("--:--:--Z", 14, CREAM);
        clockView.setGravity(Gravity.RIGHT);
        idCol.addView(unit);
        idCol.addView(clockView);
        head.addView(idCol);
        body.addView(head);

        // LED row: SCAN / DISCREET indicators
        LinearLayout leds = new LinearLayout(this);
        leds.setOrientation(LinearLayout.HORIZONTAL);
        leds.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = dp(8);
        ledScanDot = new TextView(this);
        ledScanDot.setBackground(ledDot());
        leds.addView(ledScanDot, new LinearLayout.LayoutParams(dp(11), dp(11)));
        TextView l1 = stencil("SCAN", 8.5f, ETCH);
        LinearLayout.LayoutParams l1p = new LinearLayout.LayoutParams(-2, -2);
        l1p.leftMargin = dp(5); l1p.rightMargin = dp(16);
        leds.addView(l1, l1p);
        ledDiscDot = new TextView(this);
        ledDiscDot.setBackground(ledDot());
        leds.addView(ledDiscDot, new LinearLayout.LayoutParams(dp(11), dp(11)));
        TextView l2 = stencil("DISCREET", 8.5f, ETCH);
        LinearLayout.LayoutParams l2p = new LinearLayout.LayoutParams(-2, -2);
        l2p.leftMargin = dp(5);
        leds.addView(l2, l2p);
        body.addView(leds, llp);

        // ---- etched spec plate ----
        LinearLayout plate = new LinearLayout(this);
        plate.setOrientation(LinearLayout.VERTICAL);
        plate.setBackground(grad(Color.parseColor("#26292E"), Color.parseColor("#1E2126"), Color.parseColor("#121418"), 1, 4));
        plate.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = dp(10);
        plate.addView(stencil("MODEL: COP SPOTTER CS-PAGER ▸ SN 2026-103 ▸ MIL ISSUE", 8.5f, ETCH));
        plate.addView(stencil("SIGNATURES: 103 — POLICE 84 · TRACKER 10 · SKIMMER 9", 8.5f, ETCH));
        plate.addView(stencil("RF: BLE 5 + WI-FI 2.4 GHZ ▸ MODE: PASSIVE RX ONLY", 8.5f, ETCH));
        body.addView(plate, plp);

        // ---- pager LCD centerpiece ----
        lcdBezel = new LinearLayout(this);
        lcdBezel.setOrientation(LinearLayout.VERTICAL);
        bezDrawable = new GradientDrawable();
        bezDrawable.setColor(Color.parseColor("#060708"));
        bezDrawable.setCornerRadius(dp(10));
        bezDrawable.setStroke(dp(1), Color.parseColor("#000000"));
        lcdBezel.setBackground(bezDrawable);
        lcdBezel.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(-1, -2);
        shp.topMargin = dp(12);
        body.addView(lcdBezel, shp);

        lcdPanel = new LinearLayout(this);
        lcdPanel.setOrientation(LinearLayout.VERTICAL);
        lcdPanel.setPadding(dp(12), dp(10), dp(12), dp(9));
        lcdBezel.addView(lcdPanel);

        TextView lcdHead = lcd("COP SPOTTER // PROXIMITY WATCH", 9.5f, true);
        lcdHead.setTextColor(LCD_INK_DIM);
        lcdPanel.addView(lcdHead);
        statusBig = lcd("STANDBY", 30, true);
        lcdPanel.addView(statusBig);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        classChip = new TextView(this);
        classChip.setTextSize(9.5f);
        classChip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        classChip.setPadding(dp(6), dp(2), dp(6), dp(2));
        classChip.setVisibility(View.GONE);
        chipRow.addView(classChip);
        statusSub = lcd("PRESS START WATCH", 11, false);
        statusSub.setMaxLines(2);
        statusSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ssp = new LinearLayout.LayoutParams(0, -2, 1);
        ssp.leftMargin = dp(7);
        chipRow.addView(statusSub, ssp);
        lcdPanel.addView(chipRow);

        // segmented signal meter
        LinearLayout meter = new LinearLayout(this);
        meter.setOrientation(LinearLayout.HORIZONTAL);
        meter.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = dp(9);
        TextView sigLbl = lcd("SIG", 9.5f, true);
        sigLbl.setTextColor(LCD_INK_DIM);
        meter.addView(sigLbl);
        for (int i = 0; i < 10; i++) {
            segs[i] = new View(this);
            segs[i].setBackgroundColor(LCD_SEG_OFF);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(13), dp(13));
            sl.leftMargin = dp(3);
            meter.addView(segs[i], sl);
        }
        meterDbm = lcd("--- dBm", 11.5f, true);
        LinearLayout.LayoutParams mdl = new LinearLayout.LayoutParams(-2, -2);
        mdl.leftMargin = dp(9);
        meter.addView(meterDbm, mdl);
        lcdPanel.addView(meter, mlp);

        View lcdDiv = new View(this);
        lcdDiv.setBackgroundColor(LCD_INK_DIM);
        LinearLayout.LayoutParams dvp = new LinearLayout.LayoutParams(-1, dp(1));
        dvp.topMargin = dp(8); dvp.bottomMargin = dp(5);
        lcdPanel.addView(lcdDiv, dvp);
        radioLine = lcd("BT IDLE · WIFI IDLE · CNT 0 · PKT 0 · SEEN 0", 9, false);
        radioLine.setTextColor(LCD_INK_DIM);
        lcdPanel.addView(radioLine);
        setLcdBacklight(false);

        // ---- pager log window ----
        TextView winLbl = stencil("▚ PAGER LOG — LAST 200", 9, ETCH);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
        wlp.topMargin = dp(12); wlp.bottomMargin = dp(4);
        body.addView(winLbl, wlp);
        logScroll = new ScrollView(this);
        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(LOG_BG);
        logBg.setCornerRadius(dp(6));
        logBg.setStroke(dp(1), Color.parseColor("#333A28"));
        logScroll.setBackground(logBg);
        logText = new TextView(this);
        logText.setTextSize(11);
        logText.setTextColor(LOG_TEXT);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setPadding(dp(10), dp(8), dp(10), dp(8));
        logScroll.addView(logText);
        body.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- chunky buttons ----
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(12);
        toggleBtn = new Button(this);
        toggleBtn.setText("▶ START WATCH");
        toggleBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        toggleBtn.setTextSize(14);
        toggleBtn.setTextColor(CREAM);
        toggleBtn.setBackground(chunky(OLIVE_HI, OLIVE_LO, Color.parseColor("#191D12")));
        toggleBtn.setOnClickListener(v -> toggleScan());
        btns.addView(toggleBtn, new LinearLayout.LayoutParams(0, dp(58), 1.5f));
        discreetBtn = new Button(this);
        discreetBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        discreetBtn.setTextSize(14);
        discreetBtn.setTextColor(CREAM);
        discreetBtn.setBackground(chunky(STEEL_HI, STEEL_LO, Color.parseColor("#101215")));
        discreetBtnLabel = null; // the Button carries its own label
        discreetBtn.setOnClickListener(v -> toggleDiscreet());
        LinearLayout.LayoutParams dbp = new LinearLayout.LayoutParams(0, dp(58), 1);
        dbp.leftMargin = dp(10);
        btns.addView(discreetBtn, dbp);
        body.addView(btns, blp);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(-1, -2);
        r2p.topMargin = dp(9);
        Button clearBtn = new Button(this);
        clearBtn.setText("CLEAR LOG");
        clearBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        clearBtn.setTextSize(10.5f);
        clearBtn.setTextColor(ETCH);
        clearBtn.setBackground(chunky(Color.parseColor("#2C3036"), Color.parseColor("#202329"), Color.parseColor("#101215")));
        clearBtn.setOnClickListener(v -> { ScannerService.clearLog(this); renderLog(); refreshState(); });
        row2.addView(clearBtn, new LinearLayout.LayoutParams(dp(110), dp(38)));
        Button supportBtn = new Button(this);
        supportBtn.setText("SUPPORT ▸");
        supportBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        supportBtn.setTextSize(10.5f);
        supportBtn.setTextColor(CREAM);
        supportBtn.setBackground(chunky(Color.parseColor("#2C3036"), Color.parseColor("#202329"), Color.parseColor("#101215")));
        supportBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://buy.stripe.com/14A7sLd267BE49780W3wQ3y")));
            } catch (Exception ignored) { }
        });
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(dp(110), dp(38));
        sbp.leftMargin = dp(8);
        row2.addView(supportBtn, sbp);
        TextView foot = stencil("ELEETH ▸ PERSONAL ISSUE ▸ NOT FOR RESALE", 8, Color.parseColor("#5C646B"));
        foot.setGravity(Gravity.RIGHT);
        row2.addView(foot, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(row2, r2p);

        setContentView(screenRoot);
        requestPermsIfNeeded();
        renderLog();
        refreshState();
        ui.post(tick);
        ui.post(glowTick);
    }

    /** LCD backlight: bright when live, dim in DISCREET (screen alerts dark). */
    private void setLcdBacklight(boolean dim) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            dim ? new int[]{LCD_DIM_HI, LCD_DIM_LO} : new int[]{LCD_HI, LCD_LO});
        g.setCornerRadius(dp(5));
        g.setStroke(dp(1), Color.parseColor("#2A3319"));
        lcdPanel.setBackground(g);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            clockView.setText(f.format(new Date()) + "Z");
            refreshState();
            ui.postDelayed(this, 500);
        }
    };

    // Tracker proximity pulse: the LCD bezel breathes purple at the foxhunt
    // period — the closer the tracker, the faster the pulse.
    private final Runnable glowTick = new Runnable() {
        @Override public void run() {
            ScannerService.ActiveContact act = ScannerService.activeContact();
            boolean on = false;
            if (act != null && act.cls == SigDb.CLS_TRACKER && !ScannerService.discreet
                    && SigDb.alertGate(SigDb.CLS_TRACKER, act.rssi)) {
                int period = SigDb.trackerPeriodMs(act.rssi);
                on = (System.currentTimeMillis() / Math.max(90, period / 2)) % 2 == 0;
            }
            bezDrawable.setStroke(dp(on ? 4 : 1), on ? CLS_TRACKER : Color.parseColor("#000000"));
            ui.postDelayed(this, 120);
        }
    };

    // ---------------- actions ----------------
    private void toggleScan() {
        if (ScannerService.running) {
            Intent i = new Intent(this, ScannerService.class).setAction("STOP");
            startService(i);
        } else {
            if (!hasPerms()) { requestPermsIfNeeded(); return; }
            Intent i = new Intent(this, ScannerService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        }
        ui.postDelayed(this::refreshState, 300);
    }

    private void toggleDiscreet() {
        ScannerService.setDiscreet(this, !ScannerService.discreet);
        refreshState();
    }

    private String[] neededPerms() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            p.add(Manifest.permission.POST_NOTIFICATIONS);
            p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        return p.toArray(new String[0]);
    }

    private boolean hasPerms() {
        for (String p : neededPerms())
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private void requestPermsIfNeeded() {
        List<String> missing = new ArrayList<>();
        for (String p : neededPerms())
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 7);
    }

    // ---------------- rendering ----------------
    private static int classColor(int cls) {
        return cls == SigDb.CLS_POLICE ? CLS_POLICE : cls == SigDb.CLS_SKIMMER ? CLS_SKIMMER : CLS_TRACKER;
    }

    private void renderLog() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (ScannerService.HitEvent e : ScannerService.LOG) {
            int start = sb.length();
            sb.append(e.line()).append("\n");
            String tag = "[" + SigDb.clsTag(e.cls) + "]";
            int ti = sb.toString().indexOf(tag, start);
            if (ti >= 0) sb.setSpan(new ForegroundColorSpan(classColor(e.cls)),
                ti, ti + tag.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (sb.length() == 0) sb.append("(no contacts yet — pager is listening)\n");
        logText.setText(sb);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void refreshState() {
        boolean run = ScannerService.running;
        boolean disc = ScannerService.discreet;
        toggleBtn.setText(run ? "■ STOP WATCH" : "▶ START WATCH");
        discreetBtn.setText(disc ? "DISCREET: ON" : "DISCREET");
        setLed(ledScanDot, run ? LED_GREEN : LED_OFF);
        setLed(ledDiscDot, disc ? LED_AMBER : LED_OFF);
        setLcdBacklight(disc);

        ScannerService.ActiveContact act = ScannerService.activeContact();
        int lit = 0;
        String dbm = "--- dBm";
        if (!run) {
            statusBig.setText("STANDBY");
            statusSub.setText("PRESS START WATCH TO BEGIN PATROL");
            classChip.setVisibility(View.GONE);
        } else if (disc) {
            statusBig.setText("DARK MODE");
            statusSub.setText(act != null ? "CONTACT LOGGED — DISPLAY DARK" : "SCANNING — DISPLAY DARK");
            classChip.setVisibility(View.GONE);
        } else if (act != null) {
            statusBig.setText("CONTACT");
            classChip.setVisibility(View.VISIBLE);
            classChip.setText(SigDb.clsTag(act.cls));
            GradientDrawable chip = new GradientDrawable();
            chip.setColor(classColor(act.cls));
            chip.setCornerRadius(dp(3));
            classChip.setBackground(chip);
            classChip.setTextColor(act.cls == SigDb.CLS_SKIMMER ? Color.parseColor("#1A1206") : Color.WHITE);
            statusSub.setText(act.why + "  ·  " + act.mac);
        } else {
            statusBig.setText("CLEAR");
            statusSub.setText("NO CONTACTS — AREA QUIET");
            classChip.setVisibility(View.GONE);
        }
        if (act != null) {
            int r = act.rssi;
            lit = r >= -45 ? 10 : r >= -55 ? 8 : r >= -65 ? 6 : r >= -75 ? 4 : r >= -85 ? 2 : 1;
            dbm = r + " dBm";
        }
        for (int i = 0; i < 10; i++)
            segs[i].setBackgroundColor(i < lit ? LCD_INK : LCD_SEG_OFF);
        meterDbm.setText(dbm);

        String up = "";
        if (run && ScannerService.startedAt > 0) {
            long s = (System.currentTimeMillis() - ScannerService.startedAt) / 1000;
            up = String.format(Locale.US, " · UP %02d:%02d", s / 60, s % 60);
        }
        radioLine.setText("BT " + ScannerService.btState + " · WIFI " + ScannerService.wifiState
            + " · CNT " + ScannerService.alertCount + " · PKT " + ScannerService.packetCount
            + " · SEEN " + ScannerService.seenCount + up);
    }

    private void setLed(TextView dot, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(1), Color.parseColor("#14161A"));
        dot.setBackground(g);
    }

    private void flashBezel() {
        if (flashing || ScannerService.discreet) return;
        flashing = true;
        final int[] seq = {Color.parseColor("#3A0D12"), Color.parseColor("#0D1440")};
        for (int i = 0; i < 8; i++) {
            final int c = seq[i % 2];
            ui.postDelayed(() -> screenRoot.setBackgroundColor(c), i * 110L);
        }
        ui.postDelayed(() -> {
            screenRoot.setBackground(grad(Color.parseColor("#31363C"), Color.parseColor("#1B1E23"), 0, 0, 0));
            flashing = false;
        }, 8 * 110L);
    }

    // ---------------- service listener ----------------
    @Override public void onHit(ScannerService.HitEvent e, boolean alerted) {
        ui.post(() -> {
            renderLog();
            refreshState();
            if (alerted && e.cls == SigDb.CLS_POLICE) flashBezel();
        });
    }

    @Override public void onState() { ui.post(this::refreshState); }

    @Override protected void onResume() {
        super.onResume();
        ScannerService.uiForeground = true;
        ScannerService.listener = this;
        renderLog();
        refreshState();
    }

    @Override protected void onPause() {
        ScannerService.uiForeground = false;
        if (ScannerService.listener == this) ScannerService.listener = null;
        super.onPause();
    }
}
