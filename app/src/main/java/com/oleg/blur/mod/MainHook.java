package com.oleg.blur.mod;

import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "BlurHUN: ";
    private static final String PKG_SYSTEMUI = "com.android.systemui";
    private static final String CLS_ENTRY =
            "com.android.systemui.statusbar.notification.collection.NotificationEntry";
    private static final String CLS_ROW =
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
    private static final String CLS_BG =
            "com.android.systemui.statusbar.notification.row.NotificationBackgroundView";
    private static final String CLS_HUN =
            "com.android.systemui.statusbar.phone.HeadsUpAppearanceController";

    private static final String KEY_ACTIVE = "blur_hun_active";
    private static final String KEY_DRAWABLE = "blur_hun_drawable";
    private static final String KEY_ORIGINAL = "blur_hun_original";
    private static final String KEY_STROKE = "blur_hun_stroke";

    private static final int STROKE_WIDTH_DP = 1;
    private static final int DEFAULT_BLUR_RADIUS = 65;
    private static final int DEFAULT_TINT_ALPHA = 177;
    private static final int MIN_BLUR_RADIUS = 0;
    private static final int MAX_BLUR_RADIUS = 150;
    private static final int MIN_TINT_ALPHA = 0;
    private static final int MAX_TINT_ALPHA = 255;
    private static final String CONFIG_DIR = "Android/media/com.oleg.blur.mod";
    private static final String CONFIG_FILE = "blur_config.properties";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!PKG_SYSTEMUI.equals(lpparam.packageName)) {
            return;
        }

        log("SystemUI loaded, installing HUN blur hooks.");

        hookHunState(lpparam);
        hookBackgroundCompatibility(lpparam);
    }

    private void hookHunState(LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(CLS_ENTRY, lpparam.classLoader, "setHeadsUp", boolean.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object row = XposedHelpers.getObjectField(param.thisObject, "row");
                if (row instanceof ViewGroup) {
                    updateHunBlur((ViewGroup) row, (boolean) param.args[0], "NotificationEntry#setHeadsUp");
                }
            }
        });

        XposedHelpers.findAndHookMethod(CLS_ROW, lpparam.classLoader, "setHeadsUpAnimatingAway", boolean.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                ViewGroup row = (ViewGroup) param.thisObject;
                boolean active = (boolean) param.args[0] || (boolean) XposedHelpers.callMethod(row, "isHeadsUpState");
                updateHunBlur(row, active, "ExpandableNotificationRow#setHeadsUpAnimatingAway");
            }
        });

        Class<?> hunClass = XposedHelpers.findClass(CLS_HUN, lpparam.classLoader);
        XposedBridge.hookAllMethods(hunClass, "onHeadsUpPinned", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object row = XposedHelpers.getObjectField(param.args[0], "row");
                if (row instanceof ViewGroup) {
                    updateHunBlur((ViewGroup) row, true, "HeadsUpAppearanceController#onHeadsUpPinned");
                }
            }
        });
        XposedBridge.hookAllMethods(hunClass, "onHeadsUpUnPinned", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object row = XposedHelpers.getObjectField(param.args[0], "row");
                if (row instanceof ViewGroup) {
                    boolean active = (boolean) XposedHelpers.callMethod(row, "isHeadsUpState");
                    updateHunBlur((ViewGroup) row, active, "HeadsUpAppearanceController#onHeadsUpUnPinned");
                }
            }
        });
    }

    private void hookBackgroundCompatibility(LoadPackageParam lpparam) {
        Class<?> bgClass = XposedHelpers.findClass(CLS_BG, lpparam.classLoader);

        XposedHelpers.findAndHookMethod(bgClass, "setCustomBackground$1", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View bg = (View) param.thisObject;
                if (isActive(bg)) {
                    applyBlurToBackground(bg, isPinned(bg), "NotificationBackgroundView#setCustomBackground$1");
                }
            }
        });

        XposedHelpers.findAndHookMethod(bgClass, "setTint", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View bg = (View) param.thisObject;
                if (!isActive(bg)) {
                    return;
                }
                XposedHelpers.setIntField(bg, "mTintColor", Color.TRANSPARENT);
                param.setResult(null);
            }
        });

        XposedHelpers.findAndHookMethod(bgClass, "updateBackgroundRadii", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View bg = (View) param.thisObject;
                if (!isActive(bg)) {
                    return;
                }
                Object drawable = XposedHelpers.getAdditionalInstanceField(bg, KEY_DRAWABLE);
                if (drawable != null) {
                    BlurConfig config = getConfig();
                    syncCornerRadius(bg, drawable, ensureStrokeDrawable(bg), isPinned(bg), config.tintAlpha);
                    param.setResult(null);
                }
            }
        });

        XposedHelpers.findAndHookMethod(bgClass, "onDraw", Canvas.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View bg = (View) param.thisObject;
                if (!isActive(bg)) {
                    return;
                }
                syncDrawableVisualState(bg);
            }
        });
    }

    private void updateHunBlur(ViewGroup row, boolean active, String reason) {
        try {
            View bg = (View) XposedHelpers.getObjectField(row, "mBackgroundNormal");
            if (bg == null) {
                log(reason + " missing backgroundNormal");
                return;
            }

            XposedHelpers.setAdditionalInstanceField(bg, KEY_ACTIVE, active);
            if (active) {
                BlurConfig config = getConfig();
                boolean pinned = isPinned(row);
                applyBlurToBackground(bg, pinned, reason);
                try {
                    XposedHelpers.callMethod(row, "setBackgroundBlurRadius", config.blurRadius);
                } catch (Throwable ignored) {
                }
            } else {
                clearBlurFromBackground(bg, reason);
                try {
                    XposedHelpers.callMethod(row, "setBackgroundBlurRadius", 0);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            log(reason + " failed: " + t);
        }
    }

    private void applyBlurToBackground(View bg, boolean pinned, String reason) {
        try {
            if (!isCrossWindowBlurEnabled(bg)) {
                log(reason + " cross-window blur disabled");
                return;
            }

            Object blurDrawable = ensureBlurDrawable(bg);
            if (!(blurDrawable instanceof Drawable)) {
                log(reason + " no blur drawable");
                return;
            }

            Drawable current = (Drawable) XposedHelpers.getObjectField(bg, "mBackground");
            Drawable original = (Drawable) XposedHelpers.getAdditionalInstanceField(bg, KEY_ORIGINAL);
            if (original == null && current != null) {
                XposedHelpers.setAdditionalInstanceField(bg, KEY_ORIGINAL, current);
                original = current;
            }

            GradientDrawable strokeDrawable = ensureStrokeDrawable(bg);
            LayerDrawable composite = new LayerDrawable(new Drawable[]{
                    (Drawable) blurDrawable,
                    strokeDrawable
            });

            if (current != composite) {
                if (original == null && current != null) {
                    XposedHelpers.setAdditionalInstanceField(bg, KEY_ORIGINAL, current);
                }
                composite.setCallback(bg);
                XposedHelpers.setObjectField(bg, "mBackground", composite);
            }

            BlurConfig config = getConfig();
            XposedHelpers.callMethod(blurDrawable, "setBlurRadius", config.blurRadius);
            XposedHelpers.callMethod(blurDrawable, "setColor", resolveSurfaceTint(bg, pinned, config.tintAlpha));
            int drawableAlpha = resolveAnimatedAlpha(bg);
            XposedHelpers.callMethod(blurDrawable, "setAlpha", drawableAlpha);
            strokeDrawable.setAlpha(drawableAlpha);
            syncCornerRadius(bg, blurDrawable, strokeDrawable, pinned, config.tintAlpha);
            bg.invalidate();
            log(reason + " applied radius=" + config.blurRadius + " tintAlpha=" + config.tintAlpha + " alpha=" + drawableAlpha);
        } catch (Throwable t) {
            log(reason + " apply failed: " + t);
        }
    }

    private void clearBlurFromBackground(View bg, String reason) {
        try {
            Object original = XposedHelpers.getAdditionalInstanceField(bg, KEY_ORIGINAL);
            if (original instanceof Drawable) {
                ((Drawable) original).setCallback(bg);
                XposedHelpers.setObjectField(bg, "mBackground", original);
            }
            XposedHelpers.removeAdditionalInstanceField(bg, KEY_ORIGINAL);
            XposedHelpers.removeAdditionalInstanceField(bg, KEY_ACTIVE);
            bg.invalidate();
            log(reason + " cleared");
        } catch (Throwable t) {
            log(reason + " clear failed: " + t);
        }
    }

    private Object ensureBlurDrawable(View bg) {
        Object cached = XposedHelpers.getAdditionalInstanceField(bg, KEY_DRAWABLE);
        if (cached != null) {
            return cached;
        }
        if (!bg.isAttachedToWindow()) {
            return null;
        }
        Object root = XposedHelpers.callMethod(bg, "getViewRootImpl");
        if (root == null) {
            return null;
        }
        Object blurDrawable = XposedHelpers.callMethod(root, "createBackgroundBlurDrawable");
        if (blurDrawable != null) {
            XposedHelpers.setAdditionalInstanceField(bg, KEY_DRAWABLE, blurDrawable);
        }
        return blurDrawable;
    }

    private GradientDrawable ensureStrokeDrawable(View bg) {
        Object cached = XposedHelpers.getAdditionalInstanceField(bg, KEY_STROKE);
        if (cached instanceof GradientDrawable) {
            return (GradientDrawable) cached;
        }
        GradientDrawable stroke = new GradientDrawable();
        stroke.setShape(GradientDrawable.RECTANGLE);
        stroke.setColor(Color.TRANSPARENT);
        stroke.setStroke(dp(bg, STROKE_WIDTH_DP), Color.WHITE);
        XposedHelpers.setAdditionalInstanceField(bg, KEY_STROKE, stroke);
        return stroke;
    }

    private void syncDrawableVisualState(View bg) {
        try {
            Object blurDrawable = XposedHelpers.getAdditionalInstanceField(bg, KEY_DRAWABLE);
            if (blurDrawable == null) {
                return;
            }
            int alpha = resolveAnimatedAlpha(bg);
            XposedHelpers.callMethod(blurDrawable, "setAlpha", alpha);
            ensureStrokeDrawable(bg).setAlpha(alpha);
        } catch (Throwable t) {
            log("syncDrawableVisualState failed: " + t);
        }
    }

    private void syncCornerRadius(
            View bg,
            Object blurDrawable,
            GradientDrawable strokeDrawable,
            boolean pinned,
            int tintAlpha
    ) {
        float[] radii = (float[]) XposedHelpers.getObjectField(bg, "mCornerRadii");
        if (radii == null || radii.length < 8) {
            return;
        }
        XposedHelpers.callMethod(blurDrawable, "setCornerRadius", radii[0], radii[2], radii[4], radii[6]);
        strokeDrawable.setStroke(dp(bg, STROKE_WIDTH_DP), resolveStrokeColor(bg, pinned, tintAlpha));
        strokeDrawable.setCornerRadii(new float[]{
                radii[0], radii[1], radii[2], radii[3],
                radii[4], radii[5], radii[6], radii[7]
        });
    }

    private int resolveSurfaceTint(View bg, boolean pinned, int tintAlpha) {
        boolean dark = isNightMode(bg);
        int fallback = dark ? 0xFF2F3135 : 0xFFF7F7FA;
        int baseColor = fallback;

        try {
            Object parent = bg.getParent();
            if (parent != null) {
                int calculated = (int) XposedHelpers.callMethod(parent, "calculateBgColor", true);
                if (Color.alpha(calculated) != 0) {
                    baseColor = calculated;
                }
            }
        } catch (Throwable ignored) {
        }

        int alpha = clamp(tintAlpha, MIN_TINT_ALPHA, MAX_TINT_ALPHA);
        if (pinned && alpha > 0) {
            alpha = Math.min(MAX_TINT_ALPHA, alpha + 4);
        }
        return Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor));
    }

    private int resolveStrokeColor(View bg, boolean pinned, int tintAlpha) {
        boolean dark = isNightMode(bg);
        int alpha = clamp(Math.round(tintAlpha * (dark ? 0.9f : 0.6f)), 0, 140);
        if (pinned && alpha > 0) {
            alpha = Math.min(160, alpha + 8);
        }
        return dark
                ? Color.argb(alpha, 255, 255, 255)
                : Color.argb(alpha, 255, 255, 255);
    }

    private int resolveAnimatedAlpha(View bg) {
        float alpha = 1.0f;
        alpha *= bg.getAlpha();

        Object parent = bg.getParent();
        if (parent instanceof View) {
            View row = (View) parent;
            alpha *= row.getAlpha();
            try {
                Object contentView = XposedHelpers.callMethod(row, "getContentView");
                if (contentView instanceof View) {
                    alpha *= ((View) contentView).getAlpha();
                }
            } catch (Throwable ignored) {
            }
        }

        int drawableAlpha = 255;
        try {
            drawableAlpha = XposedHelpers.getIntField(bg, "mDrawableAlpha");
        } catch (Throwable ignored) {
        }
        return clamp(Math.round(drawableAlpha * alpha), 0, 255);
    }

    private boolean isActive(View bg) {
        Object active = XposedHelpers.getAdditionalInstanceField(bg, KEY_ACTIVE);
        return active instanceof Boolean && (Boolean) active;
    }

    private boolean isPinned(Object target) {
        try {
            Object row = target;
            if (target instanceof View && CLS_BG.equals(target.getClass().getName())) {
                row = ((View) target).getParent();
            }
            return row != null && (boolean) XposedHelpers.callMethod(row, "isPinned");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isCrossWindowBlurEnabled(View bg) {
        try {
            WindowManager wm = bg.getContext().getSystemService(WindowManager.class);
            return wm == null || wm.isCrossWindowBlurEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private BlurConfig getConfig() {
        File file = new File(Environment.getExternalStorageDirectory(), CONFIG_DIR + "/" + CONFIG_FILE);
        BlurConfig config = new BlurConfig(DEFAULT_TINT_ALPHA, DEFAULT_BLUR_RADIUS);
        if (!file.exists()) {
            return config;
        }

        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(file)) {
            properties.load(input);
            config.tintAlpha = clamp(parseInt(properties.getProperty("tint_alpha"), DEFAULT_TINT_ALPHA),
                    MIN_TINT_ALPHA, MAX_TINT_ALPHA);
            config.blurRadius = clamp(parseInt(properties.getProperty("blur_radius"), DEFAULT_BLUR_RADIUS),
                    MIN_BLUR_RADIUS, MAX_BLUR_RADIUS);
        } catch (Throwable t) {
            log("getConfig failed: " + t);
        }
        return config;
    }

    private int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(View view, int dp) {
        return Math.round(dp * view.getResources().getDisplayMetrics().density);
    }

    private boolean isNightMode(View view) {
        int nightMask = view.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMask == Configuration.UI_MODE_NIGHT_YES;
    }

    private void log(String message) {
        String full = TAG + message;
        XposedBridge.log(full);
        Log.e("BlurHUN", message);
    }

    private static final class BlurConfig {
        int tintAlpha;
        int blurRadius;

        BlurConfig(int tintAlpha, int blurRadius) {
            this.tintAlpha = tintAlpha;
            this.blurRadius = blurRadius;
        }
    }
}
