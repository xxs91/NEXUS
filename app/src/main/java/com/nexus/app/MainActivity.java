package com.nexus.app;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.view.Window;
import android.view.WindowManager;
import android.graphics.drawable.ColorDrawable;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.Plugin;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.InputStream;

public class MainActivity extends BridgeActivity {
    private static final long ORIGINAL_SPLASH_HOLD_MS = 2850L;
    private static final long ORIGINAL_FADE_MS = 480L;
    private FrameLayout nativeSplash;
    private ImageView mark;
    private long animationStartedAt;
    private boolean pageReady;
    private boolean splashDismissScheduled;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Paint the launch system bars before the first Activity frame to avoid a dark top strip.
        configureLaunchSystemBars();
        registerPlugin(NativeSplashPlugin.class);
        super.onCreate(savedInstanceState);
        // Capacitor may adjust window flags during initialization; re-apply the launch appearance.
        configureLaunchSystemBars();
        showNativeSplash();
    }

    private void configureLaunchSystemBars() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
            | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.WHITE);
        window.setNavigationBarColor(Color.WHITE);
        window.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        WindowCompat.setDecorFitsSystemWindows(window, true);
        View decor = window.getDecorView();
        decor.setBackgroundColor(Color.WHITE);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, decor);
        if (controller != null) {
            controller.setAppearanceLightStatusBars(true);
            controller.setAppearanceLightNavigationBars(true);
        }
    }

    private void showNativeSplash() {
        View content = findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;

        nativeSplash = new FrameLayout(this);
        nativeSplash.setBackgroundColor(Color.WHITE);
        nativeSplash.setClickable(true);
        ((ViewGroup) content).addView(nativeSplash,
            new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                       ViewGroup.LayoutParams.MATCH_PARENT));

        Bitmap bitmap = null;
        try (InputStream input = getAssets().open("public/nexus-mark.png")) {
            bitmap = BitmapFactory.decodeStream(input);
        } catch (Exception ignored) { }

        if (bitmap == null) {
            // Keep the native launch surface white if the image asset cannot be decoded.
            return;
        }

        mark = new ImageView(this);
        mark.setImageBitmap(bitmap);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER);

        float density = getResources().getDisplayMetrics().density;
        float widthDp = getResources().getDisplayMetrics().widthPixels / density;
        int targetHeight = Math.round(Math.max(112f, Math.min(154f, widthDp * 0.30f)) * density);
        int targetWidth = Math.round(targetHeight * (bitmap.getWidth() / (float) bitmap.getHeight()));

        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
            targetWidth, targetHeight, Gravity.CENTER);
        nativeSplash.addView(mark, imageParams);

        mark.setAlpha(0.12f);
        mark.setTranslationY(30f * density);
        mark.post(() -> {
            if (mark == null || nativeSplash == null) return;
            final int w = mark.getWidth();
            final int h = mark.getHeight();
            mark.setClipBounds(new Rect(0, h, w, h));
            animationStartedAt = SystemClock.elapsedRealtime();

            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1950L);
            animator.setInterpolator(new PathInterpolator(0.22f, 0.72f, 0.18f, 1f));
            animator.addUpdateListener(value -> {
                float f = (Float) value.getAnimatedValue();
                int top = Math.max(0, Math.min(h, Math.round(h * (1f - f))));
                mark.setClipBounds(new Rect(0, top, w, h));
                mark.setTranslationY(30f * density * (1f - f));
                mark.setAlpha(splashAlpha(f));
            });
            animator.start();
            maybeDismissSplash();
        });
    }

    private float splashAlpha(float f) {
        if (f <= 0.32f) return 0.12f + (0.34f - 0.12f) * (f / 0.32f);
        if (f <= 0.68f) return 0.34f + (0.68f - 0.34f) * ((f - 0.32f) / 0.36f);
        return 0.68f + (1f - 0.68f) * ((f - 0.68f) / 0.32f);
    }

    void onWebPageReady() {
        runOnUiThread(() -> {
            pageReady = true;
            maybeDismissSplash();
        });
    }

    private void maybeDismissSplash() {
        if (!pageReady || nativeSplash == null || splashDismissScheduled || animationStartedAt == 0L) return;
        splashDismissScheduled = true;
        long elapsed = SystemClock.elapsedRealtime() - animationStartedAt;
        long wait = Math.max(0L, ORIGINAL_SPLASH_HOLD_MS - elapsed);
        nativeSplash.postDelayed(() -> {
            if (nativeSplash == null) return;
            nativeSplash.animate()
                .alpha(0f)
                .setDuration(ORIGINAL_FADE_MS)
                .withEndAction(() -> {
                    ViewGroup parent = (ViewGroup) nativeSplash.getParent();
                    if (parent != null) parent.removeView(nativeSplash);
                    nativeSplash = null;
                    mark = null;
                })
                .start();
        }, wait);
    }

    @CapacitorPlugin(name = "NativeSplash")
    public static class NativeSplashPlugin extends Plugin {
        @Override
        public void load() {
            bridge.getWebView().addJavascriptInterface(this, "NexusNativeSplash");
            super.load();
        }

        @JavascriptInterface
        public void pageReady() {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).onWebPageReady();
            }
        }
    }
}
