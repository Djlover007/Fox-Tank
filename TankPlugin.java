package de.foxtank.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

// Fox Tank: Abrufe ohne CORS, Standort, Preis-Alarm im Hintergrund.
@CapacitorPlugin(name = "Tank")
public class TankPlugin extends Plugin {

    static final String PREFS = "fox_tank";
    static final String CHANNEL = "preis_alarm";
    static final String WORK = "fox_tank_alarm";

    // ---------- Netz ----------
    static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "FoxTank/1.0 (Android; github.com/Djlover007/Fox-Tank)");
            c.setRequestProperty("Accept-Language", "de-DE,de;q=0.9");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                }
                in.close();
            }
            String body = bo.toString("UTF-8");
            if (code >= 400) {
                throw new Exception("HTTP " + code + (body.length() > 0 ? ": " + body.substring(0, Math.min(160, body.length())) : ""));
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    @PluginMethod
    public void get(final PluginCall call) {
        final String url = call.getString("url", "");
        if (url == null || !url.startsWith("https://")) {
            call.reject("Ungueltige Adresse");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSObject r = new JSObject();
                    r.put("body", httpGet(url));
                    call.resolve(r);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                }
            }
        }).start();
    }

    // ---------- Standort ----------
    private boolean hasLoc() {
        Context c = getContext();
        return ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @PluginMethod
    public void locate(final PluginCall call) {
        if (!hasLoc()) {
            if (getActivity() != null) {
                getActivity().requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 4712);
            }
            call.reject("permission");
            return;
        }
        final LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            call.reject("Kein Standortdienst");
            return;
        }
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
        } catch (SecurityException e) {
            // ignorieren
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < 10 * 60 * 1000) {
            resolveLoc(call, best);
            return;
        }
        final Location fallback = best;
        final boolean[] done = {false};
        final Handler h = new Handler(Looper.getMainLooper());
        final LocationListener ls = new LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
                if (done[0]) {
                    return;
                }
                done[0] = true;
                try {
                    lm.removeUpdates(this);
                } catch (Exception e) {
                    // ignorieren
                }
                resolveLoc(call, l);
            }

            @Override
            public void onStatusChanged(String p, int s, Bundle b) {
            }

            @Override
            public void onProviderEnabled(String p) {
            }

            @Override
            public void onProviderDisabled(String p) {
            }
        };
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean any = false;
                    if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, ls, Looper.getMainLooper());
                        any = true;
                    }
                    if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, ls, Looper.getMainLooper());
                        any = true;
                    }
                    if (!any) {
                        done[0] = true;
                        if (fallback != null) {
                            resolveLoc(call, fallback);
                        } else {
                            call.reject("Standort ist am Handy ausgeschaltet");
                        }
                    }
                } catch (SecurityException e) {
                    done[0] = true;
                    call.reject("permission");
                }
            }
        });
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (done[0]) {
                    return;
                }
                done[0] = true;
                try {
                    lm.removeUpdates(ls);
                } catch (Exception e) {
                    // ignorieren
                }
                if (fallback != null) {
                    resolveLoc(call, fallback);
                } else {
                    call.reject("Kein Standort gefunden");
                }
            }
        }, 20000);
    }

    private void resolveLoc(PluginCall call, Location l) {
        JSObject r = new JSObject();
        r.put("lat", l.getLatitude());
        r.put("lng", l.getLongitude());
        r.put("acc", l.getAccuracy());
        call.resolve(r);
    }

    // ---------- Benachrichtigungen ----------
    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Preis-Alarm", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Meldet, wenn Sprit unter deinen Wunschpreis faellt");
                nm.createNotificationChannel(ch);
            }
        }
    }

    static boolean canNotify(Context c) {
        if (Build.VERSION.SDK_INT >= 33
            && ContextCompat.checkSelfPermission(c, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return NotificationManagerCompat.from(c).areNotificationsEnabled();
    }

    static void notify(Context c, int id, String title, String text) {
        ensureChannel(c);
        if (!canNotify(c)) {
            return;
        }
        Intent open = new Intent(c, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, fl);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setColor(0xFFE8640C)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(c).notify(id, b.build());
        } catch (SecurityException e) {
            // keine Erlaubnis
        }
    }

    @PluginMethod
    public void notifyState(PluginCall call) {
        JSObject r = new JSObject();
        r.put("allowed", canNotify(getContext()));
        call.resolve(r);
    }

    @PluginMethod
    public void askNotify(PluginCall call) {
        Context c = getContext();
        if (Build.VERSION.SDK_INT >= 33
            && ContextCompat.checkSelfPermission(c, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
            && getActivity() != null) {
            getActivity().requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 4711);
        } else if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) {
            try {
                Intent i = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
                i.putExtra("android.provider.extra.APP_PACKAGE", c.getPackageName());
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Exception e) {
                // ignorieren
            }
        }
        JSObject r = new JSObject();
        r.put("allowed", canNotify(c));
        call.resolve(r);
    }

    @PluginMethod
    public void testNotify(PluginCall call) {
        if (!canNotify(getContext())) {
            call.reject("Benachrichtigungen sind nicht erlaubt");
            return;
        }
        notify(getContext(), 1, "Fox Tank", "So sieht dein Preis-Alarm aus. Alles bereit!");
        call.resolve();
    }

    // ---------- Preis-Alarm ----------
    // config: JSON-Text mit key, fuel, limit, ids, names, lat, lng, rad, area, quiet, interval
    @PluginMethod
    public void setAlarm(PluginCall call) {
        Context c = getContext();
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        String cfg = call.getString("config", "{}");
        int interval = call.getInt("interval", 30);
        if (interval < 15) {
            interval = 15;
        }
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String old = sp.getString("config", "");
        SharedPreferences.Editor ed = sp.edit().putString("config", cfg).putBoolean("on", on);
        if (!cfg.equals(old)) {
            ed.putString("notified", "{}");
        }
        ed.apply();
        WorkManager wm = WorkManager.getInstance(c);
        if (!on) {
            wm.cancelUniqueWork(WORK);
            call.resolve();
            return;
        }
        ensureChannel(c);
        Constraints cons = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(AlarmWorker.class, interval, TimeUnit.MINUTES)
            .setConstraints(cons)
            .setInitialDelay(2, TimeUnit.MINUTES)
            .build();
        int lastInt = sp.getInt("interval", -1);
        wm.enqueueUniquePeriodicWork(WORK, lastInt == interval ? ExistingPeriodicWorkPolicy.KEEP : ExistingPeriodicWorkPolicy.UPDATE, req);
        sp.edit().putInt("interval", interval).apply();
        call.resolve();
    }

    @PluginMethod
    public void checkNow(PluginCall call) {
        Constraints cons = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        WorkManager.getInstance(getContext()).enqueue(new OneTimeWorkRequest.Builder(AlarmWorker.class).setConstraints(cons).build());
        call.resolve();
    }

    @PluginMethod
    public void alarmState(PluginCall call) {
        SharedPreferences sp = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSObject r = new JSObject();
        r.put("on", sp.getBoolean("on", false));
        r.put("lastRun", sp.getLong("lastRun", 0));
        r.put("lastMsg", sp.getString("lastMsg", ""));
        r.put("lastHit", sp.getLong("lastHit", 0));
        r.put("lastHitMsg", sp.getString("lastHitMsg", ""));
        r.put("allowed", canNotify(getContext()));
        call.resolve(r);
    }
}
