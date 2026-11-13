# Google Play free release

Package **com.ajc.shottracer**, version **0.0.1**, code **5**, minimum API **34**, compile/target **36**. Separate `.dev` and `.dev.tester` packages retain their own data and now both offer free access. Keep the default watermark.

## Local outputs

- Play upload: `mobile-port/release/ShotTracer-0.0.1-free-play.aab`
- Direct-install APK: `mobile-port/release/ShotTracer-0.0.1-free.apk`
- Adjacent `.aab.sha256` and `.verification.json`: checksum and validation report
- Existing upload signing files: ignored `private/android-upload.p12`, `private/android-upload.properties`, `private/android-upload-certificate.pem`

Back up signing keys and settings together. No billing public key or tester issuer key is required. The old `ShotTracer-0.0.1-play.aab` is the code-1 subscription build; do not distribute it as the free app. Never commit build outputs.

## Rebuild

Use JDK 17, SDK 36, NDK 27.0.12077973, CMake 3.22.1 and Gradle 8.11.1. Set `ANDROID_HOME` to the prepared SDK and run from the repository root:

```sh
.venv/bin/python mobile-port/tools/build_play_bundle.py --bundletool /absolute/path/bundletool-all-1.18.3.jar --java-home /absolute/path/jdk17
```

For an installed Gradle, add `--gradle /absolute/path/gradle`. Pinned bundletool 1.18.3 SHA-256: `a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`. The tool runs bundle assembly, JVM tests and release lint, then checks certificate/JAR signing, package/API, model hash, 16 KB ELF/bundle alignment and absence of billing SDK and billing/network permissions. Build a direct-install APK separately with `:app:assembleRelease`; output is `app/android/app/build/outputs/apk/release/app-release.apk`.

## Before submission

1. Set the app price to **free** in Play Console. No subscription product is required.
2. Upload the code-**5** free AAB to internal testing, retaining the existing app signing. If code 5 or higher was already uploaded, rebuild with a higher versionCode.
3. Complete privacy, Data safety, content rating, app access, review instructions and account-specific testing/production requirements. Recheck current Console requirements when submitting.
4. Validate physical-device import, tracking, export, album saving, camera, microphone and 16 KB operation.

Local validation is not store approval or physical-device validation. This task does not upload, publish or change accounts. iPhone needs separate Apple enrollment, signing, device validation and App Store preparation.
