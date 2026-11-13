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
 * Runtime PairIP bypass using DexKit and LibXposed API 102.
 *
 * Replaces the .jar static analysis / APK repacking with live runtime interception:
 * 1. SecurityBypassPatcher:
 * - SignatureCheck.verifySignatureMatches -> returns true
 * - LicenseClient / LicenseClientV3/LicenseClientV4.verifyIntegrity -> returns true / null
 * - LicenseClientV3.onActivityCreate -> stubbed (from TranslationRewriter)
 * 2. LauncherPatcher & LaunchMethodPatcher:
 * - Hooks StartupLauncher.launch to allow VMRunner to decrypt strings/methods
 * 3. DexPatcher Junk-Class Discovery & pairip.json Dump:
 * - Uses DexKit structural matching to find all PairIP junk classes
 * - Dumps decrypted strings and methods to /data/data/<pkg>/dictionary/pairip.json
 * (replaces log.dex, capture_pairip.py, and Frida)
 */
package com.repairip.module;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.FieldsMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

@SuppressWarnings("ALL")
public class PairIpHooker {

 private static final String TAG = "RePairip";

 // Standard known PairIP target descriptors
 private static final String CLS_STARTUP_LAUNCHER = "com.pairip.StartupLauncher";
 private static final String CLS_SIGNATURE_CHECK = "com.pairip.SignatureCheck";
 private static final String CLS_LICENSE_CLIENT = "com.pairip.licensecheck.LicenseClient";
 private static final String CLS_LICENSE_CLIENT_V3 = "com.pairip.licensecheck3.LicenseClientV3";
 private static final String CLS_LICENSE_CLIENT_V4 = "com.pairip.licensecheck4.LicenseClientV4"; // New!
 private static final String CLS_PAIRIP_LOG = "com.pairip.PairipLog";

 static {
 System.loadLibrary("dexkit");
 }

 public static void hook(XposedModule module, PackageLoadedParam param) {
 ApplicationInfo appInfo = param.getApplicationInfo();
 if (appInfo == null || appInfo.sourceDir == null) {
 return;
 }

 String apkPath = appInfo.sourceDir;
 ClassLoader cl = param.getDefaultClassLoader();
 String packageName = param.getPackageName();

 try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
 // 1. Bypass integrity and signature checks
 hookSecurityClasses(module, bridge, cl);

 // 2. Discover junk classes via DexKit structural fingerprint
 List<Class<?>> junkClasses = findJunkClasses(bridge, cl);

 // 3. Hook StartupLauncher to decrypt in-memory and capture dictionary
 hookStartupLauncher(module, bridge, cl, junkClasses, packageName);

 } catch (Throwable t) {
 Log.e(TAG, "RePairip hook initialization failed for " + packageName, t);
 }
 }

 // ─── 1. Security Bypass (mirrors SecurityBypassPatcher) ───────────────────

 private static void hookSecurityClasses(XposedModule module, DexKitBridge bridge, ClassLoader cl) {
 String[] securityClasses = {CLS_SIGNATURE_CHECK, CLS_LICENSE_CLIENT, CLS_LICENSE_CLIENT_V3, CLS_LICENSE_CLIENT_V4};

 for (String className : securityClasses) {
 try {
 MethodDataList methods = bridge.findMethod(FindMethod.create()
 .matcher(MethodMatcher.create()
 .declaredClass(ClassMatcher.create().className(className))));

 for (MethodData md : methods) {
 String name = md.getMethodName();

 if ("verifySignatureMatches".equals(name)) {
 Method m = md.getMethodInstance(cl);
 Log.i(TAG, "Bypassing verifySignatureMatches @ " + className);
 module.hook(m).intercept(chain -> true);

 } else if ("verifyIntegrity".equals(name)) {
 Method m = md.getMethodInstance(cl);
 Log.i(TAG, "Bypassing verifyIntegrity @ " + className);
 final String ret = md.getReturnTypeName();
 module.hook(m).intercept(chain -> {
 if ("void".equals(ret)) return null;
 if (ret.contains(".") || ret.startsWith("[")) return null;
 return 1;
 });

 } else if ("onActivityCreate".equals(name) && (CLS_LICENSE_CLIENT_V3.equals(className) || CLS_LICENSE_CLIENT_V4.equals(className))) {
 Method m = md.getMethodInstance(cl);
 Log.i(TAG, "Stubbing onActivityCreate @ " + className);
 module.hook(m).intercept(chain -> null);
 }
 }
 } catch (Throwable t) {
 Log.w(TAG, "Could not hook security class: " + className, t);
 }
 }
 }

 // ─── 2. Junk-Class Discovery (mirrors DexPatcher scan) ───────────────────

 /**
 * Exact criteria from DexPatcher.java:
 * - superclass is Object
 * - no methods
 * - public access (flags == 1)
 * - all fields are public static (flags == 9) of type String, Method, or Class<?>
 */
 private static List<Class<?>> findJunkClasses(DexKitBridge bridge, ClassLoader cl) {
 List<Class<?>> result = new ArrayList<>();

 // Find String junk containers
 ClassDataList stringJunk = bridge.findClass(FindClass.create()
 .matcher(ClassMatcher.create()
 .superClass("java.lang.Object")
 .modifiers(Modifier.PUBLIC)
 .methodCount(0)
 .fields(FieldsMatcher.create()
 .add(FieldMatcher.create()
 .modifiers(Modifier.PUBLIC | Modifier.STATIC)
 .type("java.lang.String")))));

 for (ClassData cd : stringJunk) {
 try {
 result.add(cd.getInstance(cl));
 } catch (Throwable ignored) {
 }
 }

 // Find Method junk containers
 ClassDataList methodJunk = bridge.findClass(FindClass.create()
 .matcher(ClassMatcher.create()
 .superClass("java.lang.Object")
 .modifiers(Modifier.PUBLIC)
 .methodCount(0)
 .fields(FieldsMatcher.create()
 .add(FieldMatcher.create()
 .modifiers(Modifier.PUBLIC | Modifier.STATIC)
 .type("java.lang.reflect.Method")))));

 for (ClassData cd : methodJunk) {
 try {
 result.add(cd.getInstance(cl));
 } catch (Throwable ignored) {
 }
 }

 // Find Class<?> junk containers (new!)
 ClassDataList classJunk = bridge.findClass(FindClass.create()
 .matcher(ClassMatcher.create()
 .superClass("java.lang.Object")
 .modifiers(Modifier.PUBLIC)
 .methodCount(0)
 .fields(FieldsMatcher.create()
 .add(FieldMatcher.create()
 .modifiers(Modifier.PUBLIC | Modifier.STATIC)
 .type("java.lang.Class")))));

 for (ClassData cd : classJunk) {
 try {
 result.add(cd.getInstance(cl));
 } catch (Throwable ignored) {
 }
 }

 Log.i(TAG, "Discovered " + result.size() + " junk classes via DexKit");
 return result;
 }

 // ─── 3. StartupLauncher & Capture (mirrors log.dex & capture_pairip.py) ──

 private static void hookStartupLauncher(
 XposedModule module,
 DexKitBridge bridge,
 ClassLoader cl,
 List<Class<?>> junkClasses,
 String packageName) {

 try {
 MethodDataList launchMethods = bridge.findMethod(FindMethod.create()
 .matcher(MethodMatcher.create()
 .declaredClass(ClassMatcher.create().className(CLS_STARTUP_LAUNCHER))
 .name("launch")));

 for (MethodData md : launchMethods) {
 Method m = md.getMethodInstance(cl);
 Log.i(TAG, "Hooking StartupLauncher.launch for package: " + packageName);

 module.hook(m).intercept(chain -> {
 // Let StartupLauncher execute so VMRunner decrypts all strings/methods
 Object ret = chain.proceed();

 // Immediately capture all decrypted junk fields into pairip.json
 try {
 dumpPairipDictionary(junkClasses, packageName);
 } catch (Throwable t) {
 Log.e(TAG, "Error dumping pairip dictionary", t);
 }

 return ret;
 });
 }
 } catch (Throwable t) {
 Log.w(TAG, "StartupLauncher.launch not found or already stripped", t);
 }
 }

 // ─── 4. Dictionary Export (1:1 with pairip.json output format) ───────────

 /**
 * Dumps decrypted values into the exact pairip.json structure expected by
 * TranslationData / TranslationPatcher:
 * {
 * "Lcom/example/JunkClass;": {
 * "type": "java.lang.String",
 * "fields": {
 * "a": "decrypted string", ...
 * }
 * }
 * }
 */
 private static void dumpPairipDictionary(List<Class<?>> junkClasses, String packageName) {
 if (junkClasses.isEmpty()) {
 return;
 }

 try {
 JSONObject root = new JSONObject();

 for (Class<?> clazz : junkClasses) {
 String descriptor = "L" + clazz.getName().replace('.', '/') + ";";
 Field[] fields = clazz.getDeclaredFields();
 if (fields.length == 0) continue;

 String typeName = fields[0].getType().getName();
 JSONObject fieldsObj = new JSONObject();

 for (Field field : fields) {
 field.setAccessible(true);
 Object val = field.get(null);
 if (val instanceof String) {
 fieldsObj.put(field.getName(), (String) val);
 } else if (val instanceof Method) {
 Method targetMethod = (Method) val;
 // Format signature string as expected by RestoreMethod
 fieldsObj.put(field.getName(), targetMethod.getDeclaringClass().getName() + "->" + targetMethod.getName());
 } else if (val instanceof Class) {
 Class<?> targetClass = (Class<?>) val;
 fieldsObj.put(field.getName(), targetClass.getName());
 }
 }

 if (fieldsObj.length() > 0) {
 JSONObject classObj = new JSONObject();
 classObj.put("type", typeName);
 classObj.put("fields", fieldsObj);
 root.put(descriptor, classObj);
 }
 }

 // Write dictionary to app's data directory (mirrors PairipLog path)
 File dictDir = new File("/data/data/" + packageName + "/dictionary");
 dictDir.mkdirs();
 File jsonFile = new File(dictDir, "pairip.json");

 try (FileOutputStream fos = new FileOutputStream(jsonFile)) {
 fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
 }

 Log.i(TAG, "pairip.json successfully dumped (" + root.length() + " classes) to: " + jsonFile.getAbsolutePath());

 } catch (Throwable t) {
 Log.e(TAG, "Failed to dump pairip.json", t);
 }
 }
}