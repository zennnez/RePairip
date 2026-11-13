/*
 * Copyright (c) 2024 Antik
 *
 * Licensed under the MIT License.
 *
 * ⋆ ႔ ႔
 * ᠸ^ ^ ⸝⸝
 * |、˜〵
 * じしˍ,)⁐̤ᐷ
 *
 * Fox Mode 🍺
 *
 * LSPosed Module Entry Point using modern LibXposed API 102.
 */
package com.repairip.module;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

public class RePairipModule extends XposedModule {

 @Override
 public void onPackageLoaded(PackageLoadedParam param) {
 super.onPackageLoaded(param);

 // Skip self and related helper packages
 String packageName = param.getPackageName();
 if (packageName.startsWith("com.repairip")) {
 return;
 }

 PairIpHooker.hook(this, param);
 }
}