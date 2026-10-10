package com.leriz.android;

import android.app.Activity;
import android.content.Intent;
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


    private static final String API_BASE = "https://subhyaloid-kallie-bihourly.ngrok-free.dev";

    public class LerizBridge {
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
                        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"video\"; filename=\"" + filename.replace("\"", "") + "\"\r\nContent-Type: video/mp4\r\n\r\n").getBytes(StandardCharsets.UTF_8));
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
                Intent intent = params.createIntent();
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }
        });
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileCallback != null) {
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
