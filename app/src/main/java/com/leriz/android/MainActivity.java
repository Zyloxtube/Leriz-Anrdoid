package com.leriz.android;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.MediaStore;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.JavascriptInterface;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.util.Map;
import java.util.HashMap;
import android.view.View;
import android.graphics.Color;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_CHOOSER_REQUEST = 7021;
    private static final int CAMERA_CAPTURE_REQUEST = 7022;
    private static final int CAMERA_PERMISSION_REQUEST = 7023;
    private Uri pendingCaptureUri;


    private static final String API_BASE = "https://subhyaloid-kallie-bihourly.ngrok-free.dev";

    public class LerizBridge {
        @JavascriptInterface
        public String getSavedToken() {
            return getSharedPreferences("leriz_auth", MODE_PRIVATE).getString("token", "");
        }

        @JavascriptInterface
        public void saveAuthToken(String token) {
            getSharedPreferences("leriz_auth", MODE_PRIVATE).edit()
                .putString("token", token == null ? "" : token).apply();
        }

        @JavascriptInterface
        public void clearAuthToken() {
            getSharedPreferences("leriz_auth", MODE_PRIVATE).edit().remove("token").apply();
        }

        @JavascriptInterface
        public void request(String method, String path, String body, String contentType, String token, String callbackId) {
            new Thread(() -> {
                int status = 0;
                String response = "{}";
                HttpURLConnection connection = null;
                try {
                    URL url = new URL(API_BASE + (path.startsWith("/") ? path : "/" + path));
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod(method);
                    connection.setConnectTimeout(20000);
                    connection.setReadTimeout(30000);
                    connection.setRequestProperty("ngrok-skip-browser-warning", "true");
                    connection.setRequestProperty("Accept", "application/json");
                    if (contentType != null && !contentType.isEmpty()) connection.setRequestProperty("Content-Type", contentType);
                    if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
                    if (body != null && !body.isEmpty() && !method.equals("GET")) {
                        connection.setDoOutput(true);
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        connection.setFixedLengthStreamingMode(bytes.length);
                        try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
                    }
                    status = connection.getResponseCode();
                    InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    if (input != null) {
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        byte[] chunk = new byte[8192];
                        int n;
                        while ((n = input.read(chunk)) != -1) buffer.write(chunk, 0, n);
                        response = buffer.toString("UTF-8");
                        input.close();
                    }
                } catch (Exception e) {
                    try { response = new JSONObject().put("error", e.getMessage() == null ? "Network error" : e.getMessage()).toString(); }
                    catch (Exception ignored) {}
                } finally {
                    if (connection != null) connection.disconnect();
                }
                final int finalStatus = status;
                final String finalResponse = response;
                runOnUiThread(() -> {
                    if (webView != null) {
                        String escaped = JSONObject.quote(finalResponse);
                        webView.evaluateJavascript("window.nativeResponse && window.nativeResponse(" + JSONObject.quote(callbackId) + "," + finalStatus + "," + escaped + ")", null);
                    }
                });
            }).start();
        }

        @JavascriptInterface
        public void uploadVideo(String caption, String filename, String base64Data, String token, String callbackId) {
            new Thread(() -> {
                int status = 0;
                String response = "{}";
                HttpURLConnection connection = null;
                String boundary = "LerizAndroidBoundary" + System.currentTimeMillis();
                try {
                    URL url = new URL(API_BASE + "/api/videos");
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(30000);
                    connection.setReadTimeout(120000);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("ngrok-skip-browser-warning", "true");
                    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                    if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
                    byte[] video = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n" + caption + "\r\n").getBytes(StandardCharsets.UTF_8));
                        String safeFilename = filename == null ? "leriz-upload.mp4" : filename.replace("\"", "").replace("\r", "").replace("\n", "");
                        String lowerFilename = safeFilename.toLowerCase(java.util.Locale.ROOT);
                        String videoMime = lowerFilename.endsWith(".webm") ? "video/webm" : lowerFilename.endsWith(".mov") ? "video/quicktime" : "video/mp4";
                        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"video\"; filename=\"" + safeFilename + "\"\r\nContent-Type: " + videoMime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        out.write(video);
                        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                    }
                    status = connection.getResponseCode();
                    InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    if (input != null) {
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        byte[] chunk = new byte[8192]; int n;
                        while ((n = input.read(chunk)) != -1) buffer.write(chunk, 0, n);
                        response = buffer.toString("UTF-8"); input.close();
                    }
                } catch (Exception e) {
                    try { response = new JSONObject().put("error", e.getMessage() == null ? "Upload failed" : e.getMessage()).toString(); } catch (Exception ignored) {}
                } finally { if (connection != null) connection.disconnect(); }
                final int finalStatus = status; final String finalResponse = response;
                runOnUiThread(() -> {
                    if (webView != null) webView.evaluateJavascript("window.nativeResponse && window.nativeResponse(" + JSONObject.quote(callbackId) + "," + finalStatus + "," + JSONObject.quote(finalResponse) + ")", null);
                });
            }).start();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(5, 5, 8));
        getWindow().setNavigationBarColor(Color.rgb(5, 5, 8));
        getWindow().getDecorView().setSystemUiVisibility(0);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(5, 5, 8));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        webView.addJavascriptInterface(new LerizBridge(), "LerizNative");
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) ||
                    !"subhyaloid-kallie-bihourly.ngrok-free.dev".equalsIgnoreCase(uri.getHost()) ||
                    !"GET".equalsIgnoreCase(request.getMethod())) {
                    return super.shouldInterceptRequest(view, request);
                }
                HttpURLConnection mediaConnection = null;
                try {
                    mediaConnection = (HttpURLConnection) new URL(uri.toString()).openConnection();
                    mediaConnection.setInstanceFollowRedirects(true);
                    mediaConnection.setConnectTimeout(20000);
                    mediaConnection.setReadTimeout(60000);
                    mediaConnection.setRequestProperty("ngrok-skip-browser-warning", "true");
                    mediaConnection.setRequestProperty("Accept", "*/*");
                    String range = request.getRequestHeaders().get("Range");
                    if (range != null) mediaConnection.setRequestProperty("Range", range);
                    String ua = request.getRequestHeaders().get("User-Agent");
                    if (ua != null) mediaConnection.setRequestProperty("User-Agent", ua);
                    int status = mediaConnection.getResponseCode();
                    InputStream stream = status >= 400 ? mediaConnection.getErrorStream() : mediaConnection.getInputStream();
                    Map<String, String> headers = new HashMap<>();
                    for (Map.Entry<String, java.util.List<String>> entry : mediaConnection.getHeaderFields().entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty())
                            headers.put(entry.getKey(), String.join(", ", entry.getValue()));
                    }
                    headers.put("Access-Control-Allow-Origin", "*");
                    String mime = mediaConnection.getContentType();
                    if (mime == null) mime = URLConnection.guessContentTypeFromName(uri.getLastPathSegment());
                    if (mime == null) mime = "application/octet-stream";
                    String reason = status == 206 ? "Partial Content" : status >= 400 ? "Error" : "OK";
                    return new WebResourceResponse(mime, mediaConnection.getContentEncoding(), status, reason, headers, stream);
                } catch (Exception ex) {
                    if (mediaConnection != null) mediaConnection.disconnect();
                    return super.shouldInterceptRequest(view, request);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                boolean wantsVideoCapture = params.isCaptureEnabled();
                if (wantsVideoCapture) {
                    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, CAMERA_PERMISSION_REQUEST);
                    } else {
                        startVideoCapture();
                    }
                    return true;
                }
                Intent intent = params.createIntent();
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    if (fileCallback != null) fileCallback.onReceiveValue(null);
                    fileCallback = null;
                    return false;
                }
            }
        });
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void startVideoCapture() {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "Leriz-" + System.currentTimeMillis() + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Leriz");
            pendingCaptureUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (pendingCaptureUri == null) throw new IllegalStateException("Could not create video output");
            Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCaptureUri);
            intent.putExtra("android.intent.extra.durationLimit", 180);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.setClipData(android.content.ClipData.newRawUri("Leriz video", pendingCaptureUri));
            startActivityForResult(intent, CAMERA_CAPTURE_REQUEST);
        } catch (Exception e) {
            if (pendingCaptureUri != null) {
                try { getContentResolver().delete(pendingCaptureUri, null, null); } catch (Exception ignored) {}
            }
            pendingCaptureUri = null;
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = null;
            if (webView != null) webView.evaluateJavascript("window.toast && toast('Could not open camera. Check camera permissions.')", null);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            // Check the actual current permission state instead of assuming Android
            // always returns both permission results in a particular order/length.
            boolean granted =
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            if (granted) startVideoCapture();
            else {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = null;
                if (webView != null) webView.evaluateJavascript("window.toast && toast('Camera and microphone permissions are needed to record video.')", null);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CAMERA_CAPTURE_REQUEST) {
            // Some camera apps return their own URI despite EXTRA_OUTPUT. Prefer that
            // URI when supplied, otherwise use the MediaStore destination we created.
            Uri captured = null;
            if (resultCode == RESULT_OK) {
                captured = data != null && data.getData() != null ? data.getData() : pendingCaptureUri;
            }
            if (resultCode != RESULT_OK && pendingCaptureUri != null) {
                try { getContentResolver().delete(pendingCaptureUri, null, null); } catch (Exception ignored) {}
            } else if (captured != null && pendingCaptureUri != null && !captured.equals(pendingCaptureUri)) {
                try { getContentResolver().delete(pendingCaptureUri, null, null); } catch (Exception ignored) {}
            }
            pendingCaptureUri = null;
            if (fileCallback != null) {
                fileCallback.onReceiveValue(captured == null ? null : new Uri[]{captured});
                fileCallback = null;
            }
        } else if (requestCode == FILE_CHOOSER_REQUEST && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    result = new Uri[count];
                    for (int i = 0; i < count; i++) result[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
