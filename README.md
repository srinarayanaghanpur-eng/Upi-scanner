# UPI QR Scanner (Kotlin, Camera2 + ML Kit)

## Build
Open this folder in Android Studio (Koala or newer, JDK 17). Let it sync, then:
- Build > Build APK(s)  -> app/build/outputs/apk/debug/app-debug.apk
- Or: ./gradlew assembleDebug assembleRelease
  -> app/build/outputs/apk/release/app-release.apk
(If gradlew is missing, Studio regenerates it, or run `gradle wrapper --gradle-version 8.7`.)
Release is signed with the debug key so it installs for personal use. For your own key:
`keytool -genkey -v -keystore my.jks -alias k -keyalg RSA -keysize 2048 -validity 10000`
then add a signingConfig in app/build.gradle.kts.

## Install on the M51
Enable USB debugging, then `adb install -r app-debug.apk` — or copy the APK to the phone, open it, allow "install unknown apps".

## First run on the M51 (important)
1. Grant camera permission.
2. Tap ⓘ. It lists every back camera ID Samsung exposes, focal length, 35mm-equivalent, MP, AF.
3. For each lens use "Test" to preview it, then "→ 1× / → 0.5× / → Macro" to assign. Choices persist.
4. "Copy report" gives the full dump if you want the auto-mapping tuned.
If a lens isn't exposed by Samsung to third-party apps, its button shows an error — the app never substitutes another camera or fakes it with zoom.
