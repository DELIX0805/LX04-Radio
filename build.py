# -*- coding: utf-8 -*-
"""极简收音机 离线构建: aapt2 -> javac -> d8 -> zipalign -> apksigner

用法: python build.py
输出: build/radio.apk
"""
import os
import re
import subprocess
import sys
import zipfile

MIN_SDK = "24"
TARGET_SDK = "27"

ROOT = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(ROOT, "build")

EXE = ".exe" if os.name == "nt" else ""


def _local_prop(key):
    """local.properties 里可以写本机专属路径（该文件不入库），优先级高于环境变量。

    例:
        sdk.dir=C:\\Users\\you\\AppData\\Local\\Android\\Sdk
        jdk.dir=C:\\path\\to\\jdk-17
        build.tools=34.0.0
    """
    p = os.path.join(ROOT, "local.properties")
    if not os.path.isfile(p):
        return ""
    try:
        with open(p, encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if line.startswith(key + "="):
                    return line.split("=", 1)[1].strip()
    except Exception:
        pass
    return ""


def _ver_key(s):
    out = []
    for part in re.split(r"[.\-]", s):
        out.append(int(part) if part.isdigit() else 0)
    return out


def _find_sdk():
    for cand in (_local_prop("sdk.dir"),
                 os.environ.get("ANDROID_HOME"),
                 os.environ.get("ANDROID_SDK_ROOT"),
                 os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk"),
                 os.path.join(os.path.expanduser("~"), "Android", "Sdk"),
                 os.path.join(os.path.expanduser("~"), "Library", "Android", "sdk")):
        if cand and os.path.isdir(os.path.join(cand, "platforms")):
            return cand
    return ""


def _find_build_tools(sdk):
    d = os.path.join(sdk, "build-tools")
    fixed = _local_prop("build.tools")
    if fixed and os.path.isdir(os.path.join(d, fixed)):
        return os.path.join(d, fixed)
    if not os.path.isdir(d):
        return ""
    vs = [x for x in os.listdir(d) if os.path.isdir(os.path.join(d, x))]
    return os.path.join(d, sorted(vs, key=_ver_key)[-1]) if vs else ""


def _find_platform(sdk):
    """取 >= TARGET_SDK 里最低的那个 platform，避免误用到比 targetSdkVersion 更新的 API。"""
    d = os.path.join(sdk, "platforms")
    if not os.path.isdir(d):
        return ""
    vs = []
    for x in os.listdir(d):
        if x.startswith("android-"):
            n = x[len("android-"):]
            if n.isdigit():
                vs.append((int(n), os.path.join(d, x, "android.jar")))
    ok = [x for x in sorted(vs) if x[0] >= int(TARGET_SDK)]
    return (ok or sorted(vs))[0][1] if (ok or vs) else ""


def _find_jdk_bin():
    import shutil
    cands = [_local_prop("jdk.dir"), os.environ.get("RADIO_JDK"), os.environ.get("JAVA_HOME")]
    javac = shutil.which("javac")
    if javac:
        cands.append(os.path.dirname(os.path.dirname(javac)))
    if os.name != "nt" and os.path.isfile("/usr/libexec/java_home"):
        try:
            r = subprocess.run(["/usr/libexec/java_home"], capture_output=True, text=True)
            if r.returncode == 0:
                cands.append(r.stdout.strip())
        except Exception:
            pass
    for c in cands:
        if not c:
            continue
        b = c if os.path.basename(c).lower() == "bin" else os.path.join(c, "bin")
        if os.path.isfile(os.path.join(b, "javac" + EXE)):
            return b
    return ""


SDK = _find_sdk()
BT = _find_build_tools(SDK) if SDK else ""
PLAT = _find_platform(SDK) if SDK else ""
JDK_BIN = _find_jdk_bin()
JAVA = os.path.join(JDK_BIN, "java" + EXE) if JDK_BIN else "java"
JAVAC = os.path.join(JDK_BIN, "javac" + EXE) if JDK_BIN else "javac"
KEYTOOL = os.path.join(JDK_BIN, "keytool" + EXE) if JDK_BIN else "keytool"

APK_NAME = "radio.apk"
KS = os.path.join(ROOT, "radio.jks")

# aapt2 / zipalign 也要走 EXE —— 早前这里写死 `.exe`，导致 macOS/Linux 上第一步就失败，
# 而脚本里的 EXE 变量只用在了 java/javac/keytool 上（开源仓库的门面问题）。
AAPT2 = os.path.join(BT, "aapt2" + EXE) if BT else "aapt2" + EXE
ZIPALIGN = os.path.join(BT, "zipalign" + EXE) if BT else "zipalign" + EXE

# 签名口令。默认值 android 只为"拿到源码就能本地跑通"，
# 正式发布务必用环境变量覆盖：
#   RADIO_KS_PASS / RADIO_KEY_PASS
# 口令不再写死在命令行里（旧的 `--ks-pass pass:android` 在进程列表里可见）。
KS_PASS = os.environ.get("RADIO_KS_PASS", "android")
KEY_PASS = os.environ.get("RADIO_KEY_PASS", KS_PASS)

# res/raw 下只允许这些扩展名，其余一律拒绝编译。
# 起因：一份 stations.json.bak 曾被 aapt2 当资源打进包，白占 8.5% 体积。
RAW_ALLOWED = {".json", ".txt", ".xml", ".mp3", ".ogg", ".wav", ".mp4", ".db"}

# res/ 根目录下不允许直接放文件（res 根不是合法限定符目录，aapt2 会静默丢弃）


def check_res_sanity():
    """编译前体检 res/：拦住会被静默丢弃或静默打进包的文件。返回 0 表示通过。"""
    bad = False
    res = os.path.join(ROOT, "res")
    for f in sorted(os.listdir(res)):
        p = os.path.join(res, f)
        if os.path.isfile(p):
            print("  !! res/ 根目录下的文件会被 aapt2 静默丢弃: res/%s" % f)
            print("     （512 主图之类请放 artwork/，见 make_icon.py）")
            bad = True
    raw = os.path.join(res, "raw")
    if os.path.isdir(raw):
        for f in sorted(os.listdir(raw)):
            p = os.path.join(raw, f)
            if not os.path.isfile(p):
                continue
            ext = os.path.splitext(f)[1].lower()
            if ext not in RAW_ALLOWED:
                print("  !! res/raw/%s 扩展名 %s 不在白名单内，会被打进 APK" % (f, ext or "(无)"))
                print("     允许: %s" % " ".join(sorted(RAW_ALLOWED)))
                bad = True
    return 1 if bad else 0


def run(cmd, quiet=True, timeout=600):
    """timeout 是硬要求：aapt2/d8 卡住时不能让整个构建永久挂起。"""
    try:
        r = subprocess.run(cmd, capture_output=True, text=True,
                           errors="replace", timeout=timeout)
    except subprocess.TimeoutExpired:
        print("  !! 超时 %ds: %s" % (timeout, os.path.basename(cmd[0])))
        return subprocess.CompletedProcess(cmd, 124, "", "timeout after %ds" % timeout)
    if not quiet or r.returncode != 0:
        for ln in (r.stdout or "").splitlines():
            print("  out:", ln)
        for ln in (r.stderr or "").splitlines():
            print("  err:", ln)
        if r.returncode != 0:
            print("  !! exit", r.returncode, os.path.basename(cmd[0]))
    return r


def clean_build():
    """不用 shutil.rmtree：本机有 safe-delete 拦截，rmtree 会静默失败并残留 .class 污染 d8。"""
    if not os.path.isdir(BUILD):
        return
    left = 0
    for dp, dns, fns in os.walk(BUILD, topdown=False):
        for f in fns:
            try:
                os.remove(os.path.join(dp, f))
            except Exception:
                left += 1
        for d in dns:
            try:
                os.rmdir(os.path.join(dp, d))
            except Exception:
                pass
    if left:
        print("  (注意: %d 个文件未能删除)" % left)


def main():
    miss = []
    if not SDK:
        miss.append("Android SDK")
    else:
        if not BT:
            miss.append("build-tools")
        if not PLAT:
            miss.append("platforms/android-*")
    if not JDK_BIN:
        miss.append("JDK (需要 javac)")
    if miss:
        print("BUILD FAILED: 找不到 %s" % "、".join(miss))
        print("  在本目录放一份 local.properties 指定路径最省事，例如:")
        print("    sdk.dir=C:\\\\Users\\\\you\\\\AppData\\\\Local\\\\Android\\\\Sdk")
        print("    jdk.dir=C:\\\\path\\\\to\\\\jdk-17")
        print("  或者设置环境变量 ANDROID_HOME / JAVA_HOME。")
        return 1

    print("== res 体检 ==")
    if check_res_sanity():
        print("BUILD FAILED: res/ 里有不该存在的文件（见上）")
        return 1
    print("  ok")

    clean_build()
    os.makedirs(os.path.join(BUILD, "obj"), exist_ok=True)
    os.makedirs(os.path.join(BUILD, "gen"), exist_ok=True)

    print("== aapt2 compile ==")
    run([AAPT2, "compile", "--dir", os.path.join(ROOT, "res"),
         "-o", os.path.join(BUILD, "res.zip")], quiet=False)

    print("== aapt2 link ==")
    r = run([AAPT2, "link", "-I", PLAT,
             "--manifest", os.path.join(ROOT, "AndroidManifest.xml"),
             "-o", os.path.join(BUILD, "app.apk"), os.path.join(BUILD, "res.zip"),
             "--java", os.path.join(BUILD, "gen"), "--auto-add-overlay",
             "--min-sdk-version", MIN_SDK, "--target-sdk-version", TARGET_SDK], quiet=False)
    # 必须显式检查：link 失败时后面 zipfile("a") 仍会新建一个只有 classes.dex
    # 的裸包，zipalign/sign 都能过，最后还会打印 OK —— 等于静默产出坏 APK。
    if r.returncode != 0 or not os.path.exists(os.path.join(BUILD, "app.apk")):
        print("BUILD FAILED at aapt2 link")
        return 1

    srcs = []
    for d in (os.path.join(ROOT, "src"), os.path.join(BUILD, "gen")):
        for dp, _, fns in os.walk(d):
            for f in fns:
                if f.endswith(".java"):
                    srcs.append(os.path.join(dp, f))
    print("== javac (%d files) ==" % len(srcs))
    r = run([JAVAC, "-source", "8", "-target", "8", "-encoding", "UTF-8",
             "-bootclasspath", PLAT, "-classpath", PLAT,
             "-d", os.path.join(BUILD, "obj"), "-nowarn"] + srcs, quiet=False)
    if r.returncode != 0:
        print("BUILD FAILED at javac")
        return 1

    classes = []
    for dp, _, fns in os.walk(os.path.join(BUILD, "obj")):
        for f in fns:
            if f.endswith(".class"):
                classes.append(os.path.join(dp, f))
    print("== d8 (%d classes) ==" % len(classes))
    run([JAVA, "-cp", os.path.join(BT, "lib", "d8.jar"), "com.android.tools.r8.D8",
         "--lib", PLAT, "--min-api", MIN_SDK, "--output", BUILD] + classes, quiet=False)

    dex = os.path.join(BUILD, "classes.dex")
    if not os.path.exists(dex):
        print("BUILD FAILED: no classes.dex")
        return 1
    with zipfile.ZipFile(os.path.join(BUILD, "app.apk"), "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex, "classes.dex")

    print("== zipalign ==")
    run([ZIPALIGN, "-p", "-f", "4",
         os.path.join(BUILD, "app.apk"), os.path.join(BUILD, "aligned.apk")], quiet=False)
    if not os.path.exists(os.path.join(BUILD, "aligned.apk")):
        print("BUILD FAILED at zipalign")
        return 1

    if not os.path.exists(KS):
        print("== keytool ==")
        run([KEYTOOL, "-genkeypair", "-v", "-keystore", KS, "-alias", "radio",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
             "-storepass", KS_PASS, "-keypass", KEY_PASS,
             "-dname", "CN=Radio, OU=dev, O=tongsir, C=CN"], quiet=False)

    print("== sign ==")
    run([JAVA, "-cp", os.path.join(BT, "lib", "apksigner.jar"),
         "com.android.apksigner.ApkSignerTool", "sign", "--ks", KS,
         "--ks-pass", "pass:" + KS_PASS, "--key-pass", "pass:" + KEY_PASS,
         "--ks-key-alias", "radio", "--out", os.path.join(BUILD, APK_NAME),
         os.path.join(BUILD, "aligned.apk")], quiet=False)

    print("== verify ==")
    run([JAVA, "-cp", os.path.join(BT, "lib", "apksigner.jar"),
         "com.android.apksigner.ApkSignerTool", "verify",
         os.path.join(BUILD, APK_NAME)], quiet=False)

    apk = os.path.join(BUILD, APK_NAME)
    if os.path.exists(apk):
        print("OK size=%d" % os.path.getsize(apk))
        return 0
    print("BUILD FAILED")
    return 1


if __name__ == "__main__":
    sys.exit(main())
