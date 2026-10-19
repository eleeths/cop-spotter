package com.eleeth.copspotterapp;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen class banner — the phone's version of the hardware's alert LED.
 *   POLICE  — red/blue alternating strobe at the firmware's 110 ms cadence (~4 s)
 *   SKIMMER — amber triple-blink pattern (firmware: 3 x 180 ms blinks) (~3.6 s)
 *   TRACKER — purple foxhunt pulse; period scales with the hit's RSSI (~6 s)
 * Tap anywhere to dismiss; auto-dismisses.
 */
public class StrobeActivity extends Activity {

    private static final int POLICE_RED = Color.parseColor("#D21F2E");
    private static final int POLICE_BLUE = Color.parseColor("#2450E0");
    private static final int AMBER = Color.parseColor("#E8A33D");
    private static final int AMBER_DARK = Color.parseColor("#4A3208");
    private static final int PURPLE = Color.parseColor("#6A38C2");
    private static final int PURPLE_DARK = Color.parseColor("#241040");

    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout box;
    private boolean phase = false, done = false;
    private long startedAt;
    private int cls = SigDb.CLS_POLICE;
    private int rssi = -99;
    private int flipCount = 0;

    private long durationMs() { return cls == SigDb.CLS_TRACKER ? 6000 : cls == SigDb.CLS_SKIMMER ? 3600 : 4000; }

    private long nextDelay() {
        if (cls == SigDb.CLS_POLICE) return 110;
        if (cls == SigDb.CLS_SKIMMER) {
            // 3 blinks of 180 on / 180 off, then a 540 ms pause — firmware rhythm
            int pos = flipCount % 7;
            return pos == 6 ? 540 : 180;
        }
        // tracker: half-period flips so the visible pulse matches the foxhunt rate
        return Math.max(90, SigDb.trackerPeriodMs(rssi) / 2);
    }

    private void paint() {
        if (cls == SigDb.CLS_POLICE) {
            box.setBackgroundColor(phase ? POLICE_RED : POLICE_BLUE);
        } else if (cls == SigDb.CLS_SKIMMER) {
            int pos = flipCount % 7;
            box.setBackgroundColor(phase && pos < 6 ? AMBER : AMBER_DARK);
        } else {
            box.setBackgroundColor(phase ? PURPLE : PURPLE_DARK);
        }
    }

    private final Runnable flip = new Runnable() {
        @Override public void run() {
            if (done) return;
            phase = !phase;
            flipCount++;
            paint();
            if (System.currentTimeMillis() - startedAt > durationMs()) { finish(); return; }
            h.postDelayed(this, nextDelay());
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        String why = getIntent().getStringExtra("why");
        String mac = getIntent().getStringExtra("mac");
        rssi = getIntent().getIntExtra("rssi", -99);
        cls = getIntent().getIntExtra("cls", SigDb.CLS_POLICE);
        if (why == null) why = "UNKNOWN CONTACT";
        if (mac == null) mac = "";

        String header = cls == SigDb.CLS_POLICE ? "⚠ POLICE GEAR ⚠"
                      : cls == SigDb.CLS_SKIMMER ? "⚠ SKIMMER CLOSE ⚠"
                      : "◉ TRACKER CLOSE ◉";
        int fg = cls == SigDb.CLS_SKIMMER ? Color.parseColor("#1A1206") : Color.WHITE;

        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        TextView plate = new TextView(this);
        plate.setText("COP SPOTTER // " + SigDb.clsTag(cls) + " CLASS");
        plate.setTextColor(fg);
        plate.setTextSize(13);
        plate.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        plate.setGravity(Gravity.CENTER);
        box.addView(plate);

        TextView big = new TextView(this);
        big.setText(header);
        big.setTextColor(fg);
        big.setTextSize(36);
        big.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        big.setGravity(Gravity.CENTER);
        big.setPadding(0, 18, 0, 0);
        box.addView(big);

        TextView sub = new TextView(this);
        String extra = cls == SigDb.CLS_TRACKER ? "\nFOXHUNT — PULSE QUICKENS AS YOU NEAR IT" : "";
        sub.setText(why + "\n" + mac + "  ·  " + rssi + " dBm" + extra);
        sub.setTextColor(fg);
        sub.setTextSize(16);
        sub.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(16, 24, 16, 0);
        box.addView(sub);

        paint();
        box.setOnClickListener(v -> finish());
        setContentView(box);
        startedAt = System.currentTimeMillis();
        h.post(flip);
    }

    @Override protected void onDestroy() {
        done = true;
        h.removeCallbacks(flip);
        super.onDestroy();
    }
}
