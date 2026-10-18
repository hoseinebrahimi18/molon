$ErrorActionPreference = 'Stop'
$RootDir = Split-Path -Parent $PSScriptRoot
$ModuleDir = Join-Path $RootDir 'Android-Port/vendor/SoftEther-Android-Module'
$Repo = 'https://github.com/hoang-rio/SoftEther-Android-Module.git'
$Ref = if ($env:SOFTETHER_ANDROID_MODULE_REF) { $env:SOFTETHER_ANDROID_MODULE_REF } else { 'main' }

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ModuleDir) | Out-Null
if (-not (Test-Path (Join-Path $ModuleDir '.git'))) {
    if (Test-Path $ModuleDir) { Remove-Item -Recurse -Force $ModuleDir }
    git clone --depth 1 --branch $Ref --recurse-submodules $Repo $ModuleDir
    if ($LASTEXITCODE -ne 0) { throw 'Cloning the SoftEther Android module failed.' }
} else {
    Write-Host "Native module already exists: $ModuleDir"
    Write-Host 'To update it, delete that directory and run this script again.'
}

git -C $ModuleDir submodule update --init --recursive
if ($LASTEXITCODE -ne 0) { throw 'Initializing SoftEther/OpenSSL submodules failed.' }
python (Join-Path $RootDir 'scripts/prepare-softether-module.py') $ModuleDir
if ($LASTEXITCODE -ne 0) { throw 'Preparing the native module failed.' }
Write-Host 'Native SoftEther module is ready. Open Android-Port in Android Studio or run gradle assembleDebug.'
