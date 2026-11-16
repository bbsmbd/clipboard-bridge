@echo off
chcp 65001 >nul
title Clipboard Bridge V1 - phone install
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=C:\Users\BST90\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" (
  echo adb.exe paoa jayni. Android Studio SDK Manager theke "Android SDK Platform-Tools" install korun.
  pause & exit /b 1
)
echo.
echo  1. Phone USB diye PC-te lagan
echo  2. Phone-e: Settings - Developer options - USB debugging ON
echo  3. Phone-e "Allow USB debugging?" asle Allow chapun
echo.
"%ADB%" wait-for-device
"%ADB%" devices -l
echo.
echo ===== Phone info =====
"%ADB%" shell getprop ro.product.model
"%ADB%" shell getprop ro.build.version.release
echo.
echo ===== Install (main APK) =====
"%ADB%" install -r "%~dp0..\release\ClipboardBridge-V1.apk" > "%TEMP%\cb-install-log.txt" 2>&1
type "%TEMP%\cb-install-log.txt"
findstr /C:"Success" "%TEMP%\cb-install-log.txt" >nul && goto ok
echo.
echo ===== Old app conflict hole: uninstall old, then retry =====
"%ADB%" uninstall com.clipboardbridge >> "%TEMP%\cb-install-log.txt" 2>&1
"%ADB%" install "%~dp0..\release\ClipboardBridge-V1.apk" >> "%TEMP%\cb-install-log.txt" 2>&1
findstr /C:"Success" "%TEMP%\cb-install-log.txt" >nul && goto ok
echo.
echo INSTALL FAILED. Log: %TEMP%\cb-install-log.txt
pause & exit /b 1
:ok
echo.
echo ===== Auto-copy permission (READ_LOGS) =====
"%ADB%" shell pm grant com.clipboardbridge android.permission.READ_LOGS
"%ADB%" shell monkey -p com.clipboardbridge -c android.intent.category.LAUNCHER 1 >nul 2>&1
echo Phone-e "Allow access to all device logs?" asle  Allow  chapun.
echo.
echo ===== SUCCESS: Clipboard Bridge phone-e install hoyeche =====
pause
