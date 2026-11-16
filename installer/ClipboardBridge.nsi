; Clipboard Bridge V1 - Windows installer (NSIS). Per-user install: no admin needed.
; Build: makensis ClipboardBridge.nsi   (copy the built ClipboardBridge-V1.exe next to this file first)
Unicode true
Target amd64-unicode
!include "MUI2.nsh"

!define APPNAME   "Clipboard Bridge"
!define APPVER    "1.1.0"
!define PUBLISHER "Tarek Ahmed"
!define EXENAME   "ClipboardBridge-V1.exe"
!define UNINSTKEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\ClipboardBridgeV1"

Name "${APPNAME}"
OutFile "ClipboardBridge-V1-Setup.exe"
InstallDir "$LOCALAPPDATA\Programs\Clipboard Bridge"
InstallDirRegKey HKCU "Software\ClipboardBridgeV1" "InstallDir"
RequestExecutionLevel user
SetCompressor /SOLID lzma
BrandingText "${APPNAME} ${APPVER}  -  Developed by ${PUBLISHER}"

VIProductVersion "1.1.0.0"
VIAddVersionKey "ProductName" "${APPNAME}"
VIAddVersionKey "CompanyName" "${PUBLISHER}"
VIAddVersionKey "LegalCopyright" "Copyright (C) 2026 ${PUBLISHER}"
VIAddVersionKey "FileDescription" "${APPNAME} Setup"
VIAddVersionKey "FileVersion" "${APPVER}"
VIAddVersionKey "ProductVersion" "${APPVER}"

!define MUI_ICON "..\pc-app\res\app.ico"
!define MUI_UNICON "..\pc-app\res\app.ico"
!define MUI_ABORTWARNING
!define MUI_WELCOMEPAGE_TEXT "This will install ${APPNAME} on your computer.$\r$\n$\r$\nCopy on the PC and paste on your Android phone, and copy on the phone and paste on the PC - over your own WiFi, encrypted, no cloud.$\r$\n$\r$\nThe app starts automatically with Windows and runs in the system tray.$\r$\n$\r$\nDeveloped by ${PUBLISHER}."
!define MUI_FINISHPAGE_RUN "$INSTDIR\${EXENAME}"
!define MUI_FINISHPAGE_RUN_TEXT "Start ${APPNAME} now"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Section "Install"
  ; stop a running copy so the file can be replaced (old Python build too)
  nsExec::Exec 'taskkill /F /IM ${EXENAME}'
  nsExec::Exec 'taskkill /F /IM clipboard_bridge_pc.exe'
  Sleep 800
  SetOutPath "$INSTDIR"
  File "${EXENAME}"
  File "/oname=app.ico" "..\pc-app\res\app.ico"
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  CreateDirectory "$SMPROGRAMS\${APPNAME}"
  CreateShortcut "$SMPROGRAMS\${APPNAME}\${APPNAME}.lnk" "$INSTDIR\${EXENAME}" "" "$INSTDIR\app.ico"
  CreateShortcut "$SMPROGRAMS\${APPNAME}\Uninstall ${APPNAME}.lnk" "$INSTDIR\Uninstall.exe"
  CreateShortcut "$DESKTOP\${APPNAME}.lnk" "$INSTDIR\${EXENAME}" "" "$INSTDIR\app.ico"

  ; start with Windows (hidden in tray); the app keeps this entry up to date itself
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "ClipboardBridgeV1" '"$INSTDIR\${EXENAME}" --tray'
  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "ClipboardBridge"
  WriteRegStr HKCU "Software\ClipboardBridgeV1" "InstallDir" "$INSTDIR"

  ; Apps & features entry
  WriteRegStr HKCU "${UNINSTKEY}" "DisplayName" "${APPNAME}"
  WriteRegStr HKCU "${UNINSTKEY}" "DisplayVersion" "${APPVER}"
  WriteRegStr HKCU "${UNINSTKEY}" "Publisher" "${PUBLISHER}"
  WriteRegStr HKCU "${UNINSTKEY}" "DisplayIcon" "$INSTDIR\app.ico"
  WriteRegStr HKCU "${UNINSTKEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINSTKEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegDWORD HKCU "${UNINSTKEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINSTKEY}" "NoRepair" 1
  WriteRegDWORD HKCU "${UNINSTKEY}" "EstimatedSize" 700
SectionEnd

Section "Uninstall"
  nsExec::Exec 'taskkill /F /IM ${EXENAME}'
  Sleep 800
  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "ClipboardBridgeV1"
  DeleteRegKey HKCU "${UNINSTKEY}"
  DeleteRegKey HKCU "Software\ClipboardBridgeV1"
  ; firewall rules were added by the app with admin rights: remove them the same way (one Yes prompt)
  ExecShell "runas" "cmd.exe" '/c netsh advfirewall firewall delete rule name="ClipboardBridge V1 TCP" & netsh advfirewall firewall delete rule name="ClipboardBridge V1 UDP" & netsh advfirewall firewall delete rule name="ClipboardBridge V1 App"' SW_HIDE
  Delete "$DESKTOP\${APPNAME}.lnk"
  RMDir /r "$SMPROGRAMS\${APPNAME}"
  Delete "$INSTDIR\${EXENAME}"
  Delete "$INSTDIR\app.ico"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  ; settings (PIN, paired phones) in %APPDATA%\ClipboardBridgeV1 are kept for a later reinstall
SectionEnd
