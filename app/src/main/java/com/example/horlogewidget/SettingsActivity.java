package com.example.horlogewidget;

import android.app.Activity;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Écran de paramètres (conforme au mockup) : avertissement, sélection de la
 * fonte de l'heure et de la date (listes déroulantes), import d'une fonte
 * par le sélecteur de fichiers, puis Appliquer / Par défaut / Fermer.
 *
 * L'import se fait dès le choix du fichier : le fichier est copié dans le
 * stockage interne et validé immédiatement (FontStore), puis il apparaît
 * dans les deux listes. « Appliquer » enregistre les choix et rafraîchit
 * le widget ; « Par défaut » remet la fonte système partout.
 */
public class SettingsActivity extends Activity {

    private static final int REQUEST_PICK_FONT = 7;

    private Spinner spinnerTime;
    private Spinner spinnerDate;
    private EditText editFileName;

    /** Correspondance position de la liste -> nom de fichier stocké ("" = système). */
    private List<String> timeValues = new ArrayList<String>();
    private List<String> dateValues = new ArrayList<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        spinnerTime = (Spinner) findViewById(R.id.spinner_time_font);
        spinnerDate = (Spinner) findViewById(R.id.spinner_date_font);
        editFileName = (EditText) findViewById(R.id.edit_file_name);

        Button btnBrowse = (Button) findViewById(R.id.btn_browse);
        Button btnApply = (Button) findViewById(R.id.btn_apply);
        Button btnReset = (Button) findViewById(R.id.btn_reset);
        Button btnFermer = (Button) findViewById(R.id.btn_fermer);

        btnBrowse.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchPicker();
            }
        });
        btnApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applySettings();
            }
        });
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetSettings();
            }
        });
        btnFermer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
            
        });

        Button btnFonts = (Button) findViewById(R.id.btn_fonts);
        btnFonts.setOnClickListener(new View.OnClickListener() {
    
            @Override
        public void onClick(View v) {
        startActivity(new android.content.Intent(
                SettingsActivity.this, DeleteFontActivity.class));
    }
});
        reloadSpinners();
    }
    @Override
    protected void onResume() {
        super.onResume();
        // Rafraîchit les listes si des fontes ont été supprimées dans DeleteFontActivity.
        reloadSpinners();
    }
    // ---------- Listes déroulantes ----------

    /** Libellé affiché : nom du fichier sans extension. */
    private static String displayName(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return (dot > 0) ? fileName.substring(0, dot) : fileName;
    }

    private void reloadSpinners() {
        List<String> stored = FontStore.listFontNames(this);

        timeValues.clear();
        timeValues.add(""); // fonte système
        timeValues.addAll(stored);
        dateValues.clear();
        dateValues.add("");
        dateValues.addAll(stored);

        List<String> labels = new ArrayList<String>();
        labels.add(getString(R.string.entry_default_font));
        for (String s : stored) {
            labels.add(displayName(s));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTime.setAdapter(adapter);
        spinnerDate.setAdapter(adapter);

        spinnerTime.setSelection(indexOf(timeValues, FontStore.prefsGet(this, FontStore.KEY_TIME_FONT)));
        spinnerDate.setSelection(indexOf(dateValues, FontStore.prefsGet(this, FontStore.KEY_DATE_FONT)));
    }

    private static int indexOf(List<String> values, String wanted) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).equals(wanted)) {
                return i;
            }
        }
        return 0;
    }

    // ---------- Sélecteur de fichiers ----------

    private void launchPicker() {
        android.content.Intent intent = new android.content.Intent(
                android.content.Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_PICK_FONT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_PICK_FONT || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }

        final Uri uri = data.getData();
        final String suggested = queryDisplayName(uri);
        final android.content.Context appContext = getApplicationContext();

        // L'import (copie + verification) peut etre long : on le fait en arriere-plan.
        new Thread(new Runnable() {
            @Override
            public void run() {
                String stored = null;
                String existing = null;
                int messageRes;
                InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(uri);
                    stored = FontStore.importFont(appContext, in, suggested);
                    messageRes = R.string.toast_imported;
                } catch (FontStore.DuplicateFontException e) {
                    existing = e.existingName;
                    messageRes = R.string.toast_copied;
                } catch (IllegalArgumentException e) {
                    messageRes = R.string.toast_invalid;
                } catch (IOException e) {
                    messageRes = R.string.toast_error;
                } catch (RuntimeException e) {
                    messageRes = R.string.toast_error;
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (IOException ignored) {
                        }
                    }
                }

                final boolean imported = (stored != null);
                final String shown = imported ? displayName(stored)
                        : (existing != null ? displayName(existing) : "");
                final int msg = messageRes;
                final boolean ok = imported || existing != null;

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        editFileName.setText(shown);
                        if (imported) {
                            reloadSpinners();
                        }
                        Toast.makeText(SettingsActivity.this, getString(msg),
                                ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    /** Nom d'origine du fichier choisi, lu dans le fournisseur de contenu. */
    private String queryDisplayName(Uri uri) {
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor == null) {
            return null;
        }
        try {
            int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getString(idx);
            }
        } finally {
            cursor.close();
        }
        return null;
    }

    // ---------- Boutons du bas ----------

    private void applySettings() {
        FontStore.prefsPut(this, FontStore.KEY_TIME_FONT,
                timeValues.get(spinnerTime.getSelectedItemPosition()));
        FontStore.prefsPut(this, FontStore.KEY_DATE_FONT,
                dateValues.get(spinnerDate.getSelectedItemPosition()));
        ClockWidgetProvider.pushAll(this);
         ClockWidgetProvider.scheduleTick(this); 
        Toast.makeText(this, getString(R.string.toast_applied), Toast.LENGTH_SHORT).show();
    }

    private void resetSettings() {
        FontStore.prefsPut(this, FontStore.KEY_TIME_FONT, "");
        FontStore.prefsPut(this, FontStore.KEY_DATE_FONT, "");
        reloadSpinners();
        ClockWidgetProvider.pushAll(this);
         ClockWidgetProvider.scheduleTick(this);
        Toast.makeText(this, getString(R.string.toast_applied), Toast.LENGTH_SHORT).show();
    }
}
