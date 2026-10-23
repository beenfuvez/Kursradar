package de.kursradar.app;

import android.net.Uri;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ersatz für server.py direkt in der App: beantwortet dieselben /api/-Pfade
 * (Kurse, Suche, Marktkapitalisierung von Yahoo Finance, Depot-Speicher).
 */
public class Bridge {
    static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    private final MainActivity act;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private final Map<String, Object[]> cache = new ConcurrentHashMap<>();
    private volatile String crumb;
    private volatile long crumbTime;

    static {
        CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
    }

    Bridge(MainActivity act) { this.act = act; }

    void shutdown() { pool.shutdownNow(); }

    /** Asynchron: Ergebnis kommt über window.__krResolve(id, status, text) zurück. */
    @JavascriptInterface
    public void request(final int id, final String method, final String path, final String body) {
        pool.execute(() -> {
            int status;
            String out;
            try {
                String[] r = handle(method, path, body);
                status = Integer.parseInt(r[0]);
                out = r[1];
            } catch (Exception e) {
                status = 502;
                out = error(e.getMessage());
            }
            final String js = "window.__krResolve(" + id + "," + status + "," + JSONObject.quote(out) + ")";
            act.runOnUiThread(() -> { if (act.web != null) act.web.evaluateJavascript(js, null); });
        });
    }

    /** Versionen von App und Oberfläche (für die Anzeige). */
    @JavascriptInterface
    public String info() {
        try {
            return new JSONObject()
                    .put("app", BuildConfig.VERSION_NAME)
                    .put("web", act.updater.currentWebVersion())
                    .put("updates", !BuildConfig.UPDATE_REPO.isEmpty())
                    .toString();
        } catch (Exception e) { return "{}"; }
    }

    /** Depot-Export: öffnet den Android-Dialog zum Speichern der Datei. */
    @JavascriptInterface
    public void saveFile(final String name, final String content) { act.runOnUiThread(() -> act.startSave(name, content)); }

    /** Manuelle Update-Prüfung aus der Oberfläche. */
    @JavascriptInterface
    public void checkUpdate() { act.runOnUiThread(() -> act.updater.check(true)); }

    private String[] handle(String method, String path, String body) throws Exception {
        Uri u = Uri.parse("http://x" + path);
        String p = u.getPath() == null ? "" : u.getPath();
        switch (p) {
            case "/api/ping":
                return ok("{\"ok\":true,\"source\":\"Yahoo Finance\",\"native\":true}");
            case "/api/chart": {
                String sym = u.getQueryParameter("symbol");
                if (sym == null || sym.isEmpty()) return new String[]{"400", error("symbol fehlt")};
                String range = def(u.getQueryParameter("range"), "2y"), iv = def(u.getQueryParameter("interval"), "1d");
                String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + enc(sym)
                        + "?range=" + enc(range) + "&interval=" + enc(iv) + "&events=div&includePrePost=false";
                return ok(cached("c|" + sym + "|" + range + "|" + iv, 20_000, url));
            }
            case "/api/quote": {
                String syms = def(u.getQueryParameter("symbols"), "");
                String c = crumb();
                return ok(cached("q|" + syms, 60_000,
                        "https://query2.finance.yahoo.com/v7/finance/quote?symbols=" + enc(syms) + "&crumb=" + enc(c)));
            }
            case "/api/search": {
                String q = def(u.getQueryParameter("q"), "").trim();
                if (q.isEmpty()) return ok("{\"quotes\":[]}");
                if (q.length() > 60) q = q.substring(0, 60);
                return ok(cached("s|" + q, 300_000, "https://query2.finance.yahoo.com/v1/finance/search?q=" + enc(q)
                        + "&quotesCount=10&newsCount=0&lang=de-DE&region=DE"));
            }
            case "/api/summary":
            case "/api/timeseries": {
                String sym = def(u.getQueryParameter("symbol"), ""), lst = def(u.getQueryParameter("modules"), def(u.getQueryParameter("types"), ""));
                if (!sym.matches("[A-Za-z0-9.\\-^=]{1,24}") || !lst.matches("[A-Za-z,]{1,1500}")) return new String[]{"400", error("ungültige Anfrage")};
                if (p.equals("/api/summary")) {
                    try {
                        return ok(cached("sum|" + sym + "|" + lst, 6 * 3600_000L, "https://query2.finance.yahoo.com/v10/finance/quoteSummary/" + enc(sym)
                                + "?modules=" + enc(lst) + "&crumb=" + enc(crumb())));
                    } catch (IOException e) {
                        if (!String.valueOf(e.getMessage()).contains("401")) throw e;
                        crumb = null; // Crumb abgelaufen → einmal neu versuchen
                        return ok(cached("sum|" + sym + "|" + lst, 6 * 3600_000L, "https://query2.finance.yahoo.com/v10/finance/quoteSummary/" + enc(sym)
                                + "?modules=" + enc(lst) + "&crumb=" + enc(crumb())));
                    }
                }
                return ok(cached("ts|" + sym + "|" + lst, 6 * 3600_000L, "https://query2.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/"
                        + enc(sym) + "?symbol=" + enc(sym) + "&type=" + enc(lst) + "&period1=1420070400&period2=" + (System.currentTimeMillis() / 1000)));
            }
            case "/api/news": {
                String q = def(u.getQueryParameter("q"), "").trim();
                if (q.isEmpty()) return ok("{\"news\":[]}");
                if (q.length() > 60) q = q.substring(0, 60);
                JSONObject j = new JSONObject(cached("s|n|" + q, 300_000, "https://query2.finance.yahoo.com/v1/finance/search?q=" + enc(q)
                        + "&quotesCount=0&newsCount=8&lang=de-DE&region=DE"));
                JSONArray in = j.optJSONArray("news"), out = new JSONArray();
                if (in != null) for (int i = 0; i < in.length(); i++) {
                    JSONObject n = in.optJSONObject(i);
                    if (n == null || n.optString("title").isEmpty()) continue;
                    out.put(new JSONObject().put("title", n.optString("title")).put("publisher", n.optString("publisher"))
                            .put("link", n.optString("link")).put("providerPublishTime", n.optLong("providerPublishTime"))
                            .put("relatedTickers", n.optJSONArray("relatedTickers") == null ? new JSONArray() : n.optJSONArray("relatedTickers")));
                }
                return ok(new JSONObject().put("news", out).toString());
            }
            case "/api/feed": {
                String id = def(u.getQueryParameter("id"), "");
                String url = "wirtschaft".equals(id) ? "https://www.tagesschau.de/wirtschaft/index~rss2.xml"
                        : "finanzen".equals(id) ? "https://www.tagesschau.de/wirtschaft/finanzen/index~rss2.xml" : null;
                if (url == null) return new String[]{"400", error("unbekannter Feed")};
                return ok(new JSONObject().put("xml", cached("f|" + id, 600_000, url)).toString());
            }
            case "/api/portfolio":
                if ("PUT".equals(method)) return savePortfolio(body);
                return ok(readPortfolio());
            default:
                return new String[]{"404", error("unbekannt")};
        }
    }

    // ---------- Depot im App-Speicher ----------
    private File pfFile() { return new File(act.getFilesDir(), "portfolio.json"); }

    private String readPortfolio() throws Exception {
        File f = pfFile();
        JSONObject j = f.exists() ? new JSONObject(readFile(f)) : new JSONObject().put("positions", new JSONArray());
        return j.put("path", "App-Speicher").toString();
    }

    private synchronized String[] savePortfolio(String body) throws Exception {
        JSONObject in = new JSONObject(body);
        JSONArray pos = in.optJSONArray("positions");
        if (pos == null) return new String[]{"400", error("positions fehlt")};
        File f = pfFile();
        if (f.exists()) //noinspection ResultOfMethodCallIgnored
            f.renameTo(new File(act.getFilesDir(), "portfolio.bak.json"));
        File tmp = new File(act.getFilesDir(), "portfolio.json.tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            JSONArray jr = in.optJSONArray("journal");
            o.write(new JSONObject().put("version", 1).put("positions", pos).put("journal", jr == null ? new JSONArray() : jr)
                    .toString(1).getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("Speichern fehlgeschlagen");
        return ok("{\"ok\":true,\"path\":\"App-Speicher\"}");
    }

    // ---------- Yahoo Finance ----------
    private String crumb() throws IOException {
        if (crumb != null && System.currentTimeMillis() - crumbTime < 3_600_000) return crumb;
        try { get("https://fc.yahoo.com"); } catch (IOException ignored) { } // setzt nur das Cookie
        String c = get("https://query2.finance.yahoo.com/v1/test/getcrumb").trim();
        if (c.isEmpty() || c.contains("<")) throw new IOException("Kein Crumb erhalten");
        crumb = c;
        crumbTime = System.currentTimeMillis();
        return c;
    }

    private String cached(String key, long ttl, String url) throws IOException {
        Object[] hit = cache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && now - (Long) hit[0] < ttl) return (String) hit[1];
        String v = get(url);
        cache.put(key, new Object[]{now, v});
        return v;
    }

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "*/*");
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) { return new String(readAll(in), StandardCharsets.UTF_8); }
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static String readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { return new String(readAll(in), StandardCharsets.UTF_8); }
    }

    private static String[] ok(String s) { return new String[]{"200", s}; }

    private static String error(String msg) { return "{\"error\":" + JSONObject.quote(msg == null ? "Fehler" : msg) + "}"; }

    private static String def(String v, String d) { return v == null || v.isEmpty() ? d : v; }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
