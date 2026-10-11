package com.leriz.android;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.AugmentedFace;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.EnumSet;
import java.util.Collection;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Native ARCore Augmented Faces preview for Leriz. */
public class ARFaceActivity extends Activity implements GLSurfaceView.Renderer {
    private static final int CAMERA_PERMISSION = 8401;
    private GLSurfaceView glView;
    private Session session;
    private boolean installRequested = false;
    private boolean sessionReady = false;
    private volatile int selectedFilter = 1;
    private int cameraProgram, faceProgram, cameraTexture = 0;
    private int cameraPosition, cameraTexCoord, cameraSampler;
    private int facePosition, faceMvp, faceColor;
    private final float[] viewMatrix = new float[16];
    private final float[] projectionMatrix = new float[16];
    private final float[] modelMatrix = new float[16];
    private final float[] viewModel = new float[16];
    private final float[] mvpMatrix = new float[16];
    private FloatBuffer quadVertices;
    private FloatBuffer quadTexCoords;
    private FloatBuffer faceVertices;
    private ShortBuffer faceIndices;
    private int faceIndexCount = 0;
    private final float[][] colors = new float[][]{
        {0.20f, 0.85f, 0.95f, 0.30f},
        {0.75f, 0.25f, 1.00f, 0.43f},
        {1.00f, 0.20f, 0.48f, 0.40f},
        {1.00f, 0.68f, 0.12f, 0.38f},
        {0.15f, 0.95f, 0.55f, 0.36f}
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        FrameLayout root = new FrameLayout(this);
        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setPreserveEGLContextOnPause(true);
        glView.setRenderer(this);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(glView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(16), dp(14), dp(16), dp(12));
        top.setBackgroundColor(0x88000000);
        TextView title = new TextView(this);
        title.setText("Leriz · AR Face Filters");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setTypeface(null, 1);
        top.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("Google ARCore Augmented Faces");
        subtitle.setTextColor(0xFFD8D8E5);
        subtitle.setTextSize(12);
        top.addView(subtitle);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(12), dp(10), dp(12), dp(16));
        bottom.setBackgroundColor(0x99000000);
        TextView hint = new TextView(this);
        hint.setText("Choose a live face effect");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        bottom.addView(hint);
        LinearLayout choices = new LinearLayout(this);
        choices.setGravity(Gravity.CENTER);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"Cyan", "Violet", "Pink", "Gold", "Green"};
        for (int i = 0; i < labels.length; i++) {
            final int filter = i;
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setTextSize(10);
            b.setAllCaps(false);
            b.setPadding(dp(3), 0, dp(3), 0);
            b.setMinWidth(0);
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF292532));
            b.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
            lp.setMargins(dp(2), dp(6), dp(2), 0);
            choices.addView(b, lp);
            b.setOnClickListener(v -> selectedFilter = filter);
        }
        bottom.addView(choices);
        Button close = new Button(this);
        close.setText("Back to Leriz");
        close.setAllCaps(false);
        close.setTextColor(Color.WHITE);
        close.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF5A36A8));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(-1, dp(46));
        closeLp.setMargins(0, dp(8), 0, 0);
        bottom.addView(close, closeLp);
        close.setOnClickListener(v -> finish());
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        } else {
            createARSession();
        }
    }

    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void createARSession() {
        try {
            ArCoreApk.InstallStatus status = ArCoreApk.getInstance().requestInstall(this, !installRequested);
            if (status == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true;
                return;
            }
            session = new Session(this, EnumSet.of(Session.Feature.FRONT_CAMERA));
            Config config = session.getConfig();
            config.setAugmentedFaceMode(Config.AugmentedFaceMode.MESH3D);
            session.configure(config);
            sessionReady = true;
            if (glView != null) glView.requestRender();
        } catch (UnavailableException | SecurityException e) {
            Toast.makeText(this, "Google ARCore is unavailable on this device: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "Could not start ARCore: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_PERMISSION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) createARSession();
            else {
                Toast.makeText(this, "Camera permission is required for face AR.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (session == null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) createARSession();
        if (session != null) {
            try {
                session.resume();
                if (glView != null) glView.onResume();
            } catch (CameraNotAvailableException e) {
                Toast.makeText(this, "Camera is busy. Close other camera apps and try again.", Toast.LENGTH_LONG).show();
                finish();
            } catch (Exception e) {
                Toast.makeText(this, "Could not resume AR camera: " + e.getMessage(), Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override protected void onPause() {
        if (glView != null) glView.onPause();
        if (session != null) session.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        cameraProgram = createProgram(
            "#extension GL_OES_EGL_image_external : require\nattribute vec4 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord; void main(){ gl_Position=aPosition; vTexCoord=aTexCoord; }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTexCoord; uniform samplerExternalOES sTexture; void main(){ gl_FragColor=texture2D(sTexture,vTexCoord); }"
        );
        faceProgram = createProgram(
            "attribute vec4 aPosition; uniform mat4 uMVP; void main(){ gl_Position=uMVP*aPosition; }",
            "precision mediump float; uniform vec4 uColor; void main(){ gl_FragColor=uColor; }"
        );
        cameraPosition = GLES20.glGetAttribLocation(cameraProgram, "aPosition");
        cameraTexCoord = GLES20.glGetAttribLocation(cameraProgram, "aTexCoord");
        cameraSampler = GLES20.glGetUniformLocation(cameraProgram, "sTexture");
        facePosition = GLES20.glGetAttribLocation(faceProgram, "aPosition");
        faceMvp = GLES20.glGetUniformLocation(faceProgram, "uMVP");
        faceColor = GLES20.glGetUniformLocation(faceProgram, "uColor");
        quadVertices = floats(new float[]{-1,-1, 1,-1, -1,1, 1,1});
        quadTexCoords = floats(new float[]{0,1, 1,1, 0,0, 1,0});
        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        cameraTexture = texture[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        if (session != null) session.setCameraTextureName(cameraTexture);
    }

    @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
    }

    @Override public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        Session s = session;
        if (s == null || !sessionReady || cameraTexture == 0) return;
        try {
            s.setCameraTextureName(cameraTexture);
            Frame frame = s.update();
            drawCameraBackground();
            frame.getCamera().getViewMatrix(viewMatrix, 0);
            frame.getCamera().getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f);
            Collection<AugmentedFace> faces = s.getAllTrackables(AugmentedFace.class);
            for (AugmentedFace face : faces) {
                if (face.getTrackingState() != TrackingState.TRACKING) continue;
                FloatBuffer vertices = face.getMeshVertices();
                ShortBuffer indices = face.getMeshTriangleIndices();
                if (vertices == null || indices == null || vertices.remaining() < 3 || indices.remaining() < 3) continue;
                float[] v = new float[vertices.remaining()];
                vertices.get(v);
                short[] ix = new short[indices.remaining()];
                indices.get(ix);
                faceVertices = floats(v);
                faceIndices = shorts(ix);
                faceIndexCount = ix.length;
                face.getCenterPose().toMatrix(modelMatrix, 0);
                Matrix.multiplyMM(viewModel, 0, viewMatrix, 0, modelMatrix, 0);
                Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewModel, 0);
                drawFaceMesh();
                break;
            }
        } catch (CameraNotAvailableException e) {
            android.util.Log.w("LerizAR", "ARCore camera unavailable", e);
        } catch (Exception e) {
            android.util.Log.w("LerizAR", "AR frame error", e);
        }
    }

    private void drawCameraBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(cameraProgram);
        quadVertices.position(0);
        GLES20.glEnableVertexAttribArray(cameraPosition);
        GLES20.glVertexAttribPointer(cameraPosition, 2, GLES20.GL_FLOAT, false, 0, quadVertices);
        quadTexCoords.position(0);
        GLES20.glEnableVertexAttribArray(cameraTexCoord);
        GLES20.glVertexAttribPointer(cameraTexCoord, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture);
        GLES20.glUniform1i(cameraSampler, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(cameraPosition);
        GLES20.glDisableVertexAttribArray(cameraTexCoord);
    }

    private void drawFaceMesh() {
        if (faceVertices == null || faceIndices == null || faceIndexCount == 0) return;
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(faceProgram);
        faceVertices.position(0);
        GLES20.glEnableVertexAttribArray(facePosition);
        GLES20.glVertexAttribPointer(facePosition, 3, GLES20.GL_FLOAT, false, 0, faceVertices);
        GLES20.glUniformMatrix4fv(faceMvp, 1, false, mvpMatrix, 0);
        float[] color = colors[Math.max(0, Math.min(colors.length - 1, selectedFilter))];
        GLES20.glUniform4f(faceColor, color[0], color[1], color[2], color[3]);
        faceIndices.position(0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, faceIndexCount, GLES20.GL_UNSIGNED_SHORT, faceIndices);
        GLES20.glDisableVertexAttribArray(facePosition);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
    }

    private FloatBuffer floats(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    private ShortBuffer shorts(short[] values) {
        ShortBuffer buffer = ByteBuffer.allocateDirect(values.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String error = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new RuntimeException("GL program link failed: " + error);
        }
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        return program;
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("GL shader compile failed: " + error);
        }
        return shader;
    }
}
