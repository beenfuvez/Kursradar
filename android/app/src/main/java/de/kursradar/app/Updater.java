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
    private static final long INTERVAL = 10 * 60 * 1000L; // höchstens alle 10 Minuten automatisch prüfen (nach Erfolg)
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

    /** Datei aus dem Repository laden: erst raw.githubusercontent.com, bei Fehler über die GitHub-API. */
    private String fetch(String path) throws IOException {
        try {
            return Bridge.get(raw(path), 45_000);
        } catch (IOException first) {
            try {
                return Bridge.get("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/contents/" + path + "?ref=main", 45_000,
                        "application/vnd.github.raw");
            } catch (IOException second) {
                throw new IOException(first.getMessage() + " / API: " + second.getMessage());
            }
        }
    }

    /** Status für die Anzeige beim Antippen der Versionsnummer. */
    String status() {
        long t = prefs.getLong("lastCheck", 0);
        String when = t == 0 ? "noch nie" : new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.GERMANY).format(new java.util.Date(t));
        String err = prefs.getString("lastError", "");
        return "App: " + BuildConfig.VERSION_NAME + "\nOberfläche: " + currentWebVersion()
                + "\nZuletzt erfolgreich geprüft: " + when
                + (prefs.getString("serverWeb", "").isEmpty() ? "" : "\nAuf dem Server: Oberfläche " + prefs.getString("serverWeb", "") + ", App " + prefs.getString("serverApp", ""))
                + (err.isEmpty() ? "" : "\nLetzter Fehler: " + err);
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
                JSONObject info = new JSONObject(fetch("update/version.json"));
                prefs.edit().putString("serverWeb", info.optString("web")).putString("serverApp", info.optString("app")).apply();

                // 1) Oberfläche
                if (cmp(info.optString("web"), currentWebVersion()) > 0) {
                    String html = fetch("kursradar/index.html");
                    Matcher m = VER.matcher(html);
                    boolean ok = html.length() > 20_000 && (html.contains("<title>Owlgo") || html.contains("<title>Kursradar")) && m.find();
                    if (!ok) throw new IOException("Oberfläche unvollständig geladen (" + html.length() + " Zeichen)");
                    if (cmp(m.group(1), currentWebVersion()) > 0) {
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
                prefs.edit().putLong("lastCheck", now).putString("lastError", "").apply(); // erst nach Erfolg: sonst sofort neuer Versuch
                if (manual && !found) act.runOnUiThread(() -> showStatus("Owlgo ist aktuell."));
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                prefs.edit().putString("lastError", msg).apply();
                if (manual) act.runOnUiThread(() -> showStatus("Update-Prüfung fehlgeschlagen."));
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

    private void showStatus(String title) {
        if (act.isFinishing()) return;
        new AlertDialog.Builder(act).setTitle(title).setMessage(status())
                .setPositiveButton("OK", null)
                .setNeutralButton("APK im Browser", (d, w) -> {
                    try { act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/" + BuildConfig.UPDATE_REPO + "/releases/latest"))); }
                    catch (Exception ignored) { }
                }).show();
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
