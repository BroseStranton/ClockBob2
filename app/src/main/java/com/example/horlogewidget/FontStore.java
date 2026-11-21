package com.example.horlogewidget;

import android.content.Context;
import android.graphics.Typeface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Stockage et validation des fontes importées.
 *
 * Les fichiers sont copiés dans le stockage interne privé de l'application
 * (getFilesDir()/fonts) : aucun permission n'est nécessaire, et les fichiers
 * survivent aux redémarrages.
 *
 * Nommage : le nom d'origine du fichier est conservé (nettoyé par une liste
 * blanche de caractères sûrs, inspirée de SafeName.java de ReteClock).
 * En cas de doublon : "Nom.ttf", "Nom-2.ttf", etc.
 *
 * Validation : signature binaire (TrueType, OpenType, collection .ttc) puis
 * essai réel de chargement par Typeface.createFromFile(). Une fonte refusée
 * n'est jamais stockée.
 */
public final class FontStore {

    /** SharedPreferences : valeur = nom de fichier stocké, ou "" = fonte système. */
    public static final String PREFS = "clockbob_prefs";
    public static final String KEY_TIME_FONT = "time_font";
    public static final String KEY_DATE_FONT = "date_font";

    private static final String DIR_NAME = "fonts";
    private static final String TEMP_NAME = "import.tmp";
    private static final int MAX_STEM_CHARS = 100;
    private static final String FALLBACK_STEM = "font";

    private FontStore() {
    }

    public static File fontsDir(Context context) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        return dir;
    }

    /** Nettoie un nom venu de l'extérieur vers un simple nom de fichier sûr. */
    public static String sanitiseName(String raw) {
        if (raw == null) {
            return FALLBACK_STEM;
        }
        // Ne garder que la dernière composante, si un chemin a été transmis.
        String name = raw.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1).trim();
        }
        // Liste blanche : lettres de tous alphabets, chiffres, _ - . espace.
        StringBuilder stem = new StringBuilder();
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            extension = name.substring(dot);
            name = name.substring(0, dot);
        }
        for (int i = 0; i < name.length() && stem.length() < MAX_STEM_CHARS; i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ' ') {
                stem.append(c);
            }
        }
        String cleaned = stem.toString().trim();
        if (cleaned.isEmpty()) {
            cleaned = FALLBACK_STEM;
        }
        return cleaned + extension;
    }

    /** Ce nom se termine-t-il par une extension de fonte acceptée ? */
    public static boolean hasFontExtension(String name) {
        String lower = (name == null) ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc");
    }

    /** Premier nom libre : "Nom.ttf", sinon "Nom-2.ttf", "Nom-3.ttf"... */
    private static String availableName(File dir, String name) {
        String stem = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            stem = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int n = 1; n < 1000; n++) {
            String candidate = (n == 1) ? name : stem + "-" + n + ext;
            if (!new File(dir, candidate).exists()) {
                return candidate;
            }
        }
        throw new IllegalStateException("trop de fichiers nommes " + name);
    }

    /**
     * Signature binaire des 4 premiers octets :
     * 00 01 00 00 = TrueType, "OTTO" = OpenType, "ttcf" = collection, "true" = ancien format.
     */
    static boolean signatureLooksLikeFont(byte[] head) {
        if (head == null || head.length < 4) {
            return false;
        }
        if ((head[0] & 0xFF) == 0 && (head[1] & 0xFF) == 1
                && (head[2] & 0xFF) == 0 && (head[3] & 0xFF) == 0) {
            return true;
        }
        String tag = new String(head, 0, 4, Locale.US);
        return tag.equals("OTTO") || tag.equals("ttcf") || tag.equals("true");
    }

    /**
     * Copie le flux dans le stockage interne et vérifie que le contenu est
     * bien une fonte lisible par Android (signature + essai réel de chargement).
     *
     * @param suggestedName nom proposé (vient du sélecteur de fichiers) ;
     *                      doit avoir une extension .ttf / .otf / .ttc
     * @return le nom du fichier effectivement stocké, avec extension
     * @throws IOException              flux illisible ou stockage impossible
     * @throws IllegalArgumentException contenu refusé (pas une fonte lisible)
     */
    public static String importFont(Context context, InputStream in, String suggestedName)
            throws IOException {
        if (in == null) {
            throw new IOException("flux vide");
        }
        String name = sanitiseName(suggestedName);
        if (!hasFontExtension(name)) {
            throw new IllegalArgumentException("extension non acceptee : " + name);
        }
        File dir = fontsDir(context);
        File tmp = new File(dir, TEMP_NAME);
        FileOutputStream out = new FileOutputStream(tmp);
        byte[] buf = new byte[8192];
        byte[] head = new byte[4];
        int headFilled = 0;
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                int take = Math.min(n, 4 - headFilled);
                if (take > 0) {
                    System.arraycopy(buf, 0, head, headFilled, take);
                    headFilled += take;
                }
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
        if (headFilled < 4 || !signatureLooksLikeFont(head)) {
            tmp.delete();
            throw new IllegalArgumentException("signature non reconnue");
        }
        // Essai réel : c'est l'appareil qui a le dernier mot.
        Typeface test;
        try {
            test = Typeface.createFromFile(tmp);
        } catch (RuntimeException e) {
            test = null;
        }
        if (test == null) {
            tmp.delete();
            throw new IllegalArgumentException("fonte illisible par Android");
        }
        String finalName = availableName(dir, name);
        if (!tmp.renameTo(new File(dir, finalName))) {
            tmp.delete();
            throw new IOException("renommmage impossible");
        }
        return finalName;
    }

    /** Noms de fichiers des fontes stockées, triés. */
    public static List<String> listFontNames(Context context) {
        File[] files = fontsDir(context).listFiles();
        List<String> names = new ArrayList<String>();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && !TEMP_NAME.equals(f.getName())) {
                    names.add(f.getName());
                }
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Le fichier d'une fonte stockée, ou null. Ne peut jamais sortir du dossier. */
    public static File fontFile(Context context, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        File dir = fontsDir(context);
        File f = new File(dir, name);
        if (!f.isFile() || !dir.equals(f.getParentFile())) {
            return null;
        }
        return f;
    }

    /** Charge la fonte, ou null si absente/illisible (retombe sur la fonte système). */
    public static Typeface loadFont(Context context, String name) {
        File f = fontFile(context, name);
        if (f == null) {
            return null;
        }
        try {
            return Typeface.createFromFile(f);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void deleteFont(Context context, String name) {
        File f = fontFile(context, name);
        if (f != null) {
            f.delete();
        }
    }

    public static String prefsGet(Context context, String key) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "");
    }

    public static void prefsPut(Context context, String key, String value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(key, value).apply();
    }
}
