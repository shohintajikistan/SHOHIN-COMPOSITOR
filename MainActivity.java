
package com.shohin.compressor;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.content.Intent;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.content.ContentValues;
import android.provider.MediaStore;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;
import android.media.MediaMetadataRetriever;

import androidx.media3.common.MediaItem;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ExportException;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    WebView web;
    Transformer transformer;
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Uri selectedUri;
    File outputFile;
    String outputPath = "";
    int targetHeight = 1080;
    int quality = 70;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Shohin");

        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    public class Bridge {

        @JavascriptInterface
        public void selectVideo() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("video/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, 100);
            });
        }

        @JavascriptInterface
        public void compress(int height, int qualityValue) {
            targetHeight = height;
            quality = qualityValue;

            if (selectedUri == null) {
                send("error", "Сначала выберите видео");
                return;
            }

            runOnUiThread(() -> startCompression());
        }

        @JavascriptInterface
        public void saveVideo() {
            if (outputFile == null || !outputFile.exists()) {
                send("error", "Готовое видео не найдено");
                return;
            }

            executor.execute(() -> {
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Video.Media.DISPLAY_NAME,
                            "SHOHIN_" + System.currentTimeMillis() + ".mp4");
                    values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");

                    if (Build.VERSION.SDK_INT >= 29) {
                        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                                Environment.DIRECTORY_MOVIES + "/SHOHIN");
                    }

                    Uri destination = getContentResolver().insert(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

                    if (destination == null) throw new IOException("Не удалось сохранить файл");

                    try (InputStream in = new java.io.FileInputStream(outputFile);
                         OutputStream out = getContentResolver().openOutputStream(destination)) {

                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    }

                    send("saved", "Видео сохранено в галерею");

                } catch (Exception e) {
                    send("error", e.getMessage());
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            selectedUri = data.getData();

            try {
                getContentResolver().takePersistableUriPermission(
                        selectedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}

            String name = getFileName(selectedUri);
            send("selected", name);
        }
    }

    String getFileName(Uri uri) {
        String name = "video";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        } catch (Exception ignored) {}
        return name;
    }

    void startCompression() {
        try {
            File input = new File(getCacheDir(), "shohin_input");
            try (InputStream in = getContentResolver().openInputStream(selectedUri);
                 FileOutputStream out = new FileOutputStream(input)) {
                if (in == null) throw new IOException("Не удалось открыть видео");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }

            outputFile = new File(getCacheDir(),
                    "SHOHIN_" + System.currentTimeMillis() + ".mp4");

            MediaItem item = MediaItem.fromUri(Uri.fromFile(input));
            EditedMediaItem edited = new EditedMediaItem.Builder(item).build();

            Transformer.Listener listener = new Transformer.Listener() {
                @Override
                public void onCompleted(Composition composition, ExportResult result) {
                    send("complete", outputFile.getAbsolutePath());
                }

                @Override
                public void onError(Composition composition, ExportResult result,
                                    ExportException exception) {
                    send("error", "Ошибка обработки: " + exception.getMessage());
                }
            };

            transformer = new Transformer.Builder(this)
                    .setListener(listener)
                    .build();

            transformer.start(edited, outputFile.getAbsolutePath());

        } catch (Exception e) {
            send("error", e.getMessage());
        }
    }

    void send(String event, String value) {
        String safe = value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", " ");

        runOnUiThread(() -> web.evaluateJavascript(
                "window.onShohinEvent && window.onShohinEvent('" +
                        event + "','" + safe + "')", null));
    }

    @Override
    protected void onDestroy() {
        if (transformer != null) transformer.cancel();
        executor.shutdown();
        super.onDestroy();
    }
}
