#!/bin/sh
# Installe (ou met à jour) le launcher sur un Fire TV et en fait l'écran d'accueil.
# Usage : FIRE_TV=<ip du stick> scripts/fire-tv/installer.sh   (compiler avant : cd launcher && ./gradlew assembleDebug)
set -e
: "${FIRE_TV:?indiquer FIRE_TV (adresse IP du stick), ex. FIRE_TV=192.168.1.50}"
APK="$(dirname "$0")/../../launcher/app/build/outputs/apk/debug/app-debug.apk"
export ANDROID_SERIAL="$FIRE_TV:5555"; adb connect "$ANDROID_SERIAL" >/dev/null
adb install -r "$APK"
adb shell pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS
# Une mise à jour délie le service d'accessibilité : on le réactive à chaque fois.
adb shell settings put secure enabled_accessibility_services com.gathin.tvhome/com.gathin.tvhome.HomeRedirectService
adb shell settings put secure accessibility_enabled 1
adb shell settings put secure screensaver_components com.gathin.tvhome/com.gathin.tvhome.PhotoDream
adb shell am start -n com.gathin.tvhome/.MainActivity
