**English** | [한국어](README.ko.md)

# ShotTracer Android and iPhone port plan

Date: 2026-11-03. Status: initial development prototype. Android/iPhone app sources and a shared automatic-tracking core are now included here. Store release and physical-device validation remain incomplete.

## 1. Goal and assumptions

Bring the golf video editor to Android and iPhone. This review uses `../AGENTS.md`, `../docs/ARCHITECTURE.md`, both root READMEs, and the implementation. The user confirmed **standalone offline phone use with automatic ball tracking in the first release**. The first implementation uses SwiftUI (iPhone), Kotlin (Android), and shared C++. This allows direct validation of native media APIs in the available environment; choices may change after device validation. Plan tracking around the existing workflow of selecting the starting ball position before analysis; automatically finding that initial position is a separate requirement.

The Python 3.13 + FastAPI + pywebview/PyInstaller application cannot simply be converted into an APK or iOS app. Python MediaPipe/OpenCV dependencies, external ffmpeg processes, and filesystem access need mobile implementations. Python's availability on mobile does not establish compatibility of this entire application.

Mobile source, planning, and validation tools are added inside this folder. Existing application code, project data, and watermark behavior remain unchanged. At the start, both root READMEs were modified and `AGENTS.md` and `docs/` were untracked; that existing work was preserved.

## 2. Implementation options

| Option | Existing work reused | Standalone phone use | Assessment |
| --- | --- | --- | --- |
| Flutter UI + shared C++ processing engine + Swift/Kotlin adapters | Algorithms, project format, and test expectations; Python/JS implementations need porting | Design target | Alternative if shared UI becomes necessary |
| Separate Swift iOS and Kotlin Android apps | Algorithms can use a shared engine | Design target | Chosen for the first implementation; requires platform-specific UI maintenance |
| WebView/PWA wrapping the current UI | Some HTML/JS | Not with the existing backend unchanged | Touch editing and analysis/export still need solutions |
| Phone UI with PC or cloud processing | Substantial Python engine reuse | No | Changes the local-only product model; needs separate requirements |

Flutter supports native code calls but does not provide this analysis engine. Proposed flow: Flutter → platform channels/FFI → shared processing engine and OS media adapters. Validate native buffers/textures rather than repeatedly copying full frames through channels. [Flutter documentation](https://docs.flutter.dev/platform-integration/platform-channels)

On a phone, `127.0.0.1` refers to that phone. It does not connect to the PC server. Exposing the PC server by changing its bind address is outside this plan.

## 3. Required module changes

| Current component | Proposed mobile change |
| --- | --- |
| `settings.py` | Mobile models and compatibility tests preserving defaults and legacy project, popup, and swing-point validators |
| `trajectory.py`, `engine.apply_edits`, `ShotPath` | Port numeric logic to the shared engine; preserve `base_model`, fast edits, and apex/landing/speed semantics |
| `camera.py`, `ball.py`, `putt.py`, `swing.py` | Native OpenCV operations and shared algorithms, checked against existing synthetic tests |
| `person.py` | Validate Android/iOS MediaPipe Tasks instead of the Python package; bundle and hash-check the model, verify masks, wrists, and coordinate mapping |
| `video.py` | Android Media3/MediaCodec and iOS AVFoundation media adapters replacing external ffmpeg execution |
| `render.py`, `popups.py` | Shared scene/compositing engine; validate fonts, Korean text, alpha composition, and glow previously produced with Pillow |
| `engine.py` storage | App-private storage, import/copy policy, bounded caches, cancellation, and recovery |
| `server.py` | Proposed replacement with an internal job service; an HTTP server is not required |
| `web/app.js`, `style.css` | Portrait layout, touch selection/drag/pinch, frame buttons, bottom tool panels; existing fixed three-column layout and mouse events need adaptation |
| `web/i18n.js` | Preserve en/ko keys and map them to mobile resources; existing web changes must continue using this file |
| `desktop.py`, `packaging/` | Separate Android/iOS build, signing, and validation instead of reusing desktop packaging |
| `watermark.py` | Preserve default-on display and salted scrypt password verification; validate unlock flow and per-device persistence |

MediaPipe offers Android/iOS Pose Landmarker and optional person masks. Equivalent performance and accuracy to the Python pipeline require device validation. [Android guide](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/android), [iOS guide](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/ios)

Android Transformer supports hardware media processing and custom effects. Validate AVFoundation for iOS input/output. These APIs do not implement the existing tracer; the shared compositor must be connected separately. [Android documentation](https://developer.android.com/media/media3/transformer), [Apple documentation](https://developer.apple.com/av-foundation/)

## 4. Mobile validation concerns

- **Time and orientation:** Handle phone variable frame rates, portrait rotation metadata, and mirroring. Define source timestamp to existing frame-index mapping without changing old project coordinate semantics.
- **Coordinates:** Preserve video px → analysis px → reference → output transforms. Touch mapping needs inverse display scaling, letterboxing, pinch, and rotation transforms.
- **Memory:** `video.read_analysis_frames` retains grayscale analysis frames in a list. For example, 960×540×960 frames×1 byte is approximately 475 MiB before auxiliary buffers. Streaming/chunking must preserve camera reference and three-frame difference context. Portrait clips also need a maximum dimension or pixel budget, since limiting width alone allows large heights.
- **Matching results:** Mobile preview and export must share scene, path, font, and compositing rules. A tracer drawn only on the Flutter canvas is unacceptable. Define comparison tolerances for GPU/codec differences.
- **Storage:** Gallery assets are not desktop absolute paths. Start by copying selected videos into app storage, with storage usage and cleanup policy. Obtain remote originals such as iCloud assets before offline editing. Use Android Photo Picker for selected-video access. [Documentation](https://developer.android.com/training/data-storage/shared/photo-picker)
- **Interruption and recovery:** Persist job state and implement atomic saves, cancellation, and restart for app switching, screen lock, and OS termination. Do not assume uninterrupted background execution.
- **Output:** Start validation with SDR H.264 MP4 and source audio at normal speed. Check HEVC/HDR and high-frame-rate footage separately. Initially retain the existing silent slow-motion limitation and explain it in the UI and documentation.
- **Product rules:** No video uploads, telemetry, or new runtime network calls. Distance and speed remain user-entered rather than presented as measurements from a single video.

## 5. Equipment and preparation

Interpret the user's request as **support for iOS 26.6.2 or later**. This specifies a minimum OS version, not an iPhone model. Validate SDK/deployment-target configuration and device performance during implementation. The user specified **Android 14 or later** as the planned Android minimum. App compatibility on either platform remains unverified. Manage minimum supported OS separately from the store submission target SDK.

| Item | Observed state / preparation |
| --- | --- |
| iOS development Mac | Xcode 27.2 and developer path confirmed on this Mac; actual iOS build, signing, and connected device remain untested |
| Android tooling | Android Studio, Android SDK, compatible JDK required; `adb` not found on PATH, full installation status unknown |
| Shared UI tooling | Flutter candidate; `flutter` not found on PATH, no installation performed |
| Physical devices | At least one iPhone and one Android; need model, OS version, and free storage |
| Validation videos | Shareable drive/chip/putt clips, portrait/landscape, 30/60fps and high-frame-rate samples; never commit videos |
| Distribution assets | App name, icon, support contact, privacy description, signing-key storage policy; accounts remain owner-managed |

App Store/TestFlight distribution generally requires Apple Developer Program membership, officially US$99 annually. Testing on your own device through Xcode can begin with a free account. [Apple guidance](https://developer.apple.com/programs/)

Google Play registration is officially a one-time US$25 fee. Recheck account-specific verification/testing and submission SDK requirements when distributing. Local Android device testing does not require Play registration. [Google guidance](https://support.google.com/googleplay/android-developer/answer/6112435?hl=en)

Fees checked on 2026-11-03; regional pricing and taxes may vary. Estimate development schedule and total cost after device feasibility and feature scope are established.

**The confirmed distribution target is public release on Apple App Store and Google Play.** Personal installation alone does not fulfill it. Include the following in release preparation.

- The user confirmed an existing Google Play developer account; new account enrollment is not a preparation task. Individual/organization type, creation date, verification, and production access still need confirmation. The user has not joined Apple Developer Program; prepare enrollment before public App Store distribution. Enrollment, payment, and actual publication are separate actions.
- Choose the iOS Bundle ID and Android application ID, configure distribution signing, and retain Android upload keys and recovery procedures. Keep secrets out of the repository.
- Prepare en/ko descriptions, icons, actual app screenshots, support contact and URL, privacy policy URL, age ratings, and distribution countries. Free/paid pricing remains undecided.
- Complete Apple App Privacy and Google Play Data safety based on actual local-video and SDK data behavior. Offline operation does not remove the need for privacy information. [Apple privacy guidance](https://developer.apple.com/app-store/app-privacy-details/), [Google Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)
- Prepare review submission after physical-device validation using TestFlight and Play testing tracks. Google Play personal developer accounts created after 2023-11-13 currently require a closed test with at least 12 testers opted in continuously for 14 days before applying for production access. Check applicability to the owner's account; completing testing does not guarantee approval. [Google testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en)
- Recheck Apple SDK/Xcode and Android target SDK requirements at submission time. Provide reproducible automatic-tracking steps and permission explanations for reviewers; verify preview, export, and watermark behavior in actual builds. [Apple review guidance](https://developer.apple.com/app-store/review/)

## 6. Implementation stages and acceptance

1. **Feasibility:** On both devices, select a video, decode a frame, composite a tracer with the shared engine, and save a short MP4. Verify orientation, audio, Korean text, watermark, and preview/export agreement. Measure time, peak memory, and heat; validate tooling and device compatibility within the requested iOS 26.6.2-or-later scope. Validate the same criteria on Android 14 or later before finalizing technology.
2. **Internal editing validation:** Import, impact frame, manual start/apex/landing, tracer speed, styles, undo/redo, save/load, en/ko, watermark lock, and video export. This is an intermediate development milestone for connecting analysis, not a completed first release.
3. **Automatic tracking:** Port camera compensation, ball candidates/launch/flight model, person masks/wrists, then swing/putt tracking; compare device samples. Confirm that edits do not re-run analysis.
4. **Expanded features and distribution:** Up to four shots, popups/logo/shapes, slow motion/batch work, compatible project transfer, and interruption recovery. Prepare store submission after signed device builds and user testing.

The first release must complete import → starting-ball selection → automatic ball tracking → trajectory adjustment → preview → video export on both platforms without a PC or cloud. A manual-only editor does not meet the release requirement. Distinguish predicted segments after tracking loss and landing corrections as in the current product. Set tracking time, memory, and success criteria after obtaining representative clips and target devices. First-release inclusion of person occlusion, automatic swing paths, and putt tracking remains a separate decision.

## 7. Source and change storage

Apps live in `app/android/` and `app/ios/`, the shared engine in `core/`, synthetic/numeric tests in `tests/`, and developer tools in `tools/`. Both apps call the same C ABI. The first implementation does not modify desktop Python code or project data.

Never commit installers, signing credentials, passwords, real videos, or build outputs. If desktop shared code must change later, record the reason and make a separate change. Develop on `codex/mobile-port-plan` and review validated changes in a draft PR. Do not include existing user changes to root READMEs or documentation in the mobile commit.

## 8. Decisions needed from the user

- Confirmed: standalone offline phone use without a PC, with automatic ball tracking in the first release.
- Still to decide: first-release inclusion of person occlusion, automatic swing paths, and putt tracking.
- Support targets: iOS 26.6.2 or later and Android 14 or later. Still needed: physical test device models and installed OS, and typical video resolution, frame rate, and length.
- Confirmed: public App Store/Google Play distribution and an existing Google Play developer account. Still needed: Google account type, creation date, verification and production access, Apple Developer Program enrollment preparation (not yet joined), and free/paid pricing.
- Which device to prioritize and essential first-version features.

Both Android and iPhone target these requirements. Manual editing serves internal validation and correction when automatic tracking fails. Do not send account passwords or signing keys in chat.


## 9. Current implementation and development

### Implemented scope

- Local video copy, scrubbing, starting-ball selection, and automatic tracking sampled at 30 times per second for up to 15 seconds.
- C++ bright moving-object candidates, address-spot disappearance, direction/speed consistency for launch confirmation, observation linking, and small camera-translation compensation. Only one frame is retained.
- Preview and export share `st_render` geometry and the same platform-specific watermark composition function. Watermark is always present; no unlock UI exists yet. Desktop scrypt verification remains unchanged.
- iPhone: SwiftUI, Files video import, AVFoundation extraction/MP4 export, and save/share menu.
- Android: Kotlin, system video picker, JNI, Media3 Transformer MP4 export, and document save picker. No INTERNET or broad gallery permission is requested.
- Ported desktop `Model.at` and `apex_time` numeric evaluation to C++ and compared results with Python. Automatic model fitting/edit integration remains pending.
- Mobile strings use iOS `en.lproj`/`ko.lproj` and Android `values`/`values-ko` resources. Existing web localization remains unchanged.

### Validation and builds

From the repository root:

```bash
uv run pytest
```

All 60 tests passed: 50 existing and 10 mobile-core tests. Core tests require a C++17 compiler and skip if none is available. They check model parity, synthetic tracking, stationary balls, tracking loss, camera translation, variable time intervals, and rendering time.

The Xcode project is included and can be regenerated:

```bash
python3 mobile-port/tools/generate_ios_project.py
xcodebuild -project mobile-port/app/ios/ShotTracer.xcodeproj \
  -scheme ShotTracer -configuration Debug -sdk iphoneos \
  -destination generic/platform=iOS \
  -derivedDataPath /tmp/shottracer-mobile-ios CODE_SIGNING_ALLOWED=NO build
```

An unsigned iOS device build succeeded on this Mac. To install on a device, configure your Team and unique Bundle ID in Xcode. `com.ajc.shottracer.dev` is a development identifier, not a confirmed store registration. Simulator execution remains unverified because of a CoreSimulator service version mismatch.

Android uses JDK 17, SDK platform 35, Build Tools 35.0.0, NDK 27.0.12077973, and CMake 3.22.1. Gradle 8.11.1 wrapper and distribution ZIP SHA-256 are included. Development compile/target SDK 35 does not establish store submission eligibility; update before release.

```bash
cd mobile-port/app/android
./gradlew :app:assembleDebug
```

On this Mac, the debug APK build including arm64-v8a/x86_64 native cores and `lintDebug` succeeded. Installation/execution on an Android device remains unverified.

Open this folder in Android Studio or set `ANDROID_HOME` to a prepared SDK. Do not commit local SDK paths. Generated APKs belong in `app/build/outputs/apk/debug/` and are not committed.

### Remaining work and usage limits

Real golf footage, physical devices, and preview/export agreement on both platforms remain unverified. The detector assumes a bright ball and small camera translations; it is not equivalent to desktop LK/RANSAC and person masking. Predicted flight after observation loss, landing/apex edits, four shots, popups/swing/putt/slow motion, and project recovery/compatible import remain unimplemented. Only observed paths are drawn; no observations are fabricated after tracking loss.

Analysis maximum dimension is 640px. iPhone export uses a 1080p preset and Android development output uses 720px height; original-resolution export remains pending. Standard platform export paths include source audio, but codecs, audio, HDR, portrait rotation, and variable frame rates require real-video validation. Android frame extraction uses the nearest frame to each requested time; a source-timestamp decoder remains pending.

Keep the app open during validation. Background execution, recovery after OS termination, cache cleanup/project deletion UI, accessibility improvements, and mobile scrypt verification for password unlock remain pending. This is an initial automatic-tracking implementation, not completion of the first-release requirements.
