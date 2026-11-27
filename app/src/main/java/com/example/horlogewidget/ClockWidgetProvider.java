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

import java.util.Calendar;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Provider du widget : heure et date sont rendues ensemble dans UNE bitmap
 * (RemoteViews ne permet pas d'appliquer un Typeface a un TextClock), affichee
 * par un ImageView. La bitmap est sur-echantillonnee (x1,5) pour rester nette.
 *
 * Repartition verticale comme l'ancien layout de ClockStf :
 * heure = 5/7 de la hauteur, date = 2/7. Chaque texte est dessine centre dans
 * sa bande, a la plus grande taille qui tient (largeur et hauteur).
 *
 * Mise a jour chaque minute (AlarmManager, sans reveil) + evenements systeme.
 * Clic sur le widget : horloge systeme (com.android.deskclock).
 */
public class ClockWidgetProvider extends AppWidgetProvider {

    private static final String ACTION_TICK = "com.example.horlogewidget.ACTION_TICK";
    private static final int TICK_REQUEST_CODE = 42;
    private static final int CLICK_REQUEST_CODE = 1;

    private static final int TIME_COLOR = Color.WHITE;
    private static final int DATE_COLOR = Color.WHITE;

    /** Part de la hauteur du widget : heure 5/7, date 2/7 (comme ClockStf). */
    private static final float TIME_BAND = 5f / 7f;

    /** Sur-echantillonnage du rendu, pour la nettete. */
    private static final float OVERSAMPLE = 1.5f;

     private static final String[] JOURS = {"LUN", "MAR", "MER", "JEU", "VEN", "SAM", "DIM"};
    private static final String[] MOIS = {
        "JAN", "F\u00c9V", "MAR", "AVR", "MAI", "JUIN",
        "JUIL", "AO\u00dbT", "SEP", "OCT", "NOV", "D\u00c9C"};

    private static final Map<String, Bitmap> CACHE = new LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 4;
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
        // Redimensionnement : recalculer le rendu.
        pushAll(context);
    }

    @Override

public void onReceive(Context context, Intent intent) {
    super.onReceive(context, intent);
    String action = intent.getAction();
    if (action == null) return;
    boolean ours = ACTION_TICK.equals(action);
    boolean system = AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)
            || Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_TIME_CHANGED.equals(action)
            || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
            || Intent.ACTION_DATE_CHANGED.equals(action);
    if (ours || system) {
        pushAll(context);
        scheduleTick(context);  // toujours ré-armer, même après reboot/réinstallation
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
        // L'heure suit le reglage 12 h / 24 h du telephone.
        String timePattern = DateFormat.is24HourFormat(context) ? "HH:mm" : "h:mm a";
        String timeText = new SimpleDateFormat(timePattern, locale).format(now);
        Calendar cal = Calendar.getInstance();
        // Lundi (DAY_OF_WEEK == 2) doit donner l'index 0 : décalage de 5, modulo 7.
        String dateText = JOURS[(cal.get(Calendar.DAY_OF_WEEK) + 5) % 7]
                + " " + cal.get(Calendar.DAY_OF_MONTH)
                + " " + MOIS[cal.get(Calendar.MONTH)];

        float density = context.getResources().getDisplayMetrics().density;

        for (int id : ids) {
                        // Dimensions reelles de CE widget, sur-echantillonnees pour la nettete.
            // Plafonnees a 1600 px : au-dela, l'image depasse la limite d'envoi
            // IPC (~1 Mo) de RemoteViews et la mise a jour echoue silencieusement.
            Bundle opts = mgr.getAppWidgetOptions(id);
            // Telephone en portrait : largeur = MIN_WIDTH, hauteur = MAX_HEIGHT.
            int wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110);
            int hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 48);
            if (wDp <= 0) wDp = 110;
            if (hDp <= 0) hDp = 48;
            float wPx = wDp * density * OVERSAMPLE;
            float hPx = hDp * density * OVERSAMPLE;
            // Si trop grand, on reduit en gardant les proportions du widget.
            float shrink = Math.min(1f, Math.min(1600f / wPx, 900f / hPx));
            int w = Math.max(100, (int) (wPx * shrink));
            int h = Math.max(50, (int) (hPx * shrink));

            Bitmap bitmap = render(w, h, timeText, timeFont, dateText, dateFont);

            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_clock);
            views.setImageViewBitmap(R.id.img_clock, bitmap);
            views.setOnClickPendingIntent(R.id.widget_root, clockPendingIntent(context));
            mgr.updateAppWidget(id, views);
        }
    }

    /** Clic sur le widget : ouvrir l'horloge systeme, comme ClockStf. */
    private static PendingIntent clockPendingIntent(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage("com.android.deskclock");
        if (intent == null) {
            // Horloge systeme introuvable : repli sur l'ecran de parametres.
            intent = new Intent(context, SettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return PendingIntent.getActivity(context, CLICK_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------- Rendu ----------

    /** Heure + date dans une seule bitmap : bande 5/7 pour l'heure, 2/7 pour la date. */
    static Bitmap render(int w, int h, String timeText, Typeface timeFont,
                         String dateText, Typeface dateFont) {
        String key = w + "|" + h + "|" + timeText + "|" + dateText + "|"
                + timeFont + "|" + dateFont;
        synchronized (CACHE) {
            Bitmap cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        // ARGB_8888 : fond transparent, pour laisser voir le rectangle noir
        // semi-transparent (58 %) defini dans widget_background.xml.
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        int hTime = (int) (h * TIME_BAND);
        p.setColor(TIME_COLOR);
        drawFit(canvas, timeText, timeFont, w, hTime, 0, p);
        p.setColor(DATE_COLOR);
        drawFit(canvas, dateText, dateFont, w, h - hTime, hTime, p);

        synchronized (CACHE) {
            CACHE.put(key, bmp);
        }
        return bmp;
    }

    /** Dessine un texte centre dans sa bande, a la plus grande taille qui tient. */
    private static void drawFit(Canvas canvas, String text, Typeface tf,
                                int w, int bandH, int yOffset, Paint p) {
        p.setTypeface(tf);
        android.graphics.Rect b = new android.graphics.Rect();

        // Mesure a taille de reference, sur l'encre reelle du texte
        // (et non sur la hauteur de ligne de la police).
        float size = 200f;
        p.setTextSize(size);
        p.getTextBounds(text, 0, text.length(), b);
        float tw = Math.max(1, b.width());
        float th = Math.max(1, b.height());

        float scale = Math.min(w * 0.96f / tw, bandH * 0.92f / th);
        size *= scale;
        if (size < 1f) {
            size = 1f;
        }
        p.setTextSize(size);
        p.getTextBounds(text, 0, text.length(), b);

        // Centrage horizontal et vertical sur le texte reel.
        float x = (w - b.width()) / 2f - b.left;
        float baseline = yOffset + bandH / 2f - (b.top + b.bottom) / 2f;
        canvas.drawText(text, x, baseline, p);
    }

    // ---------- Compte a rebours d'une minute ----------

    private static PendingIntent tickPendingIntent(Context context) {
        Intent intent = new Intent(context, ClockWidgetProvider.class);
        intent.setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(context, TICK_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

       /** Y a-t-il au moins un widget horloge pose sur l'ecran d'accueil ? */
    static boolean hasWidgets(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, ClockWidgetProvider.class));
        return ids != null && ids.length > 0;
    }

    /** Programme le prochain tic a la frontiere de la minute suivante. */
    static void scheduleTick(Context context) {
        // Aucun widget : inutile de reveiller l'appli toutes les minutes.
        if (!hasWidgets(context)) {
            cancelTick(context);
            return;
        }
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        long delay = 60000 - (System.currentTimeMillis() % 60000);
        long trigger = SystemClock.elapsedRealtime() + delay;
        PendingIntent pi = tickPendingIntent(context);
        try {
            // Alarme exacte (nominalement, une fois par minute).
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, trigger, pi);
        } catch (SecurityException e) {
            // Android a refuse l'alarme exacte : repli sur une alarme approximative.
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, trigger, pi);
        }
    }

     /** Annule l'alarme de rafraichissement (quand le dernier widget est supprime). */
    static void cancelTick(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.cancel(tickPendingIntent(context));
    }
}
