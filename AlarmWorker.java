package de.foxtank.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

// Prueft im Hintergrund die Preise (Tankerkoenig) und meldet, wenn der Wunschpreis erreicht ist.
public class AlarmWorker extends Worker {

    private static final String BASE = "https://creativecommons.tankerkoenig.de/json/";

    public AlarmWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    private static String euro(double p) {
        return String.format(Locale.GERMANY, "%.3f €", p);
    }

    private static String fuelName(String f) {
        if ("e5".equals(f)) {
            return "Super E5";
        }
        if ("e10".equals(f)) {
            return "Super E10";
        }
        return "Diesel";
    }

    private static class Hit {
        String id;
        String name;
        double price;
        double dist;
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        SharedPreferences sp = c.getSharedPreferences(TankPlugin.PREFS, Context.MODE_PRIVATE);
        if (!sp.getBoolean("on", false)) {
            return Result.success();
        }
        try {
            JSONObject cfg = new JSONObject(sp.getString("config", "{}"));
            if (cfg.optBoolean("quiet", true)) {
                int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                if (h >= 22 || h < 6) {
                    return Result.success();
                }
            }
            String key = cfg.optString("key", "");
            String fuel = cfg.optString("fuel", "e10");
            double limit = cfg.optDouble("limit", 0);
            if (key.isEmpty() || limit <= 0) {
                return Result.success();
            }
            JSONObject names = cfg.optJSONObject("names");
            List<Hit> hits = new ArrayList<>();
            int checked = 0;

            // 1) Favoriten (hoechstens 10 pro Abruf)
            JSONArray ids = cfg.optJSONArray("ids");
            if (ids != null && ids.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < ids.length() && i < 10; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(ids.getString(i));
                }
                JSONObject r = new JSONObject(TankPlugin.httpGet(BASE + "prices.php?ids=" + sb + "&apikey=" + URLEncoder.encode(key, "UTF-8")));
                if (!r.optBoolean("ok", false)) {
                    throw new Exception(r.optString("message", "Fehler bei Tankerkoenig"));
                }
                JSONObject pr = r.optJSONObject("prices");
                if (pr != null) {
                    Iterator<String> it = pr.keys();
                    while (it.hasNext()) {
                        String id = it.next();
                        JSONObject s = pr.optJSONObject(id);
                        checked++;
                        if (s == null || !"open".equals(s.optString("status"))) {
                            continue;
                        }
                        Object v = s.opt(fuel);
                        if (!(v instanceof Number)) {
                            continue;
                        }
                        Hit h = new Hit();
                        h.id = id;
                        h.name = names != null ? names.optString(id, "Favorit") : "Favorit";
                        h.price = ((Number) v).doubleValue();
                        h.dist = -1;
                        hits.add(h);
                    }
                }
            }

            // 2) Umgebung eines festen Ortes
            if (cfg.optBoolean("area", false) && cfg.has("lat") && cfg.has("lng")) {
                if (ids != null && ids.length() > 0) {
                    Thread.sleep(1500);
                }
                String url = BASE + "list.php?lat=" + cfg.getDouble("lat") + "&lng=" + cfg.getDouble("lng")
                    + "&rad=" + Math.min(25, Math.max(1, cfg.optDouble("rad", 5))) + "&sort=price&type=" + fuel
                    + "&apikey=" + URLEncoder.encode(key, "UTF-8");
                JSONObject r = new JSONObject(TankPlugin.httpGet(url));
                if (!r.optBoolean("ok", false)) {
                    throw new Exception(r.optString("message", "Fehler bei Tankerkoenig"));
                }
                JSONArray st = r.optJSONArray("stations");
                if (st != null) {
                    for (int i = 0; i < st.length(); i++) {
                        JSONObject s = st.getJSONObject(i);
                        checked++;
                        if (!s.optBoolean("isOpen", false) || !(s.opt("price") instanceof Number)) {
                            continue;
                        }
                        Hit h = new Hit();
                        h.id = s.optString("id");
                        String brand = s.optString("brand", "").trim();
                        String nm = s.optString("name", "").trim();
                        h.name = (brand.isEmpty() ? nm : brand) + ", " + s.optString("street", "").trim();
                        h.price = s.getDouble("price");
                        h.dist = s.optDouble("dist", -1);
                        boolean dup = false;
                        for (Hit o : hits) {
                            if (o.id.equals(h.id)) {
                                dup = true;
                                break;
                            }
                        }
                        if (!dup) {
                            hits.add(h);
                        }
                    }
                }
            }

            // Treffer unter dem Wunschpreis, jeweils nur bei neuem Tiefstand melden
            JSONObject notified = new JSONObject(sp.getString("notified", "{}"));
            Hit best = null;
            int newCount = 0;
            JSONObject next = new JSONObject();
            for (Hit h : hits) {
                if (h.price > limit + 0.0001) {
                    continue;
                }
                double last = notified.optDouble(h.id, 99);
                next.put(h.id, Math.min(last, h.price));
                if (h.price < last - 0.0001) {
                    newCount++;
                    if (best == null || h.price < best.price) {
                        best = h;
                    }
                }
            }
            sp.edit().putString("notified", next.toString()).apply();

            String msg = checked + " Preise geprueft";
            if (best != null) {
                String t = fuelName(fuel) + " für " + euro(best.price);
                String text = best.name + (best.dist >= 0 ? String.format(Locale.GERMANY, " (%.1f km)", best.dist) : "")
                    + ". Dein Wunschpreis: " + euro(limit) + "."
                    + (newCount > 1 ? " Insgesamt " + newCount + " Tankstellen darunter." : "");
                TankPlugin.notify(c, 100, t, text);
                sp.edit().putLong("lastHit", System.currentTimeMillis()).putString("lastHitMsg", t + " – " + best.name).apply();
                msg += ", Alarm: " + euro(best.price);
            }
            sp.edit().putLong("lastRun", System.currentTimeMillis()).putString("lastMsg", msg).apply();
            return Result.success();
        } catch (Exception e) {
            sp.edit().putLong("lastRun", System.currentTimeMillis()).putString("lastMsg", "Fehler: " + e.getMessage()).apply();
            return Result.success();
        }
    }
}
