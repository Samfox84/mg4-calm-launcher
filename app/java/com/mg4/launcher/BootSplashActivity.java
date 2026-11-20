package com.mg4.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/**
 * Schermata dei primi secondi dopo l'accensione (avvio sicuro): finché l'utente Android non è sbloccato Android apre
 * solo home "directBootAware". Questa (priorità −1: sopra l'attesa di Android, sotto il launcher vero) non legge
 * nulla dalla memoria cifrata: aspetta lo sblocco e apre il launcher.
 */
public final class BootSplashActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean done;

    private final BroadcastReceiver unlocked = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            openHome();
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (isUnlocked()) openHome();
            else h.postDelayed(this, 500);
        }
    };

    private boolean isUnlocked() {
        try {
            return ((UserManager) getSystemService(Context.USER_SERVICE)).isUserUnlocked();
        } catch (Throwable e) {
            return true;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (isUnlocked()) {
            openHome();
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(Color.BLACK);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.calm_logo);
        int sz = Math.round(d * 150);
        col.addView(logo, new android.widget.LinearLayout.LayoutParams(sz, sz));
        logo.setAlpha(0f); logo.setScaleX(0.86f); logo.setScaleY(0.86f);
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(850)
            .setInterpolator(new android.view.animation.DecelerateInterpolator())
            .withEndAction(new Runnable() { @Override public void run() {
                logo.animate().scaleX(1.04f).scaleY(1.04f).setDuration(1400)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction(new Runnable() { @Override public void run() {
                        logo.animate().scaleX(1f).scaleY(1f).setDuration(1400)
                            .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()).start();
                    }}).start();
            }}).start();
        TextView tv = new TextView(this);
        tv.setText("Calm Launcher");
        tv.setTextColor(0x66FFFFFF);
        tv.setTextSize(22);
        tv.setGravity(Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams tp = new android.widget.LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = Math.round(d * 18);
        tv.setAlpha(0f);
        tv.animate().alpha(1f).setDuration(700).setStartDelay(350).start();
        col.addView(tv, tp);
        setContentView(col);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        try {
            registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        } catch (Throwable ignored) {
        }
        h.postDelayed(poll, 500);
    }

    private void openHome() {
        if (done) return;
        done = true;
        h.removeCallbacks(poll);
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setComponent(new ComponentName(this, MainActivity.class))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable ignored) {
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacks(poll);
        try {
            unregisterReceiver(unlocked);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onBackPressed() {
    }
}
