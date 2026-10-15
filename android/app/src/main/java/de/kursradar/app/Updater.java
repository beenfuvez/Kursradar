package de.kursradar.app;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automatische Updates über GitHub:
 *  - Oberfläche (index.html): wird still heruntergeladen und sofort genutzt.
 *  - App selbst (APK): Hinweis mit „Installieren“, Installation über den Android-Paketinstaller.
 */
public class Updater {
    private static final long INTERVAL = 60 * 60 * 1000L; // höchstens stündlich automatisch prüfen
    private static final Pattern VER = Pattern.compile("id=\"appVer\">v([0-9.]+)<");

    private final MainActivity act;
    private final SharedPreferences prefs;
    private final File webFile;
    private volatile boolean running;
    private String pendingApk, pendingVersion;

    Updater(MainActivity act) {
        this.act = act;
        this.prefs = act.getSharedPreferences("updates", 0);
        this.webFile = new File(act.getFilesDir(), "web/index.html");
    }

    private String raw(String path) {
        return "https://raw.githubusercontent.com/" + BuildConfig.UPDATE_REPO + "/main/" + path + "?t=" + System.currentTimeMillis();
    }

    /** Version der aktuell genutzten Oberfläche. */
    String currentWebVersion() {
        String dl = prefs.getString("webVersion", "");
        return webFile.exists() && cmp(dl, BuildConfig.WEB_VERSION) > 0 ? dl : BuildConfig.WEB_VERSION;
    }

    String currentHtml() {
        try {
            if (webFile.exists() && cmp(prefs.getString("webVersion", ""), BuildConfig.WEB_VERSION) > 0)
                return Bridge.readFile(webFile);
            try (InputStream in = act.getAssets().open("web/index.html")) {
                return new String(Bridge.readAll(in), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return "<p style=\"font-family:sans-serif;padding:24px\">Owlgo konnte nicht geladen werden: " + e.getMessage() + "</p>";
        }
    }

    void onResume() {
        // Nach der Freigabe „Unbekannte Apps installieren“ das angefangene Update fortsetzen
        if (pendingApk != null && canInstall()) {
            String url = pendingApk, v = pendingVersion;
            pendingApk = null;
            install(url, v);
        } else {
            check(false);
        }
    }

    void check(boolean manual) {
        if (BuildConfig.UPDATE_REPO.isEmpty()) {
            if (manual) toast("Diese App-Version hat keine Update-Quelle.");
            return;
        }
        long now = System.currentTimeMillis();
        if (running || (!manual && now - prefs.getLong("lastCheck", 0) < INTERVAL)) return;
        running = true;
        if (manual) toast("Suche nach Updates …");
        new Thread(() -> {
            boolean found = false;
            try {
                JSONObject info = new JSONObject(Bridge.get(raw("update/version.json")));
                prefs.edit().putLong("lastCheck", now).apply();

                // 1) Oberfläche
                if (cmp(info.optString("web"), currentWebVersion()) > 0) {
                    String html = Bridge.get(raw("kursradar/index.html"));
                    Matcher m = VER.matcher(html);
                    if (html.length() > 20_000 && (html.contains("<title>Owlgo</title>") || html.contains("<title>Kursradar</title>")) && m.find()
                            && cmp(m.group(1), currentWebVersion()) > 0) {
                        File dir = webFile.getParentFile();
                        if (dir != null && !dir.exists()) //noinspection ResultOfMethodCallIgnored
                            dir.mkdirs();
                        try (OutputStream o = new FileOutputStream(webFile)) { o.write(html.getBytes(StandardCharsets.UTF_8)); }
                        prefs.edit().putString("webVersion", m.group(1)).apply();
                        found = true;
                        final String v = m.group(1);
                        act.runOnUiThread(() -> { toast("Owlgo aktualisiert auf v" + v); act.loadUi(); });
                    }
                }
                // 2) App selbst
                int code = info.optInt("appCode", 0);
                if (code > BuildConfig.VERSION_CODE) {
                    found = true;
                    final String v = info.optString("app"), url = info.optString("apk");
                    act.runOnUiThread(() -> askInstall(v, url));
                }
                if (manual && !found) act.runOnUiThread(() -> toast("Owlgo ist aktuell (v" + currentWebVersion() + ")."));
            } catch (Exception e) {
                if (manual) act.runOnUiThread(() -> toast("Update-Prüfung fehlgeschlagen: " + e.getMessage()));
            } finally {
                running = false;
            }
        }).start();
    }

    private void askInstall(String version, String url) {
        if (act.isFinishing()) return;
        new AlertDialog.Builder(act)
                .setTitle("App-Update " + version)
                .setMessage("Eine neue Version der Owlgo-App ist verfügbar. Jetzt herunterladen und installieren? Dein Depot bleibt erhalten.")
                .setPositiveButton("Installieren", (d, w) -> install(url, version))
                .setNegativeButton("Später", null)
                .show();
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || act.getPackageManager().canRequestPackageInstalls();
    }

    private void install(String url, String version) {
        if (!canInstall()) {
            pendingApk = url;
            pendingVersion = version;
            toast("Bitte „Apps aus dieser Quelle zulassen“ aktivieren und zurückkehren.");
            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName())));
            return;
        }
        toast("Update wird heruntergeladen …");
        new Thread(() -> {
            try {
                PackageInstaller pi = act.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(act.getPackageName());
                int sid = pi.createSession(params);
                try (PackageInstaller.Session s = pi.openSession(sid)) {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestProperty("User-Agent", Bridge.UA);
                    c.setConnectTimeout(15_000);
                    c.setReadTimeout(60_000);
                    if (c.getResponseCode() >= 400) throw new IOException("HTTP " + c.getResponseCode());
                    try (InputStream in = c.getInputStream(); OutputStream out = s.openWrite("kursradar.apk", 0, -1)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        s.fsync(out);
                    } finally {
                        c.disconnect();
                    }
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent cb = PendingIntent.getBroadcast(act, sid, new Intent(act, InstallReceiver.class), flags);
                    s.commit(cb.getIntentSender());
                }
            } catch (Exception e) {
                act.runOnUiThread(() -> toast("Update fehlgeschlagen: " + e.getMessage()));
            }
        }).start();
    }

    private void toast(String msg) { Toast.makeText(act, msg, Toast.LENGTH_LONG).show(); }

    /** Vergleicht Versionen wie 3.2 numerisch (Teil für Teil, 3.10 > 3.9). */
    static int cmp(String a, String b) {
        String[] x = a == null ? new String[0] : a.split("\\."), y = b == null ? new String[0] : b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
