package com.mg4.launcher.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mg4.launcher.L;
import com.mg4.launcher.R;
import com.mg4.launcher.apps.AppCatalog;
import com.mg4.launcher.apps.IconStyler;
import com.mg4.launcher.svc.Updater;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Griglia di tutte le app: sezione "Auto" (icone disegnate) e "Installate da te" (icone ridisegnate in automatico). */
public final class AppsView extends LinearLayout {
    private static final int COLUMNS = 6;

    /** Gruppi della sezione Auto, nell'ordine di visualizzazione: chiave categoria + titolo tradotto. */
    private static final String[] CAT_KEYS = {"drive", "nav", "media", "system"};
    private static final int[] CAT_TITLES = {R.string.section_drive, R.string.section_nav, R.string.section_media, R.string.section_system};

    public interface Host {
        void closeApps();

        void openSettings();

        String navPackageHint();

        /** Contenitore per le finestre di conferma (1.0.4). */
        FrameLayout overlayRoot();

        /** Aggiornatore del launcher, per la barra "Aggiorna" in cima alla schermata App (1.0.5). */
        Updater updater();
    }

    private final Theme t;
    private final AppCatalog catalog;
    private final Host host;
    private final Prefs prefs;
    private final float k;   // scala della schermata App (dimensioneApp)
    private final LinearLayout content;
    private final TextView editBtn;
    private boolean editing;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView updChip;
    private Updater.Release pendingUpd;

    public AppsView(Context c, Theme t, AppCatalog catalog, Prefs prefs, Host host) {
        super(c);
        this.t = t;
        this.catalog = catalog;
        this.host = host;
        this.prefs = prefs;
        this.k = prefs.appsScaleFactor();
        setOrientation(VERTICAL);
        // 1.0.5: con mappa a sfondo, la pagina App è trasparente così la mappa fissa della root resta visibile dietro
        // (e durante lo swipe non si muove). L'elenco app sta su un velo scuro traslucido, per restare leggibile.
        boolean bgMap = prefs.mapBackground() && prefs.liveMap();
        setBackgroundColor(bgMap ? 0x00000000 : t.bg);
        int pad = dp(20);
        setPadding(pad, pad, pad, pad);

        LinearLayout head = Ui.row(c);
        head.setPadding(dp(8), dp(4), dp(8), dp(4));
        // si torna alla home scorrendo verso destra (o con il tasto Home)
        head.addView(Ui.text(c, L.s(R.string.apps_title), 32, t.text, Ui.MEDIUM), Ui.margins(Ui.lp(-2, -2), dp(12), 0, 0, 0));
        // 1.0.5: barra aggiornamento del launcher, subito a destra del titolo. Un tocco controlla, un tocco installa.
        updChip = Ui.text(c, L.s(R.string.btn_check), 17, t.sub, Ui.MEDIUM);
        updChip.setGravity(Gravity.CENTER);
        updChip.setPadding(dp(22), 0, dp(22), 0);
        updChip.setClickable(true);
        updChip.setBackground(Ui.pressable(c, t.panel, 26, t));
        updChip.setOnClickListener(v -> {
            Updater up = host.updater();
            if (up == null) return;
            if (pendingUpd != null && pendingUpd.newer && pendingUpd.apkUrl != null) {
                updChip.setText("Installing…");
                up.install(pendingUpd, prefs.githubToken());
            } else {
                checkUpdate();
            }
        });
        head.addView(updChip, Ui.margins(Ui.lp(-2, dp(52)), dp(16), 0, 0, 0));
        head.addView(Ui.spacer(c));
        editBtn = Ui.text(c, L.s(R.string.edit), 19, t.text, Ui.MEDIUM);
        editBtn.setGravity(Gravity.CENTER);
        editBtn.setPadding(dp(26), 0, dp(26), 0);
        editBtn.setClickable(true);
        editBtn.setContentDescription(L.s(R.string.edit_desc));
        editBtn.setOnClickListener(v -> setEditing(!editing));
        head.addView(editBtn, Ui.margins(Ui.lp(-2, dp(60)), 0, 0, dp(12), 0));
        head.addView(Ui.iconButton(c, t, R.drawable.ic_gear, t.text, t.panel, 60, 28, 18, L.s(R.string.settings_desc),
                v -> host.openSettings()));
        addView(head);

        ScrollView sv = new ScrollView(c);
        sv.setBackground(Ui.round(bgMap ? 0xCC121216 : t.panel, dp(24)));
        sv.setClipToOutline(true);
        sv.setVerticalScrollBarEnabled(false);
        content = Ui.column(c);
        content.setPadding(dp(32), dp(20), dp(32), dp(24));
        sv.addView(content, new FrameLayout.LayoutParams(-1, -2));
        addView(sv, Ui.margins(Ui.lpWeight(-1, 0, 1), 0, dp(16), 0, 0));
        setEditing(false);
        checkUpdate();   // 1.0.5: controlla all'apertura, così la barra mostra subito lo stato reale
    }

    /** Controlla gli aggiornamenti del launcher e aggiorna la barra in cima (senza toccare il resto della UI). */
    private void checkUpdate() {
        final Context c = getContext();
        Updater up = host.updater();
        if (up == null || updChip == null) return;
        updChip.setText("Checking…");
        updChip.setTextColor(t.sub);
        updChip.setBackground(Ui.pressable(c, t.panel, 26, t));
        up.checkSilent(prefs.updateRepo(), prefs.githubToken(), r -> main.post(() -> {
            pendingUpd = r;
            boolean newer = r != null && r.newer && r.apkUrl != null;
            if (newer) {
                updChip.setText("Install " + r.tag);
                updChip.setTextColor(t.onAccent);
                updChip.setBackground(Ui.pressable(c, t.accent, 26, t));
            } else {
                updChip.setText("Up to date");
                updChip.setTextColor(t.sub);
                updChip.setBackground(Ui.pressable(c, t.panel, 26, t));
            }
        }));
    }

    private void setEditing(boolean on) {
        editing = on;
        editBtn.setText(L.s(on ? R.string.done : R.string.edit));
        editBtn.setTextColor(on ? t.onAccent : t.text);
        editBtn.setBackground(Ui.pressable(getContext(), on ? t.accent : t.panel, 30, t));
        reload();
    }

    private static String key(AppCatalog.App a) {
        return a.packageName + "/" + (a.component != null ? a.component.getClassName() : "");
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }

    /** Ricarica l'elenco (chiamato anche quando si installa o si rimuove un'app). */
    public void reload() {
        Context c = getContext();
        content.removeAllViews();
        Set<String> hidden = prefs.hiddenApps();
        String note = editing ? L.s(R.string.edit_note) : null;
        // 0.9.5: anche le app dell'auto si possono nascondere in Modifica (stesso elenco delle app nascoste)
        List<AppCatalog.App> car = new ArrayList<>();
        List<AppCatalog.App> shortcuts = new ArrayList<>();
        for (AppCatalog.App a : catalog.carApps(host.navPackageHint())) {
            if (!(editing || !hidden.contains(key(a)))) continue;
            // 1.0.5: le scorciatoie Android (Impostazioni/File) vanno nella riga in cima, senza intestazione.
            if ("shortcut".equals(a.category)) shortcuts.add(a); else car.add(a);
        }
        // Scorciatoie: prima riga in assoluto, a portata di dito.
        boolean firstGroup = true;
        if (!shortcuts.isEmpty()) {
            addGrid(c, shortcuts, hidden);
            firstGroup = false;
        }
        // Auto raggruppata per categoria (Guida, Navigazione, Media, Sistema). Ogni gruppo ha la sua intestazione.
        for (int g = 0; g < CAT_KEYS.length; g++) {
            List<AppCatalog.App> group = new ArrayList<>();
            for (AppCatalog.App a : car) {
                if (CAT_KEYS[g].equals(a.category)) group.add(a);
            }
            if (group.isEmpty()) continue;
            content.addView(section(c, L.s(CAT_TITLES[g]), note),
                    Ui.margins(new LayoutParams(-1, -2), firstGroup ? 0 : dp(14), 0, 0, 0));
            addGrid(c, group, hidden);
            firstGroup = false;
        }
        // app dell'auto senza categoria nota (firmware diversi): finiscono in un gruppo "Auto" di scorta
        List<AppCatalog.App> other = new ArrayList<>();
        for (AppCatalog.App a : car) {
            if (a.category == null || !isKnownCat(a.category)) other.add(a);
        }
        if (!other.isEmpty()) {
            content.addView(section(c, L.s(R.string.section_car), note),
                    Ui.margins(new LayoutParams(-1, -2), firstGroup ? 0 : dp(14), 0, 0, 0));
            addGrid(c, other, hidden);
            firstGroup = false;
        }
        List<AppCatalog.App> all = catalog.userApps();
        List<AppCatalog.App> mine = new ArrayList<>();
        for (AppCatalog.App a : all) {
            if (editing || !hidden.contains(key(a))) mine.add(a);
        }
        content.addView(section(c, L.s(R.string.section_mine), note), Ui.margins(new LayoutParams(-1, -2), firstGroup ? 0 : dp(14), 0, 0, 0));
        if (!mine.isEmpty()) addGrid(c, mine, hidden);
    }

    private static boolean isKnownCat(String cat) {
        for (String k : CAT_KEYS) if (k.equals(cat)) return true;
        return false;
    }

    private View section(Context c, String title, String note) {
        LinearLayout r = Ui.row(c);
        r.setGravity(Gravity.BOTTOM);
        TextView tv = Ui.text(c, title, 15, t.sub, Ui.MEDIUM);
        tv.setLetterSpacing(0.07f);
        r.addView(tv);
        if (note != null) r.addView(Ui.text(c, note, 14, t.sub, Ui.REGULAR), Ui.margins(Ui.lp(-2, -2), dp(12), 0, 0, 0));
        r.setPadding(0, 0, 0, dp(6));
        return r;
    }

    /** editable: elenco delle app nascoste; in modalità Modifica ogni app (dell'auto o installata) si può nascondere. */
    private void addGrid(Context c, List<AppCatalog.App> apps, Set<String> editable) {
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLUMNS == 0) {
                row = Ui.row(c);
                content.addView(row, new LayoutParams(-1, dp(124 * k)));
            }
            row.addView(tile(c, apps.get(i), editable), Ui.lpWeight(0, -1, 1));
        }
        if (row != null) {
            for (int k = apps.size() % COLUMNS; k != 0 && k < COLUMNS; k++) row.addView(new View(c), Ui.lpWeight(0, -1, 1));
        }
    }

    /** App della telecamera 360: quella originale e MG4 360 (com.mg4.* con "360" nel nome o nell'etichetta). */
    private static boolean is360(AppCatalog.App app) {
        String p = app.packageName == null ? "" : app.packageName;
        if (Cam360.ORIG_PKG.equals(p)) return true;
        return p.startsWith("com.mg4.") && (p.contains("360") || String.valueOf(app.label).contains("360"));
    }

    private void askUninstall(AppCatalog.App app) {
        FrameLayout root = host.overlayRoot();
        if (root == null) return;
        String name = String.valueOf(app.label);
        Confirm.show(root, t, L.s(R.string.app_uninstall), L.f(R.string.app_uninstall_q, name), L.s(R.string.btn_cancel),
                L.s(R.string.app_uninstall), () -> com.mg4.launcher.svc.AppUninstall.uninstall(getContext(), app.packageName, ok -> {
                    android.widget.Toast.makeText(getContext(), ok ? L.f(R.string.app_uninstall_done, name)
                            : L.f(R.string.app_uninstall_failed, name), android.widget.Toast.LENGTH_LONG).show();
                    if (ok) reload();
                }));
    }

    private View tile(Context c, AppCatalog.App app, Set<String> editable) {
        LinearLayout col = Ui.column(c);
        col.setGravity(Gravity.CENTER);
        col.setBackground(Ui.pressable(c, 0, 20, t));
        col.setClickable(true);
        col.setContentDescription(app.label);
        boolean edit = editing && editable != null;
        boolean isHidden = edit && editable.contains(key(app));
        if (edit) {
            col.setOnClickListener(v -> {
                prefs.setAppHidden(key(app), !isHidden);
                reload();
            });
        } else if (editing) {
            col.setAlpha(0.3f);   // le app dell'auto restano sempre
            col.setClickable(false);
        } else {
            col.setOnClickListener(v -> {
                if (catalog.launch(app)) host.closeApps();
            });
            // 1.0.4 (issue #26): pressione lunga → Disinstalla, solo per le app installate dall'utente
            if (com.mg4.launcher.svc.AppUninstall.allowed(c, app.packageName)) {
                col.setOnLongClickListener(v -> {
                    askUninstall(app);
                    return true;
                });
            }
        }

        FrameLayout box = new FrameLayout(c);
        int boxBg = t.chip;
        int fg = t.text;
        box.setBackground(Ui.round(boxBg, dp(22 * k)));
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (is360(app)) {
            // 1.0.2 (issue #23): telecamera 360 = scritta "360°" grande, nello stile delle altre icone (Video USB resta play)
            TextView tx = Ui.text(c, "360°", 26 * k, fg, Ui.MEDIUM);
            tx.setGravity(Gravity.CENTER);
            tx.setLetterSpacing(-0.03f);
            box.addView(tx, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        } else if (app.iconRes != 0) {
            iv.setImageResource(app.iconRes);
            iv.setColorFilter(fg);
            box.addView(iv, new FrameLayout.LayoutParams(dp(34 * k), dp(34 * k), Gravity.CENTER));
        } else {
            box.addView(iv, new FrameLayout.LayoutParams(dp(40 * k), dp(40 * k), Gravity.CENTER));
            // icona ridisegnata in background (può richiedere qualche millisecondo per app)
            new Thread(() -> {
                Drawable d = catalog.originalIcon(app);
                Bitmap b = IconStyler.style(app.packageName + "/" + app.label, d, fg);
                boolean glyph = IconStyler.isGlyph(b);
                main.post(() -> {
                    iv.setImageBitmap(b);
                    if (!glyph) {
                        box.setBackground(null);
                        iv.setLayoutParams(new FrameLayout.LayoutParams(dp(72 * k), dp(72 * k), Gravity.CENTER));
                    }
                });
            }, "mg4-icon").start();
        }
        if (edit) {
            // segno in alto a destra: ✓ visibile, × nascosta
            FrameLayout badge = new FrameLayout(c);
            badge.setBackground(Ui.round(isHidden ? t.track : t.accent, dp(14)));
            ImageView bi = Ui.icon(c, isHidden ? R.drawable.ic_close : R.drawable.ic_check, isHidden ? t.text : t.onAccent, 18);
            badge.addView(bi, new FrameLayout.LayoutParams(dp(18 * k), dp(18 * k), Gravity.CENTER));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(28 * k), dp(28 * k), Gravity.TOP | Gravity.END);
            FrameLayout wrap = new FrameLayout(c);
            wrap.addView(box, new FrameLayout.LayoutParams(dp(72 * k), dp(72 * k), Gravity.CENTER));
            wrap.addView(badge, blp);
            if (isHidden) box.setAlpha(0.35f);
            col.addView(wrap, new LayoutParams(dp(88 * k), dp(80 * k)));
        } else {
            col.addView(box, new LayoutParams(dp(72 * k), dp(72 * k)));
        }
        TextView label = Ui.oneLine(Ui.text(c, app.label, 16 * k, isHidden ? t.sub : t.text, Ui.MEDIUM));
        label.setGravity(Gravity.CENTER);
        col.addView(label, Ui.margins(new LayoutParams(-1, -2), dp(4), dp(10), dp(4), 0));
        return col;
    }
}
