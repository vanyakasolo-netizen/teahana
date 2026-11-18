package com.example.voicesnpc;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.PermissionRequest;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int REQ_MIC = 4101;
    private static final int REQ_OPEN_FILE = 4102;
    private static final int REQ_SAVE_FILE = 4103;
    private static final String APP_ORIGIN = "https://appassets.androidplatform.net";
    private static final String AUDIO_CAPTURE = PermissionRequest.RESOURCE_AUDIO_CAPTURE;

    private WebView webView;
    private PermissionRequest pendingWebPermission;
    private boolean micAskInFlight = false;
    private ValueCallback<Uri[]> pendingFileCallback;
    private byte[] pendingSaveBytes;
    private String pendingSaveMime = "application/octet-stream";
    private String pendingSaveName = "backup.json";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(14, 12, 16));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(14, 12, 16));

        WebView.setWebContentsDebuggingEnabled(false);
        webView = new WebView(this);
        setContentView(webView);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) askMicPermission();

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // The app is intentionally self-contained and offline.
                return !APP_ORIGIN.equals(request.getUrl().getScheme() + "://" + request.getUrl().getHost());
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handleWebPermission(request));
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingWebPermission == request) pendingWebPermission = null;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
                pendingFileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try {
                    startActivityForResult(intent, REQ_OPEN_FILE);
                    return true;
                } catch (Exception e) {
                    pendingFileCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "Не удалось открыть выбор файла", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        webView.loadUrl(APP_ORIGIN + "/assets/index.html");
    }

    private void handleWebPermission(PermissionRequest request) {
        Uri origin = request.getOrigin();
        boolean trustedOrigin = origin != null
                && "https".equals(origin.getScheme())
                && "appassets.androidplatform.net".equals(origin.getHost());
        String[] requested = request.getResources();
        boolean asksOnlyForAudio = requested != null && requested.length == 1
                && AUDIO_CAPTURE.equals(requested[0]);
        if (!trustedOrigin || !asksOnlyForAudio) {
            request.deny();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{AUDIO_CAPTURE});
        } else {
            if (pendingWebPermission != null) pendingWebPermission.deny();
            pendingWebPermission = request;
            askMicPermission();
        }
    }

    private void askMicPermission() {
        if (micAskInFlight) return;
        micAskInFlight = true;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) micAskInFlight = false;
        if (requestCode == REQ_MIC && pendingWebPermission != null) {
            PermissionRequest request = pendingWebPermission;
            pendingWebPermission = null;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                request.grant(new String[]{AUDIO_CAPTURE});
            } else {
                request.deny();
                Toast.makeText(this, "Без разрешения микрофона голосовые функции не заработают", Toast.LENGTH_LONG).show();
            }
        }
    }

    private class AndroidBridge {
        @JavascriptInterface
        public void saveFile(String name, String mime, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                runOnUiThread(() -> {
                    pendingSaveBytes = bytes;
                    pendingSaveMime = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
                    pendingSaveName = (name == null || name.isEmpty()) ? "export.bin" : name;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(pendingSaveMime);
                    intent.putExtra(Intent.EXTRA_TITLE, pendingSaveName);
                    startActivityForResult(intent, REQ_SAVE_FILE);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Не удалось подготовить файл", Toast.LENGTH_SHORT).show());
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN_FILE && pendingFileCallback != null) {
            Uri[] result = resultCode == RESULT_OK && data != null && data.getData() != null
                    ? new Uri[]{data.getData()} : null;
            pendingFileCallback.onReceiveValue(result);
            pendingFileCallback = null;
        } else if (requestCode == REQ_SAVE_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingSaveBytes != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out != null) out.write(pendingSaveBytes);
                    Toast.makeText(this, "Резервная копия сохранена", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Ошибка сохранения файла", Toast.LENGTH_SHORT).show();
                }
            }
            pendingSaveBytes = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (pendingWebPermission != null) pendingWebPermission.deny();
        if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.removeJavascriptInterface("AndroidBridge");
            webView.destroy();
        }
        super.onDestroy();
    }
}
