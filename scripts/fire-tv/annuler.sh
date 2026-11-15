#!/bin/sh
# Remet l'accueil et l'économiseur d'écran d'Amazon, puis désinstalle le launcher.
# Usage : FIRE_TV=<ip du stick> scripts/fire-tv/annuler.sh
: "${FIRE_TV:?indiquer FIRE_TV (adresse IP du stick), ex. FIRE_TV=192.168.1.50}"
export ANDROID_SERIAL="$FIRE_TV:5555"; adb connect "$ANDROID_SERIAL" >/dev/null
adb shell settings delete secure enabled_accessibility_services   # aucun service n'était actif avant
adb shell settings put secure accessibility_enabled 0
adb shell settings put secure screensaver_components com.amazon.ftv.screensaver/.app.services.ScreensaverService
adb shell pm uninstall com.gathin.tvhome
adb shell input keyevent KEYCODE_HOME
