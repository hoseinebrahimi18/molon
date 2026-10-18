#!/usr/bin/env python3
"""Fast static checks for the OpenSSTP Android port (not a substitute for an APK build)."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "Android-Port"
SERVICE = APP / "app/src/main/java/com/opensstp/android/SoftEtherVpnService.kt"
ACTIVITY = APP / "app/src/main/java/com/opensstp/android/MainActivity.kt"
MANIFEST = APP / "app/src/main/AndroidManifest.xml"
SETTINGS = APP / "settings.gradle.kts"
APP_GRADLE = APP / "app/build.gradle.kts"

for path in (SERVICE, ACTIVITY, MANIFEST, SETTINGS, APP_GRADLE):
    if not path.is_file():
        raise SystemExit(f"ERROR: required project file is missing: {path.relative_to(ROOT)}")

try:
    xml_root = ET.parse(MANIFEST).getroot()
except ET.ParseError as exc:
    raise SystemExit(f"ERROR: AndroidManifest.xml is invalid: {exc}")
android_ns = "{http://schemas.android.com/apk/res/android}"
permissions = {item.attrib.get(android_ns + "name") for item in xml_root.findall("uses-permission")}
required_permissions = {
    "android.permission.INTERNET",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.WAKE_LOCK",
}
missing = required_permissions - permissions
if missing:
    raise SystemExit("ERROR: missing Android permissions: " + ", ".join(sorted(missing)))
services = xml_root.find("application").findall("service")
if not any(s.attrib.get(android_ns + "name", "").endswith("SoftEtherVpnService")
           and s.attrib.get(android_ns + "permission") == "android.permission.BIND_VPN_SERVICE"
           for s in services):
    raise SystemExit("ERROR: SoftEtherVpnService must be declared as an Android VpnService")

service = SERVICE.read_text(encoding="utf-8")
activity = ACTIVITY.read_text(encoding="utf-8")
settings = SETTINGS.read_text(encoding="utf-8")
app_gradle = APP_GRADLE.read_text(encoding="utf-8")
checks = {
    "native SoftEther session API": "nativeConnectWithHub" in service,
    "native socket protection": "protectNativeSockets" in service and "protect(fd)" in service,
    "DHCP fail-closed handling": "doDhcp(handle)" in service and "DHCP" in service,
    "Android TUN interface": "Builder()" in service and 'addRoute("0.0.0.0", 0)' in service,
    "bidirectional native packet bridge": "nativeSendSlice" in service and "nativeReceive" in service,
    "encrypted Remember me preferences": "EncryptedSharedPreferences" in activity and "MasterKey" in activity,
    "external Android SoftEther module": 'include(":softether-client")' in settings,
    "module dependency in app": 'implementation(project(":softether-client"))' in app_gradle,
}
failed = [name for name, okay in checks.items() if not okay]
for name, okay in checks.items():
    print(("PASS" if okay else "FAIL") + ": " + name)
if failed:
    raise SystemExit("Static validation failed: " + ", ".join(failed))
print("Static validation succeeded. Android Gradle/NDK build and live VPN testing are separate checks.")
