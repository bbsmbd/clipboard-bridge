#!/bin/bash
# Builds ClipboardBridge-V1.apk without Gradle: aapt -> javac -> dx -> zipalign -> apksigner
set -e
cd "$(dirname "$0")"
SDK35=${SDK35:-/opt/asdk/android-34.jar}
SDK23=${SDK23:-/usr/lib/android-sdk/platforms/android-23/android.jar}
KS=${KS:-../signing/clipboardbridge-v1.jks}
OUT=build
rm -rf $OUT && mkdir -p $OUT/gen $OUT/cls $OUT/chk
aapt package -f -m -J $OUT/gen -M AndroidManifest.xml -S res -I "$SDK35" \
     --min-sdk-version 23 --target-sdk-version 35 --version-code 110 --version-name 1.1.0-V1 -F $OUT/res.apk
SRC=$(find src -name '*.java')
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$SDK35" -cp "$SDK35" -d $OUT/cls $SRC $(find $OUT/gen -name '*.java')
# API check: everything except Api.java / ClipTileService.java must compile against Android 6 (API 23)
CHK=$(find src -name '*.java' ! -name Api.java ! -name ClipTileService.java)
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$SDK23" -cp "$SDK23:$OUT/cls" -d $OUT/chk $CHK $(find $OUT/gen -name '*.java')
echo "API-23 compatibility check passed"
dalvik-exchange --dex --min-sdk-version=23 --output=$OUT/classes.dex $OUT/cls
cp $OUT/res.apk $OUT/unsigned.apk
(cd $OUT && zip -q -j unsigned.apk classes.dex)
zipalign -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk
apksigner sign --ks "$KS" --ks-pass pass:"${KS_PASS:?set KS_PASS to the keystore password}" --key-pass pass:"${KS_PASS}" \
     --min-sdk-version 23 --out $OUT/ClipboardBridge-V1.apk $OUT/aligned.apk
apksigner verify --verbose $OUT/ClipboardBridge-V1.apk | head -5
