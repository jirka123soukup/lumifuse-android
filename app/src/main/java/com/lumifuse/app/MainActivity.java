package com.lumifuse.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.*;
import org.opencv.android.OpenCVLoader;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** UI, čtení fotek a slučování jsou oddělené; přístup k fotkám jen přes výběr souborů, bez oprávnění k celé galerii. */
public final class MainActivity extends Activity {
    private static final int PICK_IMAGES = 42;
    private static final int TRASH_REQUEST = 43;
    /** Delší strana výstupu v px; poslední položka = bez zmenšení (plné rozlišení). */
    private static final int[] EDGES = {1280, 2048, 3072, 4096, 5120, 100000};
    /** Náhled na displeji se zmenšuje – příliš velký bitmap by při vykreslení shodil aplikaci. */
    private static final int PREVIEW_EDGE = 2048;
    private final ArrayList<Uri> selected = new ArrayList<>();
    /** Fotky, ze kterých vznikl aktuální výsledek – jen ty se smí po uložení smazat. */
    private final ArrayList<Uri> resultInputs = new ArrayList<>();
    private int trashSkipped;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Spinner mode, size;
    private CheckBox align;
    private Button pick, run, save, compare;
    private TextView status;
    private ImageView preview;
    private ProgressBar progress;
    private Bitmap result, original, shown;
    private boolean busy, showingOriginal;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            ArrayList<String> values = state.getStringArrayList("selected");
            if (values != null) for (String value : values) selected.add(Uri.parse(value));
        }
        buildUi();
        if (!OpenCVLoader.initLocal()) {
            status.setText("Nepodařilo se načíst knihovnu pro zpracování obrazu, přeinstalujte aplikaci");
            run.setEnabled(false);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.setBackgroundColor(Color.rgb(250, 248, 255));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(20), dp(12) + insets.getSystemWindowInsetTop(),
                    dp(20), dp(12) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title = label("LumiFuse", 28);
        title.setTypeface(null, Typeface.BOLD); root.addView(title);
        root.addView(label("Ze série expozic jedna dobrá fotka – přímo v telefonu", 13));
        mode = spinner(new String[]{"HDR · různé expozice", "Hloubka ostrosti · různé zaostření"}); root.addView(mode);
        pick = button("Vybrat 2–7 fotek JPG", root); pick.setOnClickListener(view -> pickImages());
        size = spinner(new String[]{
                "Rychlé · delší strana 1280 px",
                "Standard · delší strana 2048 px",
                "Jemné · delší strana 3072 px",
                "Velké · delší strana 4096 px (hodně paměti)",
                "Velmi velké · delší strana 5120 px (hodně paměti)",
                "Plné rozlišení (jen pro 3 snímky, nejvíc paměti)"});
        size.setSelection(3); root.addView(size);
        align = new CheckBox(this); align.setText("Automaticky zarovnat a oříznout černé okraje"); align.setChecked(true); root.addView(align);
        run = button("Sloučit", root); run.setOnClickListener(view -> process());
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true); progress.setVisibility(View.GONE); root.addView(progress);
        status = label(selected.isEmpty() ? "Zatím nevybrány žádné fotky · zpracování offline · nic se nenahrává"
                : "Obnoveno " + selected.size() + " fotek, spusťte slučování znovu", 13);
        root.addView(status);
        preview = new ImageView(this); preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription("Náhled fotky");
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = new LinearLayout(this);
        compare = button("Zobrazit originál", actions); compare.setEnabled(false);
        compare.setOnClickListener(view -> {
            showingOriginal = !showingOriginal;
            preview.setImageBitmap(showingOriginal ? original : shown);
            compare.setText(showingOriginal ? "Zobrazit výsledek" : "Zobrazit originál");
        });
        save = button("Uložit JPG", actions); save.setEnabled(false);
        save.setOnClickListener(view -> saveResult()); root.addView(actions);
        setContentView(root);
    }

    private void pickImages() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/jpeg"); intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_IMAGES);
    }

    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request == TRASH_REQUEST) { onTrashResult(code); return; }
        if (request != PICK_IMAGES || code != RESULT_OK || data == null) return;
        ArrayList<Uri> incoming = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if (!incoming.contains(uri)) incoming.add(uri);
            }
        } else if (data.getData() != null) incoming.add(data.getData());
        if (incoming.size() < 2 || incoming.size() > 7) {
            status.setText("Vyberte 2–7 různých fotek JPG, tento výběr nebyl použit"); return;
        }
        for (Uri uri : incoming) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { /* Některé zdroje povolují přístup jen pro aktuální relaci. */ }
        }
        clearResult(); selected.clear(); selected.addAll(incoming);
        status.setText("Vybráno " + selected.size() + " fotek · HDR = různé expozice, hloubka ostrosti = různé zaostření");
    }

    private void process() {
        if (busy) return;
        if (selected.size() < 2) { status.setText("Nejdřív vyberte 2–7 fotek JPG"); return; }
        final List<Uri> inputs = new ArrayList<>(selected);
        final int edge = EDGES[size.getSelectedItemPosition()];
        final boolean hdr = mode.getSelectedItemPosition() == 0, useAlignment = align.isChecked();
        clearResult(); setBusy(true); status.setText("Připravuji…");
        worker.execute(() -> {
            Bitmap fused = null, first = null, small = null;
            try {
                fused = Fusion.merge(getContentResolver(), inputs, edge, hdr, useAlignment,
                        message -> runOnUiThread(() -> { if (!isDestroyed()) status.setText(message); }));
                small = scaledForPreview(fused);
                first = ImageLoader.load(getContentResolver(), inputs.get(0), 1280);
                final Bitmap output = fused, source = first, display = small;
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        if (display != output) display.recycle();
                        output.recycle(); source.recycle(); return;
                    }
                    result = output; original = source; shown = display;
                    resultInputs.clear(); resultInputs.addAll(inputs);
                    preview.setImageBitmap(shown); setBusy(false);
                    status.setText("Hotovo · " + result.getWidth() + " × " + result.getHeight()
                            + " px · " + inputs.size() + " fotek\n"
                            + (hdr ? "Expoziční fúze do JPG (ne HDR DNG)" : "Hloubka ostrosti: u okrajů předmětů může vzniknout svatozář"));
                });
            } catch (Exception | OutOfMemoryError error) {
                if (small != null && small != fused) small.recycle();
                if (fused != null) fused.recycle();
                if (first != null) first.recycle();
                reportFailure(isMemoryError(error)
                        ? "Došla paměť – zvolte menší velikost výstupu nebo méně fotek"
                        : error.getMessage());
            }
        });
    }

    private static boolean isMemoryError(Throwable error) {
        if (error instanceof OutOfMemoryError) return true;
        String message = error.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("insufficient memory") || lower.contains("out of memory")
                || lower.contains("failed to allocate");
    }

    private static Bitmap scaledForPreview(Bitmap source) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= PREVIEW_EDGE) return source;
        float scale = (float) PREVIEW_EDGE / longest;
        return Bitmap.createScaledBitmap(source,
                Math.max(1, Math.round(source.getWidth() * scale)),
                Math.max(1, Math.round(source.getHeight() * scale)), true);
    }

    private void saveResult() {
        if (busy || result == null) return;
        final Bitmap image = result;
        final List<Uri> toTrash = new ArrayList<>(resultInputs);
        setBusy(true); status.setText("Ukládám…");
        worker.execute(() -> {
            Uri uri = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "LumiFuse_" + System.currentTimeMillis() + ".jpg");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LumiFuse");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("V galerii se nepodařilo vytvořit soubor");
                try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null || !image.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                        throw new IOException("Zápis JPG selhal");
                    }
                }
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (getContentResolver().update(uri, values, null, null) != 1) throw new IOException("Zveřejnění v galerii selhalo");
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    setBusy(false);
                    status.setText("Uloženo do galerie · Pictures/LumiFuse · originály beze změny");
                    if (!toTrash.isEmpty()) askTrash(toTrash);
                });
            } catch (Exception error) {
                // Smaže jen prázdný soubor vytvořený teď, nikdy vstupní fotky.
                if (uri != null) {
                    try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
                }
                reportFailure("Uložení selhalo: " + error.getMessage());
            } finally {
                if (isDestroyed()) image.recycle();
            }
        });
    }

    private void askTrash(List<Uri> inputs) {
        new AlertDialog.Builder(this)
                .setTitle("Fotka uložena")
                .setMessage("Přesunout " + inputs.size() + " původních fotek do koše?\nVýsledek zůstane v galerii ve složce LumiFuse.")
                .setPositiveButton("Přesunout do koše", (dialog, which) -> requestTrash(inputs))
                .setNegativeButton("Ponechat", null)
                .show();
    }

    /** Originály se nemažou natrvalo: systém je po potvrzení přesune do koše (lze je obnovit). */
    private void requestTrash(List<Uri> inputs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            status.setText("Uloženo do galerie · přesun do koše vyžaduje Android 11 nebo novější, originály zůstaly");
            return;
        }
        ArrayList<Uri> media = new ArrayList<>();
        int skipped = 0;
        for (Uri uri : inputs) {
            Uri mediaUri = null;
            try {
                if (MediaStore.AUTHORITY.equals(uri.getAuthority())) mediaUri = uri;
                else mediaUri = MediaStore.getMediaUri(this, uri);
            } catch (Exception ignored) { }
            if (mediaUri != null) media.add(mediaUri); else skipped++;
        }
        trashSkipped = skipped;
        if (media.isEmpty()) {
            status.setText("Uloženo do galerie · originály nejde z aplikace smazat (vyberte je přes Galerii nebo Soubory, ne přes Google Fotky)");
            return;
        }
        try {
            PendingIntent request = MediaStore.createTrashRequest(getContentResolver(), media, true);
            startIntentSenderForResult(request.getIntentSender(), TRASH_REQUEST, null, 0, 0, 0);
        } catch (Exception error) {
            status.setText("Uloženo do galerie · přesun originálů do koše selhal: " + error.getMessage());
        }
    }

    private void onTrashResult(int code) {
        if (code == RESULT_OK) {
            int moved = resultInputs.size() - trashSkipped;
            selected.clear(); resultInputs.clear();
            status.setText("Uloženo do galerie · " + moved + " původních fotek přesunuto do koše"
                    + (trashSkipped > 0 ? " · " + trashSkipped + " nešlo smazat, smažte je ručně" : ""));
        } else {
            status.setText("Uloženo do galerie · mazání zrušeno, originály zůstaly");
        }
    }

    private void reportFailure(String message) {
        runOnUiThread(() -> { if (!isDestroyed()) { setBusy(false); status.setText("Nedokončeno: " + message); } });
    }

    private void setBusy(boolean value) {
        busy = value; pick.setEnabled(!value); run.setEnabled(!value);
        mode.setEnabled(!value); size.setEnabled(!value); align.setEnabled(!value);
        save.setEnabled(!value && result != null); compare.setEnabled(!value && original != null);
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    private void clearResult() {
        preview.setImageDrawable(null);
        if (shown != null && shown != result) shown.recycle();
        if (result != null) result.recycle();
        if (original != null) original.recycle();
        result = null; original = null; shown = null; showingOriginal = false; resultInputs.clear();
        save.setEnabled(false); compare.setEnabled(false); compare.setText("Zobrazit originál");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        ArrayList<String> values = new ArrayList<>();
        for (Uri uri : selected) values.add(uri.toString());
        state.putStringArrayList("selected", values); super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        // Pokud výsledek právě ukládá vlákno, uvolní ho to vlákno samo.
        if (shown != null && shown != result) shown.recycle();
        if (!busy && result != null) result.recycle();
        if (original != null) original.recycle();
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size);
        view.setTextColor(Color.rgb(50, 42, 65)); view.setPadding(0, dp(4), 0, dp(4)); return view;
    }
    private Spinner spinner(String[] options) {
        Spinner view = new Spinner(this);
        view.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options)); return view;
    }
    private Button button(String text, LinearLayout parent) {
        Button view = new Button(this); view.setText(text); view.setAllCaps(false);
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) parent.addView(view, new LinearLayout.LayoutParams(0, -2, 1));
        else parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
}
