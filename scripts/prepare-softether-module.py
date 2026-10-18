#!/usr/bin/env python3
"""Prepare the external SoftEther Android module for this app's AGP/Kotlin versions."""
from __future__ import annotations
import pathlib
import re
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: prepare-softether-module.py <path-to-SoftEther-Android-Module>")
root = pathlib.Path(sys.argv[1]).resolve()
gradle_file = root / "build.gradle"
cmake = root / "src/main/cpp/CMakeLists.txt"

for required in (gradle_file, cmake, root / "src/main/java/vn/unlimit/softether/client/SoftEtherClient.kt"):
    if not required.is_file():
        raise SystemExit(f"SoftEther Android module is incomplete; missing: {required}")

text = gradle_file.read_text(encoding="utf-8")
plugins_pattern = re.compile(r"plugins\s*\{(?P<body>.*?)\}", re.DOTALL)
match = plugins_pattern.search(text)
if not match:
    raise SystemExit("Could not locate the upstream module's Gradle plugins block")
body = match.group("body")
if "org.jetbrains.kotlin.android" not in body and "kotlin-android" not in body:
    body = body.rstrip() + "\n    id 'org.jetbrains.kotlin.android'\n"
    text = text[:match.start("body")] + body + text[match.end("body"):]
    gradle_file.write_text(text, encoding="utf-8")
    print("Applied Kotlin Android plugin for compatibility with AGP 8.x.")
else:
    print("Kotlin Android plugin is already configured.")

# Prevent the upstream native-test library from being included in the ordinary app build.
# It is not part of the application's data path and may require platform-specific test helpers.
cmake_text = cmake.read_text(encoding="utf-8")
test_target_pattern = re.compile(r"add_library\(\s*softether_test\s+SHARED(?!\s+EXCLUDE_FROM_ALL)")
if test_target_pattern.search(cmake_text):
    cmake_text = test_target_pattern.sub("add_library(softether_test SHARED EXCLUDE_FROM_ALL", cmake_text, count=1)
    cmake.write_text(cmake_text, encoding="utf-8")
    print("Excluded upstream native-test shared library from standard APK builds.")

# Keep native source and license files intact. The consuming root project supplies the
# compileSdk/minSdk/targetSdk extra properties expected by this library's original build file.
print(f"Prepared native SoftEther module at: {root}")
