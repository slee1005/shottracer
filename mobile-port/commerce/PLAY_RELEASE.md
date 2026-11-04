# Google Play upload bundle

Production ID: **com.ajc.shottracer**. Version **0.0.1**, version code **1**, Android 14+ (min 34), compile/target 36. The `.dev` and `.dev.tester` variants remain separate. A production installation does not overwrite their app-private projects.

## Files on this Mac

- Upload: `mobile-port/release/ShotTracer-0.0.1-play.aab` (43,612,838 bytes).
- Checksum: the adjacent `.aab.sha256` file.
- Verification: the adjacent `ShotTracer-0.0.1-play.verification.json`.
- Private upload keystore: `mobile-port/private/android-upload.p12`.
- Private signing configuration/passwords: `mobile-port/private/android-upload.properties`.
- Public upload certificate: `mobile-port/private/android-upload-certificate.pem`.
- Play purchase verification public key: `mobile-port/private/play-billing-public-key.txt`.

Back up the private keystore **and** its properties securely for future updates. Do not send passwords/keystore contents in chat or commit them. The upload key is not the Play licensing public key, and neither is the tester-code issuer key. All private files and generated bundles/reports are ignored by Git. Keep the separately generated tester issuer key too if continuing to issue tester grants.

## Upload

1. Open the intended new app in Play Console. Upload this AAB to an appropriate internal testing release and configure Play App Signing. The bundle is signed with the new owner upload key; Google manages the distributed app signing key through Play App Signing.
2. Check that Console shows **com.ajc.shottracer**, version code **1**, target **36** and the intended signing certificate. No upload or publication was performed by the build tool.
3. Create/activate `shottracer_monthly` / `monthly`, one month, US USD 2.99, and test purchases/restoration/pending/renewal/cancel/refund with license testers. The app gives 72 free hours from first launch; no second ordinary introductory trial is needed.
4. Complete required store listing, privacy/Data safety, app access/review instructions, content rating, account verification and applicable testing/production-access requirements. Verify real camera/tracking/export/album behavior on target devices. Structural bundle validation is not store approval or physical-device validation.

## Rebuild

Install JDK 17, SDK platform 36, Android build tools, NDK 27.0.12077973 and CMake 3.22.1. Configure JAVA_HOME/ANDROID_HOME. Gradle wrapper 8.11.1 and AGP 8.10.1 are pinned. The native build enables `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`; do not remove it.

Use official **bundletool-all-1.18.3.jar** from https://github.com/google/bundletool/releases/tag/1.18.3 . Required SHA-256: `a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`.

From the repository root:

```sh
.venv/bin/python mobile-port/tools/build_play_bundle.py --bundletool /absolute/path/bundletool-all-1.18.3.jar --java-home /absolute/path/jdk17
```

The builder reads local signing settings and the supplied licensing key, builds `bundleRelease`, runs JVM tests and release lint, verifies the AAB, then copies it and a checksum/report into ignored `release/`. The release tasks reject missing/invalid licensing keys or missing signing settings. No placeholder purchase-verification key is generated. Subsequent Play uploads must use a higher versionCode; keep using the existing upload keystore rather than initializing a new one.

## Verification completed

- Signed, non-debug/non-test-only production AAB; expected upload certificate matches.
- Official hash-verified bundletool structural validation and JAR signature verification.
- Actual target/min/version/package manifest values checked.
- Four native libraries (arm64-v8a and x86_64) have >=16KB congruent ELF LOAD alignment; BundleConfig requests PAGE_ALIGNMENT_16K.
- Pinned pose model hash and provided Play licensing public key embedding checked.
- 85 Python/core/issuer/artifact tests and 38 JVM tests passed; debug/release builds and lint passed.

Sources checked on 2026-11-03: [target API](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en), [16KB pages](https://developer.android.com/guide/practices/page-sizes), [app signing](https://developer.android.com/studio/publish/app-signing). Real subscription transactions, Play acceptance and physical 16KB-device behavior remain to be validated.
