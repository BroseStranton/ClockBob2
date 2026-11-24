package com.example.horlogewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Liste des fontes installées (FontStore), une par ligne, avec un bouton
 * « Supprimer » au bout de chaque ligne. La confirmation Oui/Non passe par
 * un AlertDialog standard. Après une suppression on reste sur cette fenêtre,
 * pour pouvoir en supprimer d'autres ; « Annuler » referme tout.
 */
public class DeleteFontActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_delete_font);

        Button btnFermer = (Button) findViewById(R.id.btn_fermer);
        btnFermer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        rebuildList();
    }

    /** Reconstruit la liste depuis FontStore (appelé après chaque suppression). */
    private void rebuildList() {
        LinearLayout list = (LinearLayout) findViewById(R.id.ll_font_list);
        TextView empty = (TextView) findViewById(R.id.tv_empty);
        list.removeAllViews();

        List<String> fonts = FontStore.listFontNames(this);
        if (fonts.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            return;
        }
        empty.setVisibility(View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (final String name : fonts) {
            View row = inflater.inflate(R.layout.item_font_row, list, false);

            TextView tvName = (TextView) row.findViewById(R.id.tv_font_name);
            tvName.setText(displayName(name));

            Button btnDelete = (Button) row.findViewById(R.id.btn_font_delete);
            btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(name);
                }
            });

            list.addView(row);
        }
    }

    /** Nom affiché : nom du fichier sans extension (même convention que les listes déroulantes). */
    private static String displayName(String storedName) {
        int dot = storedName.lastIndexOf('.');
        return (dot > 0) ? storedName.substring(0, dot) : storedName;
    }

    private void confirmDelete(final String name) {
        new AlertDialog.Builder(this)
                .setTitle(displayName(name))
                .setMessage(R.string.confirm_delete_font)
                .setPositiveButton(R.string.btn_yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteFont(name);
                    }
                })
                .setNegativeButton(R.string.btn_no, null)
                .show();
    }

    private void deleteFont(String name) {
        boolean usedAsTime = name.equals(FontStore.prefsGet(this, FontStore.KEY_TIME_FONT));
        boolean usedAsDate = name.equals(FontStore.prefsGet(this, FontStore.KEY_DATE_FONT));

        FontStore.deleteFont(this, name);

        if (usedAsTime) {
            FontStore.prefsPut(this, FontStore.KEY_TIME_FONT, "");
        }
        if (usedAsDate) {
            FontStore.prefsPut(this, FontStore.KEY_DATE_FONT, "");
        }
        if (usedAsTime || usedAsDate) {
            // Demande au provider de redessiner le widget immédiatement :
            // il retombera sur la fonte système.
            sendBroadcast(new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(new ComponentName(this, ClockWidgetProvider.class)));
        }

        rebuildList();
    }
}
