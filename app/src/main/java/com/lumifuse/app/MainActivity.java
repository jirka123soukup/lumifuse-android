package com.lumifuse.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.*;
import androidx.exifinterface.media.ExifInterface;
import org.opencv.android.OpenCVLoader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** UI, čtení fotek a slučování jsou oddělené; vstupní fotky se vybírají přes systémový výběr souborů. */
public final class MainActivity extends Activity {
    private static final int PICK_IMAGES = 42;
    private static final int TRASH_REQUEST = 43;
    private static final int PERMISSION_REQUEST = 44;
    private static final String OUTPUT_FOLDER = "Pictures/LumiFuse/";
    /** Delší strana výstupu v px; poslední položka = bez zmenšení (plné rozlišení). */
    private static final int[] EDGES = {1280, 2048, 3072, 4096, 5120, 100000};
    /** Značka kvality v názvu souboru – odpovídá položkám EDGES. */
    private static final String[] QUALITY = {"LQ", "SQ", "HQ", "XQ", "UQ", "FQ"};
    /** Náhled na displeji se zmenšuje – příliš velký bitmap by při vykreslení shodil aplikaci. */
    private static final int PREVIEW_EDGE = 2048;
    /** EXIF údaje, které se do výsledku přenesou z nejnovější vstupní fotky (bez expozice/ISO – ty by neplatily). */
    private static final String[] COPY_TAGS = {
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT};
    private final ArrayList<Uri> selected = new ArrayList<>();
    /** Fotky, ze kterých vznikl aktuální výsledek – jen ty se smí po uložení smazat. */
    private final ArrayList<Uri> resultInputs = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Spinner mode, size;
    private CheckBox align;
    private Button pick, run, cancel, save, compare;
    private TextView status;
    private ImageView preview;
    private ProgressBar progress;
    private Bitmap result, original, shown;
    private String resultName;
    private Map<String, String> resultExif;
    private boolean busy, showingOriginal, permissionAsked;
    private int trashSkipped;
    /** Fotky (v galerii), u kterých se čeká na systémové potvrzení přesunu do koše. */
    private final ArrayList<Uri> pendingTrash = new ArrayList<>();
    /** Každé spuštění slučování dostane číslo; po zrušení se pozdní výsledky starého běhu zahodí. */
    private int generation;
    private Future<?> task;

    // ---------- Úpravy po sloučení ----------
    private static final String[] ADJ_NAMES = {"Komprese dynamiky", "Stíny", "Světla", "Expozice",
            "Kontrast", "Sytost", "Teplota", "Doostření"};
    private static final boolean[] ADJ_BIPOLAR = {false, true, true, true, true, true, true, false};
    private final int[] adj = new int[ADJ_NAMES.length];
    private final SeekBar[] adjBars = new SeekBar[ADJ_NAMES.length];
    private final TextView[] adjLabels = new TextView[ADJ_NAMES.length];
    private ScrollView adjustPanel;
    private Button adjustToggle;
    /** Zmenšená kopie výsledku pro rychlý náhled úprav a upravený náhled. Neuvolňují se ručně (souběh s vláknem). */
    private Bitmap adjustBase, adjustedPreview;
    private int previewSeq;
    private final ExecutorService previewWorker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable previewRunnable = this::renderPreview;

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
                "LQ · rychlé · delší strana 1280 px",
                "SQ · standard · delší strana 2048 px",
                "HQ · jemné · delší strana 3072 px",
                "XQ · velké · delší strana 4096 px (hodně paměti)",
                "UQ · velmi velké · delší strana 5120 px (hodně paměti)",
                "FQ · plné rozlišení (jen pro 3 snímky, nejvíc paměti)"});
        size.setSelection(3); root.addView(size);
        align = new CheckBox(this); align.setText("Automaticky zarovnat a oříznout černé okraje"); align.setChecked(true); root.addView(align);
        run = button("Sloučit", root); run.setOnClickListener(view -> process(false));
        cancel = button("Zrušit slučování", root); cancel.setVisibility(View.GONE);
        cancel.setOnClickListener(view -> cancelMerge());
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true); progress.setVisibility(View.GONE); root.addView(progress);
        status = label(selected.isEmpty() ? "Zatím nevybrány žádné fotky · zpracování offline · nic se nenahrává"
                : "Obnoveno " + selected.size() + " fotek, spusťte slučování znovu", 13);
        root.addView(status);
        buildAdjustPanel(root);
        preview = new ImageView(this); preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription("Náhled fotky");
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = new LinearLayout(this);
        compare = button("Originál", actions); compare.setEnabled(false);
        compare.setOnClickListener(view -> {
            showingOriginal = !showingOriginal;
            preview.setImageBitmap(showingOriginal ? original : displayed());
            compare.setText(showingOriginal ? "Výsledek" : "Originál");
        });
        adjustToggle = button("Úpravy", actions);
        adjustToggle.setOnClickListener(view -> {
            boolean show = adjustPanel.getVisibility() != View.VISIBLE;
            adjustPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            adjustToggle.setText(show ? "Skrýt úpravy" : "Úpravy");
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

    // ---------- Kontrola, zda už byla série sloučena ----------

    private String readPermission() {
        return Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_IMAGES"
                : "android.permission.READ_EXTERNAL_STORAGE";
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        // I při odmítnutí se pokračuje – kontrola pak uvidí jen soubory uložené touto instalací.
        if (request == PERMISSION_REQUEST) process(false);
    }

    /** Název výstupu podle vstupních souborů, např. DSC01234-DSC01238_HDR (značka kvality se přidá zvlášť). */
    private String outputName(List<Uri> inputs, boolean hdr) {
        ArrayList<String> names = new ArrayList<>();
        for (Uri uri : inputs) {
            String name = displayName(uri);
            if (name == null || name.isEmpty()) name = "foto";
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            names.add(name.replaceAll("[\\\\/:*?\"<>|]", "_"));
        }
        Collections.sort(names);
        String first = names.get(0), last = names.get(names.size() - 1);
        return (first.equals(last) ? first : first + "-" + last) + (hdr ? "_HDR" : "_OSTROST");
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        return null;
    }

    private boolean existsInGallery(String fileName) {
        try (Cursor cursor = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID},
                MediaStore.Images.Media.DISPLAY_NAME + "=? AND " + MediaStore.Images.Media.RELATIVE_PATH + "=?",
                new String[]{fileName, OUTPUT_FOLDER}, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (Exception ignored) {
            return false;
        }
    }

    // ---------- Slučování ----------

    private void process(boolean confirmedRedo) {
        if (busy) return;
        if (selected.size() < 2) { status.setText("Nejdřív vyberte 2–7 fotek JPG"); return; }
        if (!permissionAsked && checkSelfPermission(readPermission()) != PackageManager.PERMISSION_GRANTED) {
            permissionAsked = true;
            status.setText("Povolte přístup k fotkám, aby aplikace poznala už sloučené série");
            requestPermissions(new String[]{readPermission()}, PERMISSION_REQUEST);
            return;
        }
        final List<Uri> inputs = new ArrayList<>(selected);
        final int edge = EDGES[size.getSelectedItemPosition()];
        final boolean hdr = mode.getSelectedItemPosition() == 0, useAlignment = align.isChecked();
        final String name = outputName(inputs, hdr) + "_" + QUALITY[size.getSelectedItemPosition()];
        if (!confirmedRedo && existsInGallery(name + ".jpg")) {
            new AlertDialog.Builder(this)
                    .setTitle("Už sloučeno")
                    .setMessage("Tahle série už je v galerii jako " + name + ".jpg.\nSloučit ji znovu? Nový soubor dostane číslo, původní zůstane.")
                    .setPositiveButton("Sloučit znovu", (dialog, which) -> process(true))
                    .setNegativeButton("Zrušit", null)
                    .show();
            return;
        }
        clearResult(); setBusy(true); cancel.setVisibility(View.VISIBLE); status.setText("Připravuji…");
        final int gen = ++generation;
        task = worker.submit(() -> {
            Bitmap fused = null, first = null, small = null;
            try {
                fused = Fusion.merge(getContentResolver(), inputs, edge, hdr, useAlignment,
                        message -> runOnUiThread(() -> { if (!isDestroyed() && gen == generation) status.setText(message); }));
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Zpracování zrušeno");
                small = scaledForPreview(fused);
                final Bitmap adjSource = scaledCopy(fused, 1280);
                first = ImageLoader.load(getContentResolver(), inputs.get(0), 1280);
                final Map<String, String> exif = newestExif(inputs);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Zpracování zrušeno");
                final Bitmap output = fused, source = first, display = small;
                runOnUiThread(() -> {
                    if (isDestroyed() || gen != generation) {
                        if (display != output) display.recycle();
                        output.recycle(); source.recycle(); return;
                    }
                    result = output; original = source; shown = display; resultName = name; resultExif = exif;
                    adjustBase = adjSource; adjustedPreview = null;
                    resultInputs.clear(); resultInputs.addAll(inputs);
                    preview.setImageBitmap(shown); cancel.setVisibility(View.GONE); setBusy(false);
                    schedulePreview();
                    status.setText("Hotovo · " + result.getWidth() + " × " + result.getHeight()
                            + " px · " + inputs.size() + " fotek\n"
                            + (hdr ? "Expoziční fúze do JPG (ne HDR DNG)" : "Hloubka ostrosti: u okrajů předmětů může vzniknout svatozář"));
                });
            } catch (Exception | OutOfMemoryError error) {
                if (small != null && small != fused) small.recycle();
                if (fused != null) fused.recycle();
                if (first != null) first.recycle();
                final String message = isMemoryError(error)
                        ? "Došla paměť – zvolte menší velikost výstupu nebo méně fotek"
                        : error.getMessage();
                runOnUiThread(() -> {
                    if (isDestroyed() || gen != generation) return;
                    cancel.setVisibility(View.GONE); setBusy(false);
                    status.setText("Nedokončeno: " + message);
                });
            }
        });
    }

    /** Zruší běžící slučování. Rozpracovaný krok v knihovně OpenCV se dokončí na pozadí a výsledek se zahodí. */
    private void cancelMerge() {
        generation++;
        if (task != null) task.cancel(true);
        task = null;
        cancel.setVisibility(View.GONE); setBusy(false);
        status.setText("Slučování zrušeno");
    }

    /** Načte EXIF ze všech vstupů a vrátí údaje té fotky, která byla pořízena nejpozději. */
    private Map<String, String> newestExif(List<Uri> inputs) {
        Map<String, String> best = new HashMap<>();
        String bestDate = null;
        for (Uri uri : inputs) {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) continue;
                ExifInterface exif = new ExifInterface(input);
                String date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                if (date == null) date = exif.getAttribute(ExifInterface.TAG_DATETIME);
                if (date == null) continue;
                String subsec = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL);
                // Formát „yyyy:MM:dd HH:mm:ss“ jde porovnávat jako text.
                String key = date + "." + (subsec != null ? subsec : "");
                if (bestDate == null || key.compareTo(bestDate) > 0) {
                    bestDate = key;
                    best = new HashMap<>();
                    for (String tag : COPY_TAGS) {
                        String value = exif.getAttribute(tag);
                        if (value != null) best.put(tag, value);
                    }
                }
            } catch (Exception ignored) { }
        }
        return best;
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

    // ---------- Uložení a mazání originálů ----------

    private void saveResult() {
        if (busy || result == null) return;
        final Bitmap image = result;
        final String fileName = (resultName != null ? resultName : "LumiFuse_" + System.currentTimeMillis()) + ".jpg";
        final List<Uri> toTrash = new ArrayList<>(resultInputs);
        final Map<String, String> exif = resultExif != null ? new HashMap<>(resultExif) : new HashMap<>();
        final Adjust.Params params = currentParams();
        final boolean adjusted = !params.isNeutral();
        setBusy(true); status.setText(adjusted ? "Upravuji a ukládám…" : "Ukládám…");
        worker.execute(() -> {
            Uri uri = null;
            Bitmap toSave = image;
            try {
                if (adjusted) toSave = Adjust.apply(image, params, 1.0);
                ContentValues values = new ContentValues();
                // Pokud už soubor stejného jména existuje, systém novému přidá číslo – nic se nepřepíše.
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, OUTPUT_FOLDER);
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("V galerii se nepodařilo vytvořit soubor");
                try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null || !toSave.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                        throw new IOException("Zápis JPG selhal");
                    }
                }
                boolean dateCopied = false;
                if (!exif.isEmpty()) {
                    // Datum a údaje o fotoaparátu z nejnovější vstupní fotky – galerie výsledek zařadí jako nejnovější ze série.
                    try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "rw")) {
                        if (fd != null) {
                            ExifInterface out = new ExifInterface(fd.getFileDescriptor());
                            for (Map.Entry<String, String> entry : exif.entrySet()) out.setAttribute(entry.getKey(), entry.getValue());
                            out.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
                            out.setAttribute(ExifInterface.TAG_SOFTWARE, "LumiFuse");
                            out.saveAttributes();
                            dateCopied = exif.containsKey(ExifInterface.TAG_DATETIME_ORIGINAL) || exif.containsKey(ExifInterface.TAG_DATETIME);
                        }
                    } catch (Exception ignored) { /* Bez EXIF se fotka uloží i tak, jen s dnešním datem. */ }
                }
                final String dateNote = dateCopied ? " · datum podle nejnovější fotky" : " · datum se nepodařilo přenést";
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (getContentResolver().update(uri, values, null, null) != 1) throw new IOException("Zveřejnění v galerii selhalo");
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    setBusy(false);
                    status.setText("Uloženo do galerie · " + OUTPUT_FOLDER + fileName + dateNote + (adjusted ? " · s úpravami" : "") + " · originály beze změny");
                    if (!toTrash.isEmpty()) askTrash(toTrash);
                });
            } catch (Exception | OutOfMemoryError error) {
                // Smaže jen prázdný soubor vytvořený teď, nikdy vstupní fotky.
                if (uri != null) {
                    try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
                }
                final String message = "Uložení selhalo: " + (isMemoryError(error)
                        ? "došla paměť při úpravách – zkuste menší velikost výstupu" : error.getMessage());
                runOnUiThread(() -> { if (!isDestroyed()) { setBusy(false); status.setText(message); } });
            } finally {
                if (toSave != image) toSave.recycle();
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
                if (isMediaItem(uri)) mediaUri = uri;
                // Odkazy z výběru fotek Androidu (content://media/picker/…) nejsou položky galerie – ty se dohledají níž.
                else if (!MediaStore.AUTHORITY.equals(uri.getAuthority())) mediaUri = MediaStore.getMediaUri(this, uri);
            } catch (Exception ignored) { }
            // Odkaz typu …/file/ID systém ke smazání nepřijme („must be Media items“) – převést na …/images/media/ID.
            mediaUri = toImageItem(mediaUri);
            // Záloha pro výběr přes Galerii MIUI apod.: dohledat stejnou fotku v galerii podle jména a velikosti.
            if (mediaUri == null) mediaUri = findInMediaStore(uri);
            if (mediaUri != null && !media.contains(mediaUri)) media.add(mediaUri);
            else if (mediaUri == null) skipped++;
        }
        trashSkipped = skipped;
        pendingTrash.clear(); pendingTrash.addAll(media);
        if (media.isEmpty()) {
            status.setText("Uloženo do galerie · originály se nepodařilo v galerii najít, smažte je ručně"
                    + (checkSelfPermission(readPermission()) != PackageManager.PERMISSION_GRANTED ? " (aplikace nemá povolený přístup k fotkám)" : ""));
            return;
        }
        try {
            PendingIntent request = MediaStore.createTrashRequest(getContentResolver(), media, true);
            startIntentSenderForResult(request.getIntentSender(), TRASH_REQUEST, null, 0, 0, 0);
        } catch (Exception trashError) {
            // Některé systémy koš nepodporují – pak trvalé smazání, opět se systémovým potvrzením.
            try {
                PendingIntent request = MediaStore.createDeleteRequest(getContentResolver(), media);
                startIntentSenderForResult(request.getIntentSender(), TRASH_REQUEST, null, 0, 0, 0);
            } catch (Exception deleteError) {
                status.setText("Uloženo do galerie · smazání originálů selhalo: " + deleteError.getMessage()
                        + "\n(" + media.get(0) + ")");
            }
        }
    }

    /** Je to přímý odkaz na položku galerie, např. content://media/external_primary/images/media/123? */
    private static boolean isMediaItem(Uri uri) {
        if (uri == null || !MediaStore.AUTHORITY.equals(uri.getAuthority())) return false;
        List<String> segments = uri.getPathSegments();
        String last = segments.isEmpty() ? "" : segments.get(segments.size() - 1);
        if (!last.matches("\\d+")) return false;
        if (segments.size() == 4 && "media".equals(segments.get(2))) {
            String type = segments.get(1);
            return "images".equals(type) || "video".equals(type) || "audio".equals(type);
        }
        return false;
    }

    /** Vrátí odkaz ve tvaru …/images/media/ID (nebo jiný přímý odkaz na médium), nebo null. */
    private Uri toImageItem(Uri uri) {
        if (uri == null || !MediaStore.AUTHORITY.equals(uri.getAuthority())) return null;
        if (isMediaItem(uri)) return uri;
        List<String> segments = uri.getPathSegments();
        if (segments.size() == 3 && "file".equals(segments.get(1)) && segments.get(2).matches("\\d+")) {
            Uri image = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(segments.get(0)),
                    Long.parseLong(segments.get(2)));
            try (Cursor cursor = getContentResolver().query(image,
                    new String[]{MediaStore.Images.Media._ID}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) return image;
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** Najde v galerii (MediaStore) fotku se stejným jménem a velikostí jako vybraný soubor. */
    private Uri findInMediaStore(Uri uri) {
        String name = null; long length = -1;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(0);
                if (!cursor.isNull(1)) length = cursor.getLong(1);
            }
        } catch (Exception ignored) { }
        if (name == null) return null;
        String selection = MediaStore.Images.Media.DISPLAY_NAME + "=?" + (length > 0 ? " AND " + MediaStore.Images.Media.SIZE + "=?" : "");
        String[] args = length > 0 ? new String[]{name, String.valueOf(length)} : new String[]{name};
        try (Cursor cursor = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID}, selection, args, null)) {
            // Jen jednoznačná shoda – při víc stejných souborech radši nic nemazat.
            if (cursor != null && cursor.getCount() == 1 && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0));
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Po odpovědi systému se ověří, kolik fotek je opravdu v koši – výsledek se vypíše do stavového řádku. */
    private void onTrashResult(int code) {
        int total = pendingTrash.size(), gone = 0;
        for (Uri uri : pendingTrash) if (isTrashedOrGone(uri)) gone++;
        pendingTrash.clear();
        String skippedNote = trashSkipped > 0 ? " · " + trashSkipped + " se nepodařilo najít, smažte je ručně" : "";
        if (gone == total && total > 0) {
            selected.clear(); resultInputs.clear();
            status.setText("Uloženo do galerie · " + gone + " původních fotek přesunuto do koše" + skippedNote);
        } else if (code == RESULT_OK) {
            status.setText("Uloženo · systém přesun potvrdil, ale v koši je jen " + gone + " z " + total
                    + " fotek. Ostatní zůstaly." + skippedNote);
        } else {
            status.setText("Uloženo · systém přesun do koše nepotvrdil (odpověď " + code + "), v koši "
                    + gone + " z " + total + " fotek." + skippedNote);
        }
    }

    private boolean isTrashedOrGone(Uri uri) {
        Bundle args = new Bundle();
        args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.IS_TRASHED}, args, null)) {
            if (cursor == null || !cursor.moveToFirst()) return true;
            return cursor.getInt(0) == 1;
        } catch (Exception error) {
            return false;
        }
    }

    // ---------- Panel úprav ----------

    private void buildAdjustPanel(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences("upravy", MODE_PRIVATE);
        adjustPanel = new ScrollView(this);
        adjustPanel.setVisibility(View.GONE);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < ADJ_NAMES.length; i++) {
            final int index = i;
            adj[i] = prefs.getInt("a" + i, 0);
            adjLabels[i] = label("", 13);
            list.addView(adjLabels[i]);
            SeekBar bar = new SeekBar(this);
            bar.setMax(ADJ_BIPOLAR[i] ? 200 : 100);
            bar.setProgress(ADJ_BIPOLAR[i] ? adj[i] + 100 : adj[i]);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    adj[index] = ADJ_BIPOLAR[index] ? progress - 100 : progress;
                    updateAdjustLabel(index);
                    if (fromUser) { saveAdjustments(); schedulePreview(); }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) { }
                @Override public void onStopTrackingTouch(SeekBar seekBar) { }
            });
            adjBars[i] = bar;
            list.addView(bar);
            updateAdjustLabel(i);
        }
        Button reset = button("Vynulovat úpravy", list);
        reset.setOnClickListener(view -> {
            for (int i = 0; i < ADJ_NAMES.length; i++) {
                adj[i] = 0;
                adjBars[i].setProgress(ADJ_BIPOLAR[i] ? 100 : 0);
            }
            saveAdjustments(); schedulePreview();
        });
        adjustPanel.addView(list);
        root.addView(adjustPanel, new LinearLayout.LayoutParams(-1, dp(240)));
    }

    private void updateAdjustLabel(int i) {
        String value = ADJ_BIPOLAR[i] && adj[i] > 0 ? "+" + adj[i] : String.valueOf(adj[i]);
        adjLabels[i].setText(ADJ_NAMES[i] + ": " + value);
    }

    private void saveAdjustments() {
        SharedPreferences.Editor editor = getSharedPreferences("upravy", MODE_PRIVATE).edit();
        for (int i = 0; i < adj.length; i++) editor.putInt("a" + i, adj[i]);
        editor.apply();
    }

    /** Převod posuvníků na parametry úprav. */
    private Adjust.Params currentParams() {
        Adjust.Params p = new Adjust.Params();
        p.compression = adj[0] / 100f * 0.7f;
        p.shadows = adj[1] / 100f;
        p.highlights = adj[2] / 100f;
        p.exposure = adj[3] / 100f * 1.5f;
        p.contrast = adj[4] / 100f * 0.5f;
        p.saturation = adj[5] / 100f * 0.8f;
        p.warmth = adj[6] / 100f;
        p.sharpness = adj[7] / 100f * 1.5f;
        return p;
    }

    private Bitmap displayed() { return adjustedPreview != null ? adjustedPreview : shown; }

    private void schedulePreview() {
        handler.removeCallbacks(previewRunnable);
        handler.postDelayed(previewRunnable, 150);
    }

    /** Přepočítá náhled úprav na pozadí; zastaralé výsledky (posuvník se mezitím pohnul) se zahodí. */
    private void renderPreview() {
        final Bitmap base = adjustBase;
        if (base == null || result == null) return;
        final Adjust.Params p = currentParams();
        final int seq = ++previewSeq;
        if (p.isNeutral()) {
            adjustedPreview = null;
            if (!showingOriginal) preview.setImageBitmap(shown);
            return;
        }
        final double scale = (double) Math.max(base.getWidth(), base.getHeight())
                / Math.max(result.getWidth(), result.getHeight());
        previewWorker.execute(() -> {
            try {
                final Bitmap out = Adjust.apply(base, p, scale);
                runOnUiThread(() -> {
                    if (isDestroyed() || seq != previewSeq || base != adjustBase) return;
                    adjustedPreview = out;
                    if (!showingOriginal) preview.setImageBitmap(adjustedPreview);
                });
            } catch (Exception | OutOfMemoryError ignored) { }
        });
    }

    private static Bitmap scaledCopy(Bitmap source, int edge) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= edge) return source.copy(Bitmap.Config.ARGB_8888, false);
        float scale = (float) edge / longest;
        return Bitmap.createScaledBitmap(source,
                Math.max(1, Math.round(source.getWidth() * scale)),
                Math.max(1, Math.round(source.getHeight() * scale)), true);
    }

    // ---------- Pomocné ----------

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
        result = null; original = null; shown = null; resultName = null; resultExif = null; showingOriginal = false;
        adjustBase = null; adjustedPreview = null; previewSeq++;
        resultInputs.clear();
        save.setEnabled(false); compare.setEnabled(false); compare.setText("Originál");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        ArrayList<String> values = new ArrayList<>();
        for (Uri uri : selected) values.add(uri.toString());
        state.putStringArrayList("selected", values); super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        generation++;
        worker.shutdownNow();
        previewWorker.shutdownNow();
        handler.removeCallbacks(previewRunnable);
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
