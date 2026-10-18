#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="$ROOT_DIR/Android-Port/vendor/SoftEther-Android-Module"
REPOSITORY="https://github.com/hoang-rio/SoftEther-Android-Module.git"
REF="${SOFTETHER_ANDROID_MODULE_REF:-main}"

mkdir -p "$(dirname "$MODULE_DIR")"
if [[ ! -d "$MODULE_DIR/.git" ]]; then
  rm -rf "$MODULE_DIR"
  git clone --depth 1 --branch "$REF" --recurse-submodules "$REPOSITORY" "$MODULE_DIR"
else
  echo "Native module already exists: $MODULE_DIR"
  echo "To update it, delete that directory and run this script again."
fi

git -C "$MODULE_DIR" submodule update --init --recursive
python3 "$ROOT_DIR/scripts/prepare-softether-module.py" "$MODULE_DIR"
echo "Native SoftEther module is ready. Open Android-Port in Android Studio or run gradle assembleDebug."
