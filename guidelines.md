# Android project guidelines

Conventions and hard-won gotchas from BlueRemind, reusable for any Java Android app in
`/home/legitcoconut/projects/`. Read the toolchain section first if you are a fresh agent:
nothing here is discoverable from a project's files alone.

## Where everything is

No Android Studio. Everything is CLI, installed once and shared by every project. Nothing is on
PATH in a non-interactive shell, so use these absolute paths.

| Tool | Absolute path |
|---|---|
| JDK 17, `JAVA_HOME` | `/usr/lib/jvm/java-17-openjdk-amd64` |
| `java`, `keytool` | `/usr/lib/jvm/java-17-openjdk-amd64/bin/` |
| Android SDK, `ANDROID_HOME` | `/home/legitcoconut/projects/tools/android-sdk` |
| `adb` | `/home/legitcoconut/projects/tools/android-sdk/platform-tools/adb` |
| `sdkmanager` | `/home/legitcoconut/projects/tools/android-sdk/cmdline-tools/latest/bin/sdkmanager` |
| `apksigner`, `aapt2`, `zipalign` | `/home/legitcoconut/projects/tools/android-sdk/build-tools/34.0.0/` |
| `gradle` | `/home/legitcoconut/projects/tools/gradle-8.7/bin/gradle` |

The SDK holds `cmdline-tools/latest`, `platform-tools`, `platforms/android-34` and
`build-tools/34.0.0`. Licenses are already accepted, so no build will stop to prompt.

Nothing else is installed. There is no NDK, no CMake, no emulator image and no Kotlin.

`~/.bashrc` exports `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `GRADLE_HOME` and puts
`sdkmanager`, `adb` and `gradle` on PATH. A non-interactive shell may not source it, so set them
inline when scripting:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/home/legitcoconut/projects/tools/android-sdk
/home/legitcoconut/projects/tools/gradle-8.7/bin/gradle -p /path/to/project assembleDebug
```

Always pass `-p <project-dir>`. There is no Gradle wrapper committed, so Gradle resolves the
project from the current directory and a stale shell cwd will fail with "not part of the build".

Each project needs `local.properties` with `sdk.dir=/home/legitcoconut/projects/tools/android-sdk`.
It is gitignored, so a fresh clone must recreate it.

Add SDK components with `sdkmanager`, never apt. Distro Gradle packages are too old for AGP 8.

## Versions that work together

```
JDK 17 · Gradle 8.7 · AGP 8.5.2 · compileSdk 34 · targetSdk 34 · minSdk 26
com.google.android.material:material:1.12.0
androidx.activity:activity:1.9.0
```

`minSdk 26` is deliberate: notification channels and adaptive icons exist, which removes a pile
of legacy branching. Do not lower it without a reason.

## Theme

Material 3, dynamic colour, dark and light both free:

```xml
<style name="Theme.App" parent="Theme.Material3.DayNight.NoActionBar">
    <item name="android:statusBarColor">?attr/colorSurface</item>
    <item name="android:navigationBarColor">?attr/colorSurface</item>
</style>
```

Plus `DynamicColors.applyToActivitiesIfAvailable(this)` in an `Application` subclass. Use that
rather than a `Theme.Material3.DynamicColors.*` parent: the documented call is guaranteed to
exist and leaves you free to pick the `NoActionBar` variant.

**Never hardcode a colour in a layout.** Use theme attributes so dynamic colour and dark mode
work for free:

| Use | Attribute |
|---|---|
| Page and card background | `?attr/colorSurface` |
| Accent, active switch, section titles | `?attr/colorPrimary` |
| Body text | default, or `?attr/colorOnSurface` |
| Secondary text, captions | `?attr/colorOnSurfaceVariant` |
| Icon tint, neutral | `?attr/colorControlNormal` |

Text sizes come from `?attr/textAppearanceTitleMedium`, `?attr/textAppearanceBodySmall` and
friends. No `android:textSize` in sp.

Launcher icon: adaptive icon XML in `mipmap-anydpi-v26/` with a vector foreground, a colour
background and a `<monochrome>` entry so Android 13+ themed icons work. No PNGs needed.

## Cards

```xml
<com.google.android.material.card.MaterialCardView
    style="?attr/materialCardViewFilledStyle"
    app:cardCornerRadius="20dp">
```

- `materialCardViewFilledStyle` for list rows, `materialCardViewElevatedStyle` for things that
  should stand out (a "connected now" grid, for example).
- Corner radius 20dp for rows, 24dp for feature cards.
- Inner padding 16dp horizontal, 14dp vertical.
- Set `android:clickable="true"` and `android:focusable="true"` only when the card really has a
  click listener, otherwise you get a ripple that leads nowhere.

### Card spacing inside a ListView

`ListView` uses `AbsListView.LayoutParams`, which **has no margin fields**. `layout_margin` on a
row is silently discarded. Space rows with a transparent divider instead:

```xml
<ListView
    android:divider="@android:color/transparent"
    android:dividerHeight="10dp"
    android:clipToPadding="false"
    android:paddingHorizontal="12dp" />
```

### Images on cards

Show the whole picture, never a crop. `ImageView` with `scaleType="fitCenter"`, no
`android:background`, so a transparent PNG sits flush on the card surface. When binding, set
**both** branches explicitly or recycling bleeds state across rows:

```java
view.setScaleType(ImageView.ScaleType.FIT_CENTER);
if (photo != null) {
    view.setImageTintList(null);       // else the glyph tint floods the photo
    view.setPadding(0, 0, 0, 0);
    view.setImageBitmap(photo);
} else {
    view.setImageTintList(ColorStateList.valueOf(
            MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary)));
    view.setPadding(pad, pad, pad, pad);
    view.setImageResource(R.drawable.ic_placeholder);
}
```

Store user-picked images downscaled to a 384px longest side, aspect ratio kept, as PNG in
`getFilesDir()`, so gallery deletions cannot break them and transparency survives.

### Switches in a recycled row

Detach the listener before setting state, always:

```java
sw.setOnCheckedChangeListener(null);
sw.setChecked(isOn(address));
sw.setOnCheckedChangeListener((b, on) -> save(address, on));
```

## Layout gotchas that cost real debugging time

- **`ListView` measures header views even when `GONE`.** Only a `ViewGroup` parent honours GONE
  for its children. Keep the header root visible and collapse an inner `LinearLayout` instead,
  otherwise a hidden header still reserves its full height.
- **`GridLayout` sizes columns globally.** With `columnCount=2` and a single child, column 1 never
  exists so column 0 takes the full width. Add a zero-width `Space` in column 1 to force a 50/50
  split. Odd counts above 1 are already fine.
- Free entry animation with no code: an `anim/` layout animation set on the list plus
  `list.scheduleLayoutAnimation()` after data changes.
- Animated assets: `AnimatedVectorDrawable` with `objectAnimator` on a named group covers pulsing
  and fading. No Lottie, no dependency.

## Bluetooth notes

- Paired list is `BluetoothAdapter.getBondedDevices()`. Needs `BLUETOOTH_CONNECT` at runtime on
  API 31+, which is also required to *receive* ACL broadcasts.
- `ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` are on the implicit-broadcast exception
  list, so a manifest receiver still wakes on Android 8+. No foreground service needed.
- **`BluetoothManager.getConnectedDevices(GATT)` only reports GATT connections owned by your own
  app.** It will never find a watch someone else's app connected. The only call that answers
  "is the ACL link up" for any profile is the hidden `BluetoothDevice.isConnected()`, reached by
  reflection with a graceful fallback.
- Profile proxies (`A2DP`, `HEADSET`) cover audio gear only.

## Other platform notes

- Photo picking: `ActivityResultContracts.PickVisualMedia`. System photo picker on Android 13+,
  `OPEN_DOCUMENT` below. **No storage permission at all.** Register the launcher as a field, not
  in a callback, since it must exist before `onStart`.
- `POST_NOTIFICATIONS` is runtime on API 33+. Wrap `notify()` in a try/catch for `SecurityException`
  and let non-notification side effects such as vibration still run.
- Notification channels are created idempotently, so just call `createNotificationChannel` before
  posting rather than tracking whether you have done it.

## Building

```bash
gradle -p <dir> assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
gradle -p <dir> assembleRelease   # app/build/outputs/apk/release/app-release.apk
adb install -r <apk>
```

Release needs signing. Generate once per app:

```bash
keytool -genkeypair -keystore app-release.jks -alias app \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PW" -keypass "$PW" -dname "CN=LegitCoconut, O=LegitCoconut"
```

Credentials go in a gitignored `keystore.properties` at the repo root, read from `build.gradle`
with an `exists()` guard so a clone without it still builds, unsigned, instead of failing.

**Back up the keystore.** Losing it means never being able to ship an update that installs over
the existing app.

Enable `minifyEnabled true` and `shrinkResources true` for release. On BlueRemind that took the
APK from 5.5 MB to 1.7 MB. AGP generates keep rules from the manifest and layouts automatically,
and reflection against framework classes is safe because those are never in your dex.

Do **not** add `abiFilters` or ABI splits to a pure Java app. With no `.so` files the APK is
already architecture-independent and filtering an empty set changes nothing. Verify with
`unzip -l app.apk | grep lib/`.

**A debug build cannot be upgraded to a release build.** Different signing keys means
`adb install -r` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall first, which wipes
app data. There is no way around this short of root.

## Writing style for docs and UI copy

No em dashes or en dashes anywhere, in prose, comments or UI strings. Use a full stop, a comma or
a colon. Keep sentences short and cut any sentence that only defends a decision.
