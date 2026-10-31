# Nuvio Flow — Stage 1 build notes

This branch is a personal Android TV fork of Nuvio. Stage 1 deliberately keeps the
original Nuvio playback behaviour unchanged so later playback work can be compared
against a known baseline.

## Identity

- Application ID: `com.jeffrey.nuvioflow.debug`
- Display name: `Nuvio Flow`
- Version name: `1.1.0-flow.3`
- Version code: `2001`
- Target ABI: `armeabi-v7a` only
- Signing: the existing Flow development keystore, supplied through
  `local.properties`. It is not committed and must not be published.

The application ID and signing key are chosen to update the existing Flow
installation in place. The official Nuvio package (`com.nuvio.tv`) is separate and
must not be replaced or uninstalled.

## Build configuration

`local.properties` is intentionally excluded from source control. It supplies:

- `sdk.dir`
- `NUVIO_RELEASE_STORE_FILE`
- `NUVIO_RELEASE_KEY_ALIAS`
- `NUVIO_RELEASE_KEY_PASSWORD`
- `NUVIO_RELEASE_STORE_PASSWORD`
- `NUVIO_SUPABASE_URL`
- `NUVIO_SUPABASE_ANON_KEY`
- `NUVIO_SUPABASE_FALLBACK_URL`
- `AVATAR_PUBLIC_BASE_URL`
- `TV_LOGIN_WEB_BASE_URL`
- `DEVICE_LOGIN_WEB_BASE_URL`
- `DOVI_NATIVE_ENABLED=true`
- `DOVI_EXTRACTOR_HOOK_READY=true`
- `DOVI_ENABLE_REAL_LINK=true`
- `DOVI_LIBDOVI_PREBUILT_ROOT=DV7/libdovi`

The account values are the official Nuvio client configuration. The Supabase
publishable/anon key is a public client key, not a server secret. Signing passwords
and any private service credentials must stay out of commits and build logs.

## Build

From this directory, in Windows PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = 'C:\Users\crimi\Documents\deepseek-harness\default-workspace\.gradle-flow'
$env:TMP = 'C:\Users\crimi\Documents\deepseek-harness\default-workspace\.tmp-flow'
$env:TEMP = $env:TMP
$env:GRADLE_OPTS = '-Dorg.gradle.vfs.watch=false'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:assembleFullRelease --no-daemon --no-watch-fs
```

The release APK is written under `app/build/outputs/apk/full/release/`.

## Deliberate Stage 1 limits

- No playback tuning: native-memory performance mode, parallel fetching, larger
  buffers and VOD cache are not enabled by this change.
- In-app updates are disabled for now. The updater must not point at the official
  Nuvio release feed. A Flow-owned release repository and signing policy are a later
  step.
- The following optional integrations have no local configuration yet and may be
  unavailable or reduced: TMDB metadata/trailers, Trakt, MDBList, Simkl, IntroDB,
  IMDb ratings, parental guide, Premiumize OAuth and playback issue reports.
- Dolby Vision Profile 7 conversion support is compiled in when the libdovi prebuilt
  library is present. This does not imply the attached TV supports Dolby Vision; the
  player still follows its normal display-capability policy.

## Source control

Two commits should exist:

1. the untouched original Nuvio source;
2. the Flow identity, branding and optimized build configuration.

Keep later playback and analytics work in separate commits so upstream Nuvio changes
can be merged with a small, reviewable conflict surface.
