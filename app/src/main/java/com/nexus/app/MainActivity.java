package com.nexus.app;

import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.graphics.drawable.ColorDrawable;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import android.widget.FrameLayout;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.Plugin;
import com.getcapacitor.annotation.CapacitorPlugin;


public class MainActivity extends BridgeActivity {
    private static final long ORIGINAL_SPLASH_HOLD_MS = 2850L;
    private static final long ORIGINAL_FADE_MS = 480L;
    private FrameLayout nativeSplash;
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
            // Suppress the OS status-bar surface during the native splash. A decor
            // child cannot cover that system-owned surface and may spill into content.
            controller.hide(WindowInsetsCompat.Type.statusBars());
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

        // Version 1.0.17 diagnostic: omit the N mark and animation.
        // Keep the native white splash and the existing hold/fade behavior.
        animationStartedAt = SystemClock.elapsedRealtime();
        maybeDismissSplash();
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
                    // Restore the real system status bar only after the splash is gone.
                    WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                        getWindow(), getWindow().getDecorView());
                    if (controller != null) {
                        controller.show(WindowInsetsCompat.Type.statusBars());
                    }
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
