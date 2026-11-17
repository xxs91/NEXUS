package com.nexus.app;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
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
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.ViewCompat;
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
    private View statusBarWhiteCover;
    private long animationStartedAt;
    private boolean pageReady;
    private boolean splashDismissScheduled;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Establish the launch window before WebView/Capacitor creates its first frame.
        configureLaunchWindow();
        registerPlugin(NativeSplashPlugin.class);
        super.onCreate(savedInstanceState);

        // WebView can initially expose its default black surface while Chromium starts.
        // Give both the WebView and its host an explicit opaque light backing surface.
        configureLaunchWindow();
        if (bridge != null && bridge.getWebView() != null) {
            bridge.getWebView().setBackgroundColor(Color.WHITE);
            bridge.getWebView().setBackgroundResource(android.R.color.white);
            bridge.getWebView().setOverScrollMode(View.OVER_SCROLL_NEVER);
        }
        showNativeSplash();
    }

    private void configureLaunchWindow() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
            | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        // Android 15+ enforces edge-to-edge for targetSdk 35+. Draw a known white
        // surface behind the transparent status bar instead of relying on the
        // deprecated statusBarColor behavior.
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.WHITE);
        window.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }

        WindowCompat.setDecorFitsSystemWindows(window, false);
        View decor = window.getDecorView();
        decor.setBackgroundColor(Color.WHITE);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, decor);
        if (controller != null) {
            controller.setAppearanceLightStatusBars(true);
            controller.setAppearanceLightNavigationBars(true);
        }
    }

    private void showNativeSplash() {
        View decor = getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return;

        nativeSplash = new FrameLayout(this);
        nativeSplash.setBackgroundColor(Color.WHITE);
        nativeSplash.setClickable(true);
        nativeSplash.setFocusable(true);
        nativeSplash.setClipToPadding(false);

        // Attach to the window decor, not android.R.id.content. This makes the
        // splash background cover the status-bar inset as well as the WebView.
        ViewGroup decorGroup = (ViewGroup) decor;
        decorGroup.addView(nativeSplash,
            new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                       ViewGroup.LayoutParams.MATCH_PARENT));

        // A dedicated opaque white cap sits at the very top of the app window.
        // It is deliberately a separate, last-added native view so later WebView
        // frames cannot paint a dark strip over the launch status-bar backdrop.
        statusBarWhiteCover = new View(this);
        statusBarWhiteCover.setBackgroundColor(Color.WHITE);
        statusBarWhiteCover.setClickable(false);
        statusBarWhiteCover.setFocusable(false);
        statusBarWhiteCover.setElevation(10000f);
        decorGroup.addView(statusBarWhiteCover, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP));

        ViewCompat.setOnApplyWindowInsetsListener(nativeSplash, (view, insets) -> {
            androidx.core.graphics.Insets bars =
                insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(0, bars.top, 0, bars.bottom);
            if (statusBarWhiteCover != null) {
                ViewGroup.LayoutParams coverParams = statusBarWhiteCover.getLayoutParams();
                coverParams.height = bars.top;
                statusBarWhiteCover.setLayoutParams(coverParams);
                statusBarWhiteCover.setVisibility(bars.top > 0 ? View.VISIBLE : View.GONE);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(nativeSplash);

        Bitmap bitmap = null;
        try (InputStream input = getAssets().open("public/nexus-mark.png")) {
            bitmap = BitmapFactory.decodeStream(input);
        } catch (Exception ignored) { }

        if (bitmap == null) {
            // Keep the entire launch window white if the image cannot be decoded.
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

            // Preserve the original N reveal: duration, easing, lift and alpha curve.
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
                    if (statusBarWhiteCover != null) {
                        ViewGroup coverParent = (ViewGroup) statusBarWhiteCover.getParent();
                        if (coverParent != null) coverParent.removeView(statusBarWhiteCover);
                        statusBarWhiteCover = null;
                    }
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
