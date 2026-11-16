@echo off
chcp 65001 >nul
title Clipboard Bridge - DIAGNOSE
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=C:\Users\BST90\AppData\Local\Android\Sdk\platform-tools\adb.exe"
set "OUT=%USERPROFILE%\Desktop\clipboard-bridge-diag.txt"
echo Phone USB diye lagan (USB debugging ON)...
"%ADB%" wait-for-device
> "%OUT%" echo ===== device =====
"%ADB%" shell getprop ro.product.manufacturer >> "%OUT%" 2>&1
"%ADB%" shell getprop ro.product.model >> "%OUT%" 2>&1
"%ADB%" shell getprop ro.build.version.release >> "%OUT%" 2>&1
"%ADB%" shell getprop ro.build.version.sdk >> "%OUT%" 2>&1
"%ADB%" shell getprop ro.build.display.id >> "%OUT%" 2>&1
>> "%OUT%" echo ===== app version =====
"%ADB%" shell dumpsys package com.clipboardbridge | findstr /C:"versionName" /C:"versionCode" >> "%OUT%" 2>&1
>> "%OUT%" echo ===== grant READ_LOGS =====
"%ADB%" shell pm grant com.clipboardbridge android.permission.READ_LOGS >> "%OUT%" 2>&1
>> "%OUT%" echo grant exit=%ERRORLEVEL%
"%ADB%" shell dumpsys package com.clipboardbridge | findstr /C:"READ_LOGS: granted=true" >nul
if errorlevel 1 (
  echo.
  echo  *** OnePlus: Settings - System - Developer options -
  echo  ***   "Disable permission monitoring" ON korun, tarpor ei file abar chalan.
  echo.
)
"%ADB%" shell dumpsys package com.clipboardbridge | findstr /C:"READ_LOGS" >> "%OUT%" 2>&1
>> "%OUT%" echo ===== accessibility =====
"%ADB%" shell settings get secure enabled_accessibility_services >> "%OUT%" 2>&1
"%ADB%" shell monkey -p com.clipboardbridge -c android.intent.category.LAUNCHER 1 >nul 2>&1
"%ADB%" logcat -c
echo.
echo ============================================================
echo  Phone-e "Allow access to all device logs?" asle Allow din.
echo  Ekhon phone-e Home-e jan, Chrome/WhatsApp-e 3 bar
echo  alada alada lekha COPY korun (protibar 5 second por por).
echo  Shesh hole ekhane jekono key chapun.
echo ============================================================
pause >nul
>> "%OUT%" echo ===== logcat =====
"%ADB%" logcat -d -v time ClipboardService:V ClipboardOverlay:V ActivityTaskManager:I ActivityManager:W AndroidRuntime:E *:S >> "%OUT%" 2>&1
>> "%OUT%" echo ===== dumpsys accessibility =====
"%ADB%" shell dumpsys accessibility >> "%OUT%" 2>&1
>> "%OUT%" echo ===== app process =====
"%ADB%" shell pidof com.clipboardbridge >> "%OUT%" 2>&1
"%ADB%" shell dumpsys activity services com.clipboardbridge | findstr /I "ServiceRecord app= isForeground" >> "%OUT%" 2>&1
>> "%OUT%" echo ===== app log lines =====
"%ADB%" logcat -d -v time | findstr /I "clipboardbridge" >> "%OUT%" 2>&1
echo.
echo Hoye geche. Claude-ke "done" likhun.
pause
