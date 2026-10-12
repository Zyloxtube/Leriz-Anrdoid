package com.leriz.android;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.database.Cursor;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.json.JSONArray;
import android.webkit.WebChromeClient;
import android.webkit.PermissionRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import android.view.View;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.graphics.Color;
import android.media.AudioManager;
import java.util.ArrayList;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_CHOOSER_REQUEST = 7021;
    private static final int CAMERA_CAPTURE_REQUEST = 7022;
    private static final int CAMERA_PERMISSION_REQUEST = 7023;
    private Uri pendingCaptureUri;
    private String pendingCameraPermissionCallback;

    private final AudioManager.OnAudioFocusChangeListener recorderAudioFocusListener = focusChange -> {};
    private int recorderPreviousAudioMode = AudioManager.MODE_NORMAL;
    private boolean recorderAudioModeCaptured = false;
    private boolean recorderAudioFocusHeld = false;
    private final Object pickedVideoLock = new Object();
    private Uri latestPickedVideoUri;
    private String latestPickedVideoName;


    private FrameLayout rootContainer;
    private TextureView nativeVideoTexture;
    private ExoPlayer nativeFeedPlayer;
    private DefaultPreloadManager.Builder nativePreloadBuilder;
    private DefaultPreloadManager nativePreloadManager;
    private SimpleCache nativeMediaCache;
    private final ArrayList<NativeFeedItem> nativeFeedItems = new ArrayList<>();
    private final LinkedHashMap<String, NativeFeedItem> nativeFeedById = new LinkedHashMap<>();
    private final Set<String> nativePreloadedIds = new HashSet<>();
    private final Map<String, Long> nativeFeedPositions = new HashMap<>();
    private int currentNativeFeedIndex = 0;
    private String currentNativeFeedId = "";
    private String activeNativePlaybackUrl = "";
    private boolean nativeUsingFallback = false;
    private boolean nativeFeedVisible = false;
    private boolean nativeWasPlayingBeforePause = false;
    private boolean nativeLowMemory = false;

    private static final class NativeFeedItem {
        final String id;
        final String url;
        final String fallbackUrl;
        final int index;
        final MediaItem mediaItem;

        NativeFeedItem(String id, String url, String fallbackUrl, int index) {
            this.id = id;
            this.url = url;
            this.fallbackUrl = fallbackUrl == null ? "" : fallbackUrl;
            this.index = index;
            String lower = url == null ? "" : url.toLowerCase();
            String mime = lower.contains(".m3u8") ? MimeTypes.APPLICATION_M3U8 : MimeTypes.VIDEO_MP4;
            this.mediaItem = new MediaItem.Builder()
                .setMediaId(id)
                .setUri(url)
                .setMimeType(mime)
                .build();
        }
    }

    private static String API_BASE = "https://bce23aa947fb67.lhr.life";

    private void loadAppSettings() {
        try (InputStream input = getAssets().open("settings.json");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            JSONObject settings = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            String configuredUrl = settings.optString("serverUrl", "").trim();
            if (configuredUrl.startsWith("https://") || configuredUrl.startsWith("http://")) {
                API_BASE = configuredUrl.replaceAll("/+$", "");
            }
        } catch (Exception e) {
            android.util.Log.w("LerizConfig", "Could not load settings.json; using default server URL.", e);
        }
    }

    private static String apiHost() {
        try {
            return new URL(API_BASE).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    public class LerizBridge {
        @JavascriptInterface
        public void openARFaceFilters() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(MainActivity.this, ARFaceActivity.class));
                } catch (Exception e) {
                    android.util.Log.e("LerizAR", "Could not open AR face filters", e);
                    if (webView != null) webView.evaluateJavascript("window.toast && toast('Could not open native AR filters')", null);
                }
            });
        }

        @JavascriptInterface
        public boolean acquireRecorderAudioFocus() {
            return acquireRecorderAudioFocusInternal();
        }

        @JavascriptInterface
        public void releaseRecorderAudioFocus() {
            releaseRecorderAudioFocusInternal();
        }

        @JavascriptInterface
        public String getServerUrl() {
            return API_BASE;
        }

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
        public boolean isNativeFeedPlaybackAvailable() {
            return nativeFeedPlayer != null && nativePreloadManager != null && nativeVideoTexture != null;
        }

        @JavascriptInterface
        public void setNativeFeedItems(String json) {
            runOnUiThread(() -> updateNativeFeedItems(json));
        }

        @JavascriptInterface
        public void playNativeFeedVideo(String id, int index, String url, String fallbackUrl) {
            runOnUiThread(() -> startNativeFeedVideo(id, index, url, fallbackUrl));
        }

        @JavascriptInterface
        public void toggleNativePlayback() {
            runOnUiThread(() -> {
                if (nativeFeedPlayer == null || currentNativeFeedId.isEmpty()) return;
                if (nativeFeedPlayer.getPlayWhenReady()) {
                    nativeFeedPlayer.pause();
                } else {
                    nativeFeedPlayer.play();
                }
            });
        }

        @JavascriptInterface
        public void stopNativePlayback() {
            runOnUiThread(() -> stopNativeFeedPlayback());
        }

        @JavascriptInterface
        public boolean uploadProfileAvatar(String base64, String mimeType, String originalName, String token, String callbackId) {
            if (base64 == null || base64.isEmpty() || token == null || token.isEmpty()) return false;
            final String safeMime = ("image/png".equals(mimeType) || "image/webp".equals(mimeType)) ? mimeType : "image/jpeg";
            final String extension = "image/png".equals(safeMime) ? ".png" : ("image/webp".equals(safeMime) ? ".webp" : ".jpg");
            final String safeToken = token;
            final String encoded = base64;
            final String boundary = "----LerizAvatar" + java.util.UUID.randomUUID().toString().replace("-", "");
            new Thread(() -> {
                HttpURLConnection connection = null;
                int status = 0;
                String response = "{}";
                try {
                    byte[] imageBytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
                    if (imageBytes.length == 0 || imageBytes.length > 5 * 1024 * 1024) throw new IllegalArgumentException("Profile pictures must be 5 MB or smaller");
                    connection = (HttpURLConnection) new URL(API_BASE + "/api/me/avatar").openConnection();
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(20000);
                    connection.setReadTimeout(60000);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("ngrok-skip-browser-warning", "true");
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("Authorization", "Bearer " + safeToken);
                    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                        out.write(("Content-Disposition: form-data; name=\"avatar\"; filename=\"avatar" + extension + "\"\r\n").getBytes(StandardCharsets.UTF_8));
                        out.write(("Content-Type: " + safeMime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        out.write(imageBytes);
                        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
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
                    status = status == 0 ? 500 : status;
                    try { response = new JSONObject().put("ok", false).put("error", e.getMessage() == null ? "Profile picture upload failed" : e.getMessage()).toString(); } catch (Exception ignored) {}
                } finally {
                    if (connection != null) connection.disconnect();
                }
                final int finalStatus = status;
                final String finalResponse = response;
                runOnUiThread(() -> {
                    if (webView != null) webView.evaluateJavascript("window.nativeResponse && window.nativeResponse(" + JSONObject.quote(callbackId) + "," + finalStatus + "," + JSONObject.quote(finalResponse) + ")", null);
                });
            }).start();
            return true;
        }

        @JavascriptInterface
        public void requestCameraPermissions(String callbackId) {
            runOnUiThread(() -> {
                boolean granted =
                    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
                if (granted) {
                    sendCameraPermissionResult(callbackId, true);
                } else {
                    pendingCameraPermissionCallback = callbackId;
                    requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, CAMERA_PERMISSION_REQUEST);
                }
            });
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
        public boolean uploadPickedVideo(String callbackId, String filename, String title, String caption, String token) {
            final Uri sourceUri;
            final String pickedName;
            synchronized (pickedVideoLock) {
                sourceUri = latestPickedVideoUri;
                pickedName = latestPickedVideoName;
                if (sourceUri == null) return false;
                if (filename != null && !filename.isEmpty() && pickedName != null &&
                    !pickedName.equalsIgnoreCase(filename)) return false;
                latestPickedVideoUri = null;
                latestPickedVideoName = null;
            }
            final String safeName = (filename == null || filename.isEmpty())
                ? (pickedName == null || pickedName.isEmpty() ? "leriz-upload.mp4" : pickedName)
                : filename;
            final String safeTitle = title == null ? "" : title;
            final String safeCaption = caption == null ? "" : caption;
            final String safeToken = token == null ? "" : token;
            new Thread(() -> {
                File temp = null;
                try (InputStream input = getContentResolver().openInputStream(sourceUri)) {
                    if (input == null) throw new IllegalStateException("Android could not open the selected video");
                    temp = File.createTempFile("leriz-picked-video-", ".bin", getCacheDir());
                    try (FileOutputStream output = new FileOutputStream(temp)) {
                        byte[] buffer = new byte[64 * 1024];
                        int n;
                        while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                    }
                    if (temp.length() == 0) throw new IllegalStateException("The selected video is empty");
                } catch (Exception e) {
                    if (temp != null) try { temp.delete(); } catch (Exception ignored) {}
                    final String error = e.getMessage() == null ? "Could not read selected video from Android storage" : e.getMessage();
                    runOnUiThread(() -> {
                        if (webView != null) {
                            String payload;
                            try { payload = new JSONObject().put("error", error).toString(); }
                            catch (Exception ignored) { payload = "{}"; }
                            webView.evaluateJavascript("window.nativeResponse && window.nativeResponse(" +
                                JSONObject.quote(callbackId) + ",0," + JSONObject.quote(payload) + ")", null);
                        }
                    });
                    return;
                }
                sendVideoUpload(temp, new String[]{safeName, safeTitle, safeCaption, safeToken}, callbackId);
            }).start();
            return true;
        }

        private final Object uploadLock = new Object();
        private final Map<String, File> uploadFiles = new HashMap<>();
        private final Map<String, FileOutputStream> uploadStreams = new HashMap<>();
        private final Map<String, String[]> uploadMetadata = new HashMap<>();

        @JavascriptInterface
        public boolean beginVideoUpload(String callbackId, String filename, String title, String caption, String token) {
            try {
                File file = File.createTempFile("leriz-upload-", ".bin", getCacheDir());
                FileOutputStream stream = new FileOutputStream(file);
                synchronized (uploadLock) {
                    uploadFiles.put(callbackId, file);
                    uploadStreams.put(callbackId, stream);
                    uploadMetadata.put(callbackId, new String[] {
                        filename == null ? "leriz-upload.mp4" : filename,
                        title == null ? "" : title,
                        caption == null ? "" : caption,
                        token == null ? "" : token
                    });
                }
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public boolean appendVideoUploadChunk(String callbackId, String base64Chunk) {
            try {
                byte[] bytes = android.util.Base64.decode(base64Chunk, android.util.Base64.DEFAULT);
                synchronized (uploadLock) {
                    FileOutputStream stream = uploadStreams.get(callbackId);
                    if (stream == null) return false;
                    stream.write(bytes);
                }
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public boolean finishVideoUpload(String callbackId) {
            final File file;
            final String[] metadata;
            try {
                synchronized (uploadLock) {
                    FileOutputStream stream = uploadStreams.remove(callbackId);
                    file = uploadFiles.remove(callbackId);
                    metadata = uploadMetadata.remove(callbackId);
                    if (stream == null || file == null || metadata == null) {
                        if (stream != null) try { stream.close(); } catch (Exception ignored) {}
                        return false;
                    }
                    stream.close();
                }
                new Thread(() -> sendVideoUpload(file, metadata, callbackId)).start();
                return true;
            } catch (Exception e) { return false; }
        }

        private void sendVideoUpload(File file, String[] metadata, String callbackId) {
            int status = 0;
            String response = "{}";
            HttpURLConnection connection = null;
            String boundary = "LerizAndroidBoundary" + System.currentTimeMillis();
            try {
                String filename = metadata[0].replace("\\", "_").replace("\"", "").replace("\r", "").replace("\n", "");
                String lowerFilename = filename.toLowerCase(java.util.Locale.ROOT);
                String videoMime;
                if (lowerFilename.endsWith(".webm")) videoMime = "video/webm";
                else if (lowerFilename.endsWith(".mov")) videoMime = "video/quicktime";
                else if (lowerFilename.endsWith(".3gp")) videoMime = "video/3gpp";
                else if (lowerFilename.endsWith(".m4v")) videoMime = "video/x-m4v";
                else if (lowerFilename.endsWith(".mkv")) videoMime = "video/x-matroska";
                else if (lowerFilename.endsWith(".avi")) videoMime = "video/x-msvideo";
                else videoMime = "video/mp4";
                byte[] titlePart = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\n" + metadata[1] + "\r\n").getBytes(StandardCharsets.UTF_8);
                byte[] captionPart = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n" + metadata[2] + "\r\n").getBytes(StandardCharsets.UTF_8);
                byte[] videoHeader = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"video\"; filename=\"" + filename + "\"\r\nContent-Type: " + videoMime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                byte[] ending = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
                URL url = new URL(API_BASE + "/api/videos");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setDoOutput(true);
                connection.setRequestProperty("ngrok-skip-browser-warning", "true");
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                if (!metadata[3].isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + metadata[3]);
                connection.setFixedLengthStreamingMode((long) titlePart.length + captionPart.length + videoHeader.length + file.length() + ending.length);
                try (OutputStream out = connection.getOutputStream(); FileInputStream in = new FileInputStream(file)) {
                    out.write(titlePart);
                    out.write(captionPart);
                    out.write(videoHeader);
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    out.write(ending);
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
                try { response = new JSONObject().put("error", e.getMessage() == null ? "Upload failed" : e.getMessage()).toString(); } catch (Exception ignored) {}
            } finally {
                if (connection != null) connection.disconnect();
                try { file.delete(); } catch (Exception ignored) {}
            }
            final int finalStatus = status;
            final String finalResponse = response;
            runOnUiThread(() -> {
                if (webView != null) webView.evaluateJavascript("window.nativeResponse && window.nativeResponse(" + JSONObject.quote(callbackId) + "," + finalStatus + "," + JSONObject.quote(finalResponse) + ")", null);
            });
        }
    }

    private boolean acquireRecorderAudioFocusInternal() {
        try {
            AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (manager == null) return false;
            if (!recorderAudioModeCaptured) {
                recorderPreviousAudioMode = manager.getMode();
                recorderAudioModeCaptured = true;
            }
            manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            int result = manager.requestAudioFocus(
                recorderAudioFocusListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            );
            recorderAudioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            return recorderAudioFocusHeld;
        } catch (Exception e) {
            android.util.Log.e("LerizRecorder", "Unable to acquire microphone audio focus", e);
            return false;
        }
    }

    private void releaseRecorderAudioFocusInternal() {
        try {
            AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (manager == null) return;
            if (recorderAudioFocusHeld) manager.abandonAudioFocus(recorderAudioFocusListener);
            recorderAudioFocusHeld = false;
            if (recorderAudioModeCaptured) manager.setMode(recorderPreviousAudioMode);
        } catch (Exception e) {
            android.util.Log.w("LerizRecorder", "Unable to release microphone audio focus", e);
        } finally {
            recorderAudioModeCaptured = false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadAppSettings();
        getWindow().setStatusBarColor(Color.rgb(5, 5, 8));
        getWindow().setNavigationBarColor(Color.rgb(5, 5, 8));
        getWindow().getDecorView().setSystemUiVisibility(0);
        rootContainer = new FrameLayout(this);
        nativeVideoTexture = new TextureView(this);
        nativeVideoTexture.setOpaque(false);
        nativeVideoTexture.setBackgroundColor(Color.TRANSPARENT);
        rootContainer.addView(nativeVideoTexture, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        nativeVideoTexture.setVisibility(View.GONE);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
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
                    !apiHost().equalsIgnoreCase(uri.getHost()) ||
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
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    Uri origin = request.getOrigin();
                    if (origin == null || !"https".equalsIgnoreCase(origin.getScheme()) ||
                        !"leriz.local".equalsIgnoreCase(origin.getHost())) {
                        request.deny();
                        return;
                    }
                    ArrayList<String> allowed = new ArrayList<>();
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) &&
                            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            allowed.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
                        } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) &&
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            allowed.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE);
                        } else {
                            request.deny();
                            return;
                        }
                    }
                    if (allowed.isEmpty()) {
                        request.deny();
                        return;
                    }
                    if (allowed.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) acquireRecorderAudioFocusInternal();
                    request.grant(allowed.toArray(new String[0]));
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                boolean wantsVideoCapture = params.isCaptureEnabled();
                if (wantsVideoCapture) {
                    // Use Android's native camera recorder for capture intents. The system camera
                    // owns microphone access and records the audio track, avoiding WebView's
                    // recurring "Could not start audio source" failure.
                    startVideoCapture();
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
        rootContainer.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(rootContainer);
        initializeNativeFeedPlayback();
        try (InputStream asset = getAssets().open("index.html");
             ByteArrayOutputStream pageBytes = new ByteArrayOutputStream()) {
            byte[] pageBuffer = new byte[8192];
            int pageRead;
            while ((pageRead = asset.read(pageBuffer)) != -1) pageBytes.write(pageBuffer, 0, pageRead);
            String pageHtml = pageBytes.toString("UTF-8");
            // A trusted HTTPS base origin is required for WebView getUserMedia camera access.
            webView.loadDataWithBaseURL("https://leriz.local/", pageHtml, "text/html", "UTF-8", null);
        } catch (Exception loadError) {
            webView.loadUrl("file:///android_asset/index.html");
        }
    }


    @UnstableApi
    private void initializeNativeFeedPlayback() {
        try {
            File cacheDirectory = new File(getCacheDir(), "leriz_native_video_cache");
            nativeMediaCache = new SimpleCache(
                cacheDirectory,
                new LeastRecentlyUsedCacheEvictor(256L * 1024L * 1024L),
                new StandaloneDatabaseProvider(getApplicationContext())
            );

            Map<String, String> headers = new HashMap<>();
            headers.put("ngrok-skip-browser-warning", "true");
            headers.put("Accept", "*/*");

            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("LerizAndroid/1.0")
                .setConnectTimeoutMs(12000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers);

            CacheDataSource.Factory cachedFactory = new CacheDataSource.Factory()
                .setCache(nativeMediaCache)
                .setUpstreamDataSourceFactory(httpFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

            TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus> preloadControl =
                new TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus>() {
                    @Override
                    public DefaultPreloadManager.PreloadStatus getTargetPreloadStatus(Integer rankingData) {
                        int distance = rankingData - currentNativeFeedIndex;
                        if (distance == 1) {
                            return DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(
                                nativeLowMemory ? 800L : 3500L
                            );
                        }
                        if (distance == -1) {
                            return DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(
                                nativeLowMemory ? 400L : 1500L
                            );
                        }
                        if (Math.abs(distance) == 2) {
                            return nativeLowMemory
                                ? DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
                                : DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED;
                        }
                        if (Math.abs(distance) <= 3) {
                            return DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED;
                        }
                        return DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED;
                    }
                };

            nativePreloadBuilder = new DefaultPreloadManager.Builder(this, preloadControl);
            nativePreloadBuilder.setDataSourceFactory(cachedFactory);
            nativePreloadManager = nativePreloadBuilder.build();
            nativeFeedPlayer = nativePreloadBuilder.buildExoPlayer();
            nativeFeedPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
            nativeFeedPlayer.setVideoTextureView(nativeVideoTexture);

            nativeFeedPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (currentNativeFeedId.isEmpty()) return;
                    if (playbackState == Player.STATE_BUFFERING) {
                        notifyNativeFeedState("buffering");
                    } else if (playbackState == Player.STATE_READY) {
                        notifyNativeFeedState("ready");
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    if (currentNativeFeedId.isEmpty()) return;
                    if (isPlaying) {
                        notifyNativeFeedState("playing");
                    } else if (!nativeFeedPlayer.getPlayWhenReady()
                            && nativeFeedPlayer.getPlaybackState() == Player.STATE_READY) {
                        notifyNativeFeedState("paused");
                    }
                }

                @Override
                public void onRenderedFirstFrame() {
                    if (!currentNativeFeedId.isEmpty()) {
                        notifyNativeFeedState("firstFrame");
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    NativeFeedItem active = nativeFeedById.get(currentNativeFeedId);
                    if (active != null && !nativeUsingFallback
                            && !active.fallbackUrl.isEmpty()
                            && !active.fallbackUrl.equals(activeNativePlaybackUrl)) {
                        nativeUsingFallback = true;
                        activeNativePlaybackUrl = active.fallbackUrl;
                        notifyNativeFeedState("buffering");
                        MediaItem fallback = new NativeFeedItem(
                            active.id, active.fallbackUrl, "", active.index
                        ).mediaItem;
                        nativeFeedPlayer.setMediaItem(fallback);
                        nativeFeedPlayer.prepare();
                        nativeFeedPlayer.setPlayWhenReady(true);
                        return;
                    }
                    android.util.Log.w("LerizPlayback", "Native playback failed for "
                        + currentNativeFeedId + ": " + error.getMessage());
                    notifyNativeFeedState("error");
                }
            });
        } catch (Exception error) {
            android.util.Log.e("LerizPlayback", "Native feed playback could not initialize.", error);
            if (nativeFeedPlayer != null) {
                try { nativeFeedPlayer.release(); } catch (Exception ignored) {}
                nativeFeedPlayer = null;
            }
            if (nativePreloadManager != null) {
                try { nativePreloadManager.release(); } catch (Exception ignored) {}
                nativePreloadManager = null;
            }
            if (nativeMediaCache != null) {
                try { nativeMediaCache.release(); } catch (Exception ignored) {}
                nativeMediaCache = null;
            }
        }
    }

    private void notifyNativeFeedState(String state) {
        if (webView == null || currentNativeFeedId.isEmpty()) return;
        final String idJson = JSONObject.quote(currentNativeFeedId);
        final String stateJson = JSONObject.quote(state);
        webView.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                    "window.onNativeFeedState && window.onNativeFeedState(" + idJson + "," + stateJson + ")",
                    null
                );
            }
        });
    }

    @UnstableApi
    private void updateNativeFeedItems(String json) {
        if (nativePreloadManager == null || nativeFeedPlayer == null) return;
        String previousId = currentNativeFeedId;
        currentNativeFeedId = "";
        activeNativePlaybackUrl = "";
        nativeFeedVisible = false;
        nativeVideoTexture.setVisibility(View.GONE);
        nativeFeedPlayer.pause();
        nativeFeedPlayer.clearMediaItems();
        nativePreloadManager.reset();
        nativePreloadedIds.clear();
        nativeFeedItems.clear();
        nativeFeedById.clear();

        try {
            JSONArray array = new JSONArray(json == null || json.isEmpty() ? "[]" : json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                String id = object.optString("id", "");
                String url = object.optString("url", "");
                String fallback = object.optString("fallback", "");
                if (id.isEmpty() || url.isEmpty() || nativeFeedById.containsKey(id)) continue;
                NativeFeedItem item = new NativeFeedItem(id, url, fallback, nativeFeedItems.size());
                nativeFeedItems.add(item);
                nativeFeedById.put(id, item);
            }
        } catch (Exception error) {
            android.util.Log.w("LerizPlayback", "Invalid native feed list.", error);
        }

        currentNativeFeedIndex = 0;
        if (!previousId.isEmpty()) {
            NativeFeedItem previous = nativeFeedById.get(previousId);
            if (previous != null) currentNativeFeedIndex = previous.index;
        }
        ensureNativePreloadWindow();
    }

    @UnstableApi
    private void ensureNativePreloadWindow() {
        if (nativePreloadManager == null || nativeFeedItems.isEmpty()) return;
        currentNativeFeedIndex = Math.max(0, Math.min(
            currentNativeFeedIndex, nativeFeedItems.size() - 1
        ));
        nativePreloadManager.setCurrentPlayingIndex(currentNativeFeedIndex);
        int first = Math.max(0, currentNativeFeedIndex - 2);
        int last = Math.min(nativeFeedItems.size() - 1, currentNativeFeedIndex + 3);
        for (int i = first; i <= last; i++) {
            NativeFeedItem item = nativeFeedItems.get(i);
            if (nativePreloadedIds.add(item.id)) {
                nativePreloadManager.add(item.mediaItem, item.index);
            }
        }
        nativePreloadManager.invalidate();
    }

    @UnstableApi
    private void pruneNativePreloadWindow() {
        if (nativePreloadManager == null) return;
        for (String id : new ArrayList<>(nativePreloadedIds)) {
            NativeFeedItem item = nativeFeedById.get(id);
            if (item == null || (Math.abs(item.index - currentNativeFeedIndex) > 3
                    && !id.equals(currentNativeFeedId))) {
                if (item != null) nativePreloadManager.remove(item.mediaItem);
                nativePreloadedIds.remove(id);
            }
        }
        nativePreloadManager.invalidate();
    }

    @UnstableApi
    private void startNativeFeedVideo(String id, int requestedIndex, String url, String fallbackUrl) {
        if (nativeFeedPlayer == null || nativePreloadManager == null || id == null || id.isEmpty()) return;
        NativeFeedItem item = nativeFeedById.get(id);
        if (item == null) {
            if (url == null || url.isEmpty()) return;
            int safeIndex = Math.max(0, Math.min(requestedIndex, nativeFeedItems.size()));
            item = new NativeFeedItem(id, url, fallbackUrl, safeIndex);
            nativeFeedItems.add(safeIndex, item);
            nativeFeedById.clear();
            for (int i = 0; i < nativeFeedItems.size(); i++) {
                NativeFeedItem old = nativeFeedItems.get(i);
                NativeFeedItem replacement = new NativeFeedItem(old.id, old.url, old.fallbackUrl, i);
                nativeFeedItems.set(i, replacement);
                nativeFeedById.put(replacement.id, replacement);
            }
            item = nativeFeedById.get(id);
        }

        if (id.equals(currentNativeFeedId)) {
            nativeVideoTexture.setVisibility(View.VISIBLE);
            nativeFeedVisible = true;
            return; // Do not undo a user-initiated pause on repeated intersection events.
        }

        if (!currentNativeFeedId.isEmpty()) {
            nativeFeedPositions.put(currentNativeFeedId, Math.max(0L, nativeFeedPlayer.getCurrentPosition()));
        }

        currentNativeFeedId = id;
        currentNativeFeedIndex = item.index;
        activeNativePlaybackUrl = item.url;
        nativeUsingFallback = false;
        nativeFeedVisible = true;
        nativeVideoTexture.setVisibility(View.VISIBLE);

        nativeFeedPlayer.setPlayWhenReady(false);
        ensureNativePreloadWindow();

        MediaSource preloadedSource = nativePreloadManager.getMediaSource(item.mediaItem);
        if (preloadedSource != null) {
            nativeFeedPlayer.setMediaSource(preloadedSource);
        } else {
            nativeFeedPlayer.setMediaItem(item.mediaItem);
        }

        Long savedPosition = nativeFeedPositions.get(id);
        if (savedPosition != null && savedPosition > 0) {
            nativeFeedPlayer.seekTo(savedPosition);
        } else {
            nativeFeedPlayer.seekTo(0);
        }
        nativeFeedPlayer.prepare();
        nativeFeedPlayer.setPlayWhenReady(true);
        pruneNativePreloadWindow();
    }

    private void stopNativeFeedPlayback() {
        nativeFeedVisible = false;
        nativeWasPlayingBeforePause = false;
        if (nativeFeedPlayer != null) {
            if (!currentNativeFeedId.isEmpty()) {
                nativeFeedPositions.put(currentNativeFeedId, Math.max(0L, nativeFeedPlayer.getCurrentPosition()));
            }
            currentNativeFeedId = "";
            activeNativePlaybackUrl = "";
            try {
                nativeFeedPlayer.pause();
                nativeFeedPlayer.clearMediaItems();
            } catch (Exception error) {
                android.util.Log.w("LerizPlayback", "Could not stop native feed player cleanly.", error);
            }
        }
        if (nativePreloadManager != null) {
            try {
                nativePreloadManager.reset();
                nativePreloadedIds.clear();
            } catch (Exception error) {
                android.util.Log.w("LerizPlayback", "Could not reset native preloads.", error);
            }
        }
        if (nativeVideoTexture != null) nativeVideoTexture.setVisibility(View.GONE);
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

    private void rememberPickedVideo(Uri uri) {
        String name = null;
        if (uri != null) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) name = cursor.getString(index);
                }
            } catch (Exception ignored) {}
            if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
        }
        synchronized (pickedVideoLock) {
            latestPickedVideoUri = uri;
            latestPickedVideoName = name;
        }
    }

    private void sendCameraPermissionResult(String callbackId, boolean granted) {
        if (webView == null || callbackId == null) return;
        webView.evaluateJavascript(
            "window.cameraPermissionResult && window.cameraPermissionResult(" +
                JSONObject.quote(callbackId) + "," + granted + ")", null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            boolean granted =
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            if (pendingCameraPermissionCallback != null) {
                String callbackId = pendingCameraPermissionCallback;
                pendingCameraPermissionCallback = null;
                sendCameraPermissionResult(callbackId, granted);
                if (!granted && webView != null)
                    webView.evaluateJavascript("window.toast && toast('Camera and microphone permissions are needed to record video.')", null);
            } else if (granted) {
                startVideoCapture();
            } else {
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
            rememberPickedVideo(captured);
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
            rememberPickedVideo(result != null && result.length > 0 ? result[0] : null);
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    protected void onPause() {
        if (nativeFeedPlayer != null) {
            nativeWasPlayingBeforePause = nativeFeedPlayer.getPlayWhenReady();
            if (!currentNativeFeedId.isEmpty()) {
                nativeFeedPositions.put(currentNativeFeedId, Math.max(0L, nativeFeedPlayer.getCurrentPosition()));
            }
            nativeFeedPlayer.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nativeWasPlayingBeforePause && nativeFeedVisible
                && nativeFeedPlayer != null && !currentNativeFeedId.isEmpty()) {
            nativeFeedPlayer.play();
        }
        nativeWasPlayingBeforePause = false;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        nativeLowMemory = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;
        if (nativePreloadManager != null) {
            try { nativePreloadManager.invalidate(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        nativeFeedVisible = false;
        if (nativeFeedPlayer != null) {
            try { nativeFeedPlayer.release(); } catch (Exception ignored) {}
            nativeFeedPlayer = null;
        }
        if (nativePreloadManager != null) {
            try { nativePreloadManager.release(); } catch (Exception ignored) {}
            nativePreloadManager = null;
        }
        if (nativeMediaCache != null) {
            try { nativeMediaCache.release(); } catch (Exception ignored) {}
            nativeMediaCache = null;
        }
        if (webView != null) {
            try { webView.removeJavascriptInterface("LerizNative"); } catch (Exception ignored) {}
            try { webView.destroy(); } catch (Exception ignored) {}
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
