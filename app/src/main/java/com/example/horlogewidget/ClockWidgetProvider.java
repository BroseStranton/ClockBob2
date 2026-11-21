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
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Provider du widget : dessine l'heure et la date dans des bitmaps (pour
 * pouvoir utiliser une fonte personnalisée) et les affiche via ImageView.
 * Un compte à rebours d'une minute (AlarmManager, sans réveil du téléphone)
 * rafraîchit l'affichage ; des diffusions système couvrent démarrage,
 * changement d'heure et de fuseau.
 */
public class ClockWidgetProvider extends AppWidgetProvider {

    private static final String ACTION_TICK = "com.example.horlogewidget.ACTION_TICK";
    private static final int TICK_REQUEST_CODE = 42;

    private static final float TIME_SIZE_SP = 48f;
    private static final float DATE_SIZE_SP = 14f;
    private static final int TIME_COLOR = Color.WHITE;
    private static final int DATE_COLOR = 0xFFDDDDDD;

    /** Petit cache : la même minute ne doit être dessinée qu'une fois. */
    private static final Map<String, Bitmap> CACHE = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 12;
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
                // Le compte à rebours se re-programme lui-même, minute après minute.
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

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_clock);
        views.setImageViewBitmap(R.id.img_time,
                renderText(context, timeText, timeFont, TIME_SIZE_SP, TIME_COLOR));
        views.setImageViewBitmap(R.id.img_date,
                renderText(context, dateText, dateFont, DATE_SIZE_SP, DATE_COLOR));
        views.setOnClickPendingIntent(R.id.widget_root, settingsPendingIntent(context));

        for (int id : ids) {
            mgr.updateAppWidget(id, views);
        }
    }

    private static PendingIntent settingsPendingIntent(Context context) {
        Intent intent = new Intent(context, SettingsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------- Rendu ----------

    /** Dessine du texte dans un bitmap à la densité réelle de l'écran. */
    static Bitmap renderText(Context context, String text, Typeface font, float spSize, int color) {
        String key = text + "|" + spSize + "|" + color + "|"
                + ((font == null) ? "default" : font.toString());
        synchronized (CACHE) {
            Bitmap cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, spSize,
                context.getResources().getDisplayMetrics());
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(px);
        paint.setColor(color);
        if (font != null) {
            paint.setTypeface(font);
        }
        Paint.FontMetrics fm = paint.getFontMetrics();
        int w = Math.max(1, (int) Math.ceil(paint.measureText(text) + px * 0.15f));
        int h = Math.max(1, (int) Math.ceil(fm.descent - fm.ascent + px * 0.10f));
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
