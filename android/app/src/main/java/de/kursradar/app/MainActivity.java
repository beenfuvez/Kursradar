package de.kursradar.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Owlgo als Android-App (technischer Paketname bleibt de.kursradar.app, damit Updates weiter passen): zeigt die Web-Oberfläche und liefert ihr Live-Kurse über {@link Bridge}. */
public class MainActivity extends Activity {
    /** Feste Herkunft, damit Browser-Speicher (Einstellungen) über Updates hinweg erhalten bleibt. */
    static final String BASE_URL = "https://app.kursradar.local/";
    private static final int REQ_FILE = 41;
    private static final int REQ_SAVE = 42;
    private String pendingSave;

    WebView web;
    private Bridge bridge;
    Updater updater;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);

        updater = new Updater(this);
        bridge = new Bridge(this);
        web.addJavascriptInterface(bridge, "KursradarNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (BASE_URL.startsWith(u.getScheme() + "://" + u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (ActivityNotFoundException ignored) { }
                return true; // externe Links im Browser öffnen
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Depot-Datei wählen"), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        loadUi();
        updater.check(false);
        try { AlertJobService.schedule(this); } catch (Exception ignored) { } // Kursalarme im Hintergrund
    }

    /** Lädt die aktuellste verfügbare Oberfläche (heruntergeladenes Update oder mitgelieferte Version). */
    void loadUi() {
        String html = "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">"
                + "</head><body>" + updater.currentHtml() + "</body></html>";
        web.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null);
    }

    void askNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 43);
    }

    /** Export: Android-Dialog „Speichern unter“ (Downloads, Google Drive …), danach Inhalt schreiben. */
    void startSave(String name, String content) {
        pendingSave = content;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(i, REQ_SAVE);
        } catch (ActivityNotFoundException e) {
            pendingSave = null;
            Toast.makeText(this, "Speichern wird auf diesem Gerät nicht unterstützt.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_SAVE) {
            String content = pendingSave;
            pendingSave = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && content != null) {
                try (OutputStream o = getContentResolver().openOutputStream(data.getData())) {
                    if (o == null) throw new java.io.IOException("Datei nicht beschreibbar");
                    o.write(content.getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "Depot exportiert", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Export fehlgeschlagen: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        updater.onResume();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Zuerst die Oberfläche fragen (Formular schließen, zurück zu „Märkte“), sonst App schließen
        web.evaluateJavascript("(window.krBack && window.krBack()) ? 'y' : 'n'", v -> {
            if (v == null || !v.contains("y")) finish();
        });
    }

    @Override
    protected void onDestroy() {
        bridge.shutdown();
        web.destroy();
        super.onDestroy();
    }
}
