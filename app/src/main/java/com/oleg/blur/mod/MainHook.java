package com.oleg.blur.mod;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG_SYSTEMUI = "com.android.systemui";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!PKG_SYSTEMUI.equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log("BlurHUN: SystemUI loaded.");
    }
}
