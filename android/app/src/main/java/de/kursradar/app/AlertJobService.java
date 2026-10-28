package de.kursradar.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Prüft im Hintergrund (etwa alle 15 Minuten, Android bestimmt den genauen Zeitpunkt) die Kursalarme
 * „Kurs steigt über / fällt unter“ der Watchlist und meldet ausgelöste Alarme per Benachrichtigung.
 * Signal-Alarme (Ausbruch, RSI, 200-Tage-Linie) prüft die App beim Aktualisieren.
 */
public class AlertJobService extends JobService {
    private static final int JOB_ID = 4711;

    static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(ctx, AlertJobService.class))
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try { check(); } catch (Exception ignored) { }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }

    private void check() throws Exception {
        File f = new File(getFilesDir(), "portfolio.json");
        if (!f.exists()) return;
        JSONObject root = new JSONObject(Bridge.readFile(f));
        JSONArray wl = root.optJSONArray("watchlist");
        if (wl == null) return;
        boolean changed = false;
        for (int i = 0; i < wl.length(); i++) {
            JSONObject w = wl.optJSONObject(i);
            JSONObject a = w == null ? null : w.optJSONObject("alert");
            if (a == null || !a.optBoolean("active", false) || a.has("hit")) continue;
            String type = a.optString("type");
            double v = a.optDouble("value", Double.NaN);
            if (!("above".equals(type) || "below".equals(type)) || Double.isNaN(v)) continue;
            String sym = w.optString("sym");
            double px;
            try {
                JSONObject j = new JSONObject(Bridge.get("https://query1.finance.yahoo.com/v8/finance/chart/"
                        + URLEncoder.encode(sym, "UTF-8") + "?range=1d&interval=1d"));
                px = j.getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta").optDouble("regularMarketPrice", Double.NaN);
            } catch (Exception e) { continue; }
            if (Double.isNaN(px)) continue;
            if (("above".equals(type) && px >= v) || ("below".equals(type) && px <= v)) {
                a.put("hit", System.currentTimeMillis() / 1000).put("hitPx", px).put("seen", false);
                changed = true;
                Notifier.show(this, sym.hashCode(), w.optString("name", sym) + ": Alarm ausgelöst",
                        ("above".equals(type) ? "Kurs über " : "Kurs unter ") + fmt(v) + " – aktuell " + fmt(px));
            }
        }
        if (changed) {
            File tmp = new File(getFilesDir(), "portfolio.json.job");
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(root.toString(1).getBytes(StandardCharsets.UTF_8)); }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.GERMANY, v >= 1000 ? "%,.0f" : v >= 1 ? "%,.2f" : "%,.4f", v);
    }
}
