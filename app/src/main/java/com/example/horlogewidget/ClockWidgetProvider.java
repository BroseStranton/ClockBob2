package com.example.horlogewidget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Provider du widget : dessine l'heure et la date dans des bitmaps (pour
 * pouvoir utiliser une fonte personnalisée) et les affiche via ImageView.
 *
 * La taille du texte s'adapte aux dimensions réelles de chaque widget
 * (lues dans getAppWidgetOptions) : l'heure occupe environ 60 % de la
 * hauteur, la date environ 20 %, et le texte est ajusté pour tenir dans
 * la largeur. Le clic sur le widget ouvre l'horloge système
 * (com.android.deskclock), comme ClockStf.
 *
 * Un compte à rebours d'une minute (AlarmManager, sans réveil du téléphone)
 * rafraîchit l'affichage ; des diffusions système couvrent démarrage,
 * changement d'heure et de fuseau.
 */
public class ClockWidgetProvider extends AppWidgetProvider {

    private static final String ACTION_TICK = "com.example.horlogewidget.ACTION_TICK";
    private static final int TICK_REQUEST_CODE = 42;
    private static final int CLICK_REQUEST_CODE = 1;

    private static final int TIME_COLOR = Color.WHITE;
    private static final int DATE_COLOR = 0xFFDDDDDD;

    /** Part de la hauteur du widget réservée à l'heure et à la date. */
     private static final float TIME_HEIGHT_RATIO = 0.56f;
     private static final float DATE_HEIGHT_RATIO = 0.22f;
    /** Le texte ne doit pas dépasser cette part de la largeur. */
    private static final float WIDTH_RATIO = 0.92f;

    /** Petit cache : la même minute ne doit être dessinée qu'une fois. */
    private static final Map<String, Bitmap> CACHE = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 24;
        }
    };

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        pushAll(context, appWidgetManager, appWidgetIds);
        scheduleTick(context);
    }

    @Override
    public void onEnabled(Context context) {
        scheduleTick(context);
    }

    @Override
    public void onDisabled(Context context) {
        cancelTick(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, Bundle newOptions) {
        // Redimensionnement du widget : recalculer les tailles de texte.
        pushAll(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        boolean ours = ACTION_TICK.equals(action);
        boolean system = AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (ours || system) {
            pushAll(context);
            if (ours) {
                scheduleTick(context);
            }
        }
    }

    // ---------- Affichage ----------

    static void pushAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, ClockWidgetProvider.class));
        pushAll(context, mgr, ids);
    }

    static void pushAll(Context context, AppWidgetManager mgr, int[] ids) {
        if (ids == null || ids.length == 0) {
            return;
        }
        Typeface timeFont = FontStore.loadFont(context, FontStore.prefsGet(context, FontStore.KEY_TIME_FONT));
        Typeface dateFont = FontStore.loadFont(context, FontStore.prefsGet(context, FontStore.KEY_DATE_FONT));

        Date now = new Date();
        Locale locale = Locale.getDefault();
        // L'heure suit le réglage 12 h / 24 h du téléphone.
        String timePattern = DateFormat.is24HourFormat(context) ? "HH:mm" : "h:mm a";
        String timeText = new SimpleDateFormat(timePattern, locale).format(now);
        String dateText = new SimpleDateFormat(
                DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale).format(now);

        float density = context.getResources().getDisplayMetrics().density;

        for (int id : ids) {
            // Dimensions réelles de CE widget, en dip (disponibles depuis l'API 16).
            Bundle opts = mgr.getAppWidgetOptions(id);
            int widthDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110);
            int heightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 70);
            if (heightDp <= 0) {
                heightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 70);
            }
            float widthPx = widthDp * density;
            float heightPx = heightDp * density;

            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_clock);
            views.setImageViewBitmap(R.id.img_time,
                    renderText(context, timeText, timeFont,
                            textSize(widthPx, heightPx, timeText, TIME_HEIGHT_RATIO),
                            TIME_COLOR));
            views.setImageViewBitmap(R.id.img_date,
                    renderText(context, dateText, dateFont,
                            textSize(widthPx, heightPx, dateText, DATE_HEIGHT_RATIO),
                            DATE_COLOR));
            views.setOnClickPendingIntent(R.id.widget_root, clockPendingIntent(context));
            mgr.updateAppWidget(id, views);
        }
    }

    /**
     * Taille de texte en pixels : part fixe de la hauteur du widget, puis
     * réduite si nécessaire pour tenir dans la largeur.
     */
    private static float textSize(float widthPx, float heightPx, String text, float heightRatio) {
        Paint probe = new Paint(Paint.ANTI_ALIAS_FLAG);
        float size = Math.max(1f, heightPx * heightRatio);
        probe.setTextSize(size);
        float measured = probe.measureText(text);
        if (measured > widthPx * WIDTH_RATIO) {
            size = size * (widthPx * WIDTH_RATIO) / measured;
        }
        return size;
    }

    /** Clic sur le widget : ouvrir l'horloge système, comme ClockStf. */
    private static PendingIntent clockPendingIntent(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage("com.android.deskclock");
        if (intent == null) {
            // Horloge système introuvable : repli sur l'écran de paramètres.
            intent = new Intent(context, SettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return PendingIntent.getActivity(context, CLICK_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------- Rendu ----------

    /** Dessine du texte dans un bitmap à la taille demandée, en pixels réels. */
    static Bitmap renderText(Context context, String text, Typeface font, float textSizePx, int color) {
        String key = text + "|" + (int) textSizePx + "|" + color + "|"
                + ((font == null) ? "default" : font.toString());
        synchronized (CACHE) {
            Bitmap cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(textSizePx);
        paint.setColor(color);
        if (font != null) {
            paint.setTypeface(font);
        }
        Paint.FontMetrics fm = paint.getFontMetrics();
        int w = Math.max(1, (int) Math.ceil(paint.measureText(text) + textSizePx * 0.10f));
        int h = Math.max(1, (int) Math.ceil(fm.descent - fm.ascent));
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(bitmap).drawText(text, 0, -fm.ascent, paint);
        synchronized (CACHE) {
            CACHE.put(key, bitmap);
        }
        return bitmap;
    }

    // ---------- Compte à rebours d'une minute ----------

    private static PendingIntent tickPendingIntent(Context context) {
        Intent intent = new Intent(context, ClockWidgetProvider.class);
        intent.setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(context, TICK_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Programme le prochain tic à la frontière de la minute suivante. */
    static void scheduleTick(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        long delay = 60000 - (System.currentTimeMillis() % 60000);
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + delay, tickPendingIntent(context));
    }

    static void cancelTick(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.cancel(tickPendingIntent(context));
    }
}
