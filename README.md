# Thanima Ticketing, staff app

The Android app door staff use: sign in, pick an event, then scan tickets, mark attendance,
run food sessions and track unpaid entries. Java only, no Kotlin.

## What you need

- **JDK 17**
- **Android SDK**, platform 34 and build tools 34.0.0
- **Gradle 8.7**. There is no wrapper in this repo, so `./gradlew` will not work

`minSdk` is 26. Android Studio is not required.

## Setup

Create `local.properties` in the project root:

```properties
sdk.dir=/path/to/android-sdk
```

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
```

## Debug build

```bash
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Application id is `com.legitcoconut.thanimaticketing.debug`, so it installs alongside the
release build.

## Release build

Minified, resource shrunk and signed. Make a keystore once:

```bash
keytool -genkeypair -v -keystore thanima-release.jks -alias thanima \
  -keyalg RSA -keysize 4096 -validity 10000
```

Back it up. If the key is lost, Android will not let you update an installed copy of the app.

Then create `keystore.properties` in the project root:

```properties
storeFile=thanima-release.jks
storePassword=...
keyAlias=thanima
keyPassword=...
```

```bash
gradle assembleRelease
```

Output is `app/build/outputs/apk/release/app-release.apk`. Without `keystore.properties` the
build still succeeds but emits an unsigned APK, which cannot be installed.

## Notes

- Server URL: `gradle assembleDebug -PBASE_URL=https://your-server.example/api`, or change it
  on the login screen. Plain HTTP is allowed, so a LAN address works at a venue.
- Tests: `gradle testDebugUnitTest`
- Builds are **arm64 only**, so the x86 emulator cannot install them. Use an arm64 image or a
  real phone.
