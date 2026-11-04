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
| `person.py` | Android MediaPipe Tasks with a bundled pinned full pose model; iPhone Apple Vision person segmentation/body pose; cache masks and reference-space wrist/club observations |
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
- **Storage:** Gallery assets are not desktop absolute paths. Start by copying selected videos into app storage, with storage usage and cleanup policy. Obtain remote originals such as iCloud assets before offline editing. Use Android’s local document picker for selected-video access. [Documentation](https://developer.android.com/training/data-storage/shared/documents-files)
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

The first release must complete import → starting-ball selection → automatic ball tracking → trajectory adjustment → preview → video export on both platforms without a PC or cloud. A manual-only editor does not meet the release requirement. Distinguish predicted segments after tracking loss and landing corrections as in the current product. Set tracking time, memory, and success criteria after obtaining representative clips and target devices. The user subsequently requested person occlusion, automatic club/wrist paths and putt tracking on both platforms; their implementation is described in section 9.

## 7. Source and change storage

Apps live in `app/android/` and `app/ios/`, the shared engine in `core/`, synthetic/numeric tests in `tests/`, and developer tools in `tools/`. Both apps call the same C ABI. The first implementation does not modify desktop Python code or project data.

Never commit installers, signing credentials, passwords, real videos, or build outputs. If desktop shared code must change later, record the reason and make a separate change. Develop on `codex/mobile-port-plan` and review validated changes in a draft PR. Do not include existing user changes to root READMEs or documentation in the mobile commit.

## 8. Decisions needed from the user

- Confirmed: standalone offline phone use without a PC, with automatic ball tracking in the first release.
- Confirmed later: person occlusion, automatic club/wrist paths and dedicated putt tracking on Android and iPhone.
- Support targets: iOS 26.6.2 or later and Android 14 or later. Still needed: physical test device models and installed OS, and typical video resolution, frame rate, and length.
- Confirmed: public App Store/Google Play distribution and an existing Google Play developer account. Still needed: Google account type, creation date, verification and production access, Apple Developer Program enrollment preparation (not yet joined), and free/paid pricing.
- Confirmed: apply the requested editing and automatic-analysis features to both platforms.

Both Android and iPhone target these requirements. Manual editing serves internal validation and correction when automatic tracking fails. Do not send account passwords or signing keys in chat.


## 9. Current implementation and development

### Implemented scope

New logo source: [ShotTracer logo v1](assets/brand/shottracer-logo-v1.png), with the generation prompt and asset scope in [design notes](assets/brand/DESIGN.md).

- A redesigned dark workspace with teal action/selection controls, rounded video cards, visible Flight/Putt switching and iPhone native Glass controls. A larger, zoomable video workspace with accessible icon buttons, an expand/collapse control, exact previous/next source-frame stepping and a frame counter. The selected shot has a yellow starting-ball marker and green landing flag above the timeline, with source time/frame buttons below it. Drag the markers to edit their times (details below). Tap a marker/button to jump to the nearest source frame and show labeled start/landing handles in the video. Predicted landings say “est.”; events beyond the clip say “Outside clip” and cannot be jumped to. The event clock remains start + flight duration regardless of tracer drawing speed or export slow motion. Long-press an Android icon to see its label. Landscape and portrait use fit-to-screen without cropping the video.
- Up to four shots in one video: add/select/delete, select the starting ball and frame, analyze the selected shot, drag start/apex/landing handles, assign the current frame as landing time, adjust flight duration, time to apex and tracer speed. Undo/redo stores up to 50 edit snapshots; edits do not rerun tracking.
- Shared C++ perspective flight fitting, landing refitting and apex reshaping, compared numerically with the desktop Python implementation. Automatic observations and `baseModel` remain separate from edits. The predicted flight after tracking loss is an estimate; selecting the actual landing point improves it.
- Automatic person occlusion, wrist midpoint and club-head paths, plus a dedicated template-matching rolling-putt tracker; see the on-device analysis details below.
- Five tracer presets (classic/TV/neon/comet/minimal), shot colors, width/glow/hold, ball head and landing marker; manual start/apex/landing curves, circles/lines with draggable endpoints, and frame-timed manual swing points.
- Eight popup kinds with position and timing, local logo import/size/opacity/corner, silent slow motion around shot starts, project save/reopen, reset and sequential batch export. Distance and speed values are user-entered, not measurements from one video.
- Normal-speed export retains source audio through native media APIs. Slow-motion export is explicitly video-only. Each platform uses its same `EditorRenderer` scene raster for preview and export, including all shots, shapes, popups, logo and the default-on watermark. Selection handles are editor controls and are not exported.
- Local video copies and atomic `mobile-project.json` edit saves in app storage. No PC/cloud processing or runtime network calls. The new mobile schema is version 1; it does not import desktop project folders. App-private logo paths are not portable between devices.
- iPhone: SwiftUI, Photos/Files import, AVFoundation export and native output review/playback/share. Photos copies selected files without requesting full-library access. iCloud-only originals must be downloaded by the system before offline editing.
- Android: Kotlin, system video picker, JNI, Media3 normal-speed export, MediaCodec slow-motion export, app output review and automatic photo-album export through MediaStore. No INTERNET or broad gallery permission.
- Both live cameras show observed tracers during recording, save the composited video and offer output review. Camera and optional microphone permissions are requested only for live capture. Details and limits are below.
- en/ko UI uses Android resources and iOS localized strings. The desktop web localization system is unchanged. The watermark remains on; its mobile password-unlock UI is not implemented and the desktop scrypt check is preserved.

### Validation and builds

From the repository root:

```bash
uv run pytest
```

All 68 tests passed: 50 existing and 18 mobile-core tests. Core tests require a C++17 compiler and skip if none is available. They check fit/refit/apex parity with desktop, exact manual-curve endpoints and speed, synthetic tracking, stationary balls, tracking loss, camera translations, variable time intervals and rendering time.

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

On this Mac, the debug APK build including arm64-v8a/x86_64 native cores and `lintDebug` succeeded. Android 14 emulator installation and editor/live-camera smoke checks passed; physical-device validation remains pending.

Open this folder in Android Studio or set `ANDROID_HOME` to a prepared SDK. Do not commit local SDK paths. Generated APKs belong in `app/build/outputs/apk/debug/` and are not committed.

### Testing the editor

1. Install the newly built APK, or rebuild/install the iPhone app from Xcode. Open a local video using the import icon (Photos is available on iPhone).
2. Use previous/next-frame icons or the frame slider. Tap **Set start point** and select the ball just before impact. The start button shows a checkmark. Choose **Analyze** to track the ball automatically, or choose the **Landing** tool and tap the landing position to draw a manual tracer without analysis. **Reset** beside it asks for confirmation, clears this project’s edits/analysis and returns to frame 1 while keeping its imported video; undo can restore edits.
3. Use **+** to add another shot (up to four). Select its shot chip before editing. Drag the green landing handle and cyan apex handle. The landing-frame tool sets flight time to the displayed frame, then enables landing-point selection.
4. Open settings for flight/apex timing, speed, styles, popups, logo and slow motion. Use circle/line tools with two taps; tap manual swing points on consecutive frames. Pinch to zoom, drag empty video areas to pan in any direction, and double-tap to fit. Markers remain draggable; the pan tool prioritizes viewport movement even over markers.
5. Export, then tap the review icon to play the finished MP4 inside the app. Review opens at 1× media playback speed, independent of tracer speed and editor playback. Slow motion already encoded in a finished MP4 remains part of that video. Completed exports are automatically saved to the photo album; the Save icon retries if saving failed. iPhone requests add-only Photos permission on first save; Android uses Movies/ShotTracer. The iPhone review screen also offers sharing. Reopen a project from the project icon to resume saved edits. Batch export processes saved projects sequentially. In the project list, select **Delete projects…** on Android; on iPhone select rows or swipe one row. Select individual projects or all, then confirm deletion. Only app-owned project copies, edits and their exported results are removed; original Photos/Files videos and separately saved output copies are kept. Project deletion cannot be undone.

Android 14 emulator checks used an uncommitted synthetic 640×360/30fps/90-frame clip: frame 1→2 changed the timestamp 0.000→0.033s, a landing drag changed persisted normalized coordinates, shot add/undo restored one shot, normal export produced a valid 3-second/90-frame H.264 MP4 with tracer/popup/circle/watermark, and the review activity opened the result. The expand control retained a visible return control; a 2× slow-motion window produced a 4.6-second/138-frame H.264 video-only file. These checks do not establish real golf tracking quality or iPhone runtime behavior. Additional project-control checks confirmed start-point checkmarks and Analyze gating, 70 observed synthetic points, reset retaining the imported video, deletion cancellation retaining both test projects, select-all deleting both app-owned test folders and clearing the active editor. Eighteen Android JVM tests cover YUV conversion/rotation, overlapping slow-motion timing windows, and exact putt landing edits without altering observations, and timeline VFR mapping/predicted or out-of-clip events, and drag timing constraints with immutable automatic data. Android emulator timeline buttons moved from frame 1 (0.000s) to landing frame 31 (1.000s) and back to frame 1, with both location labels visible in the preview.

### Remaining work and usage limits

**This is not yet the complete desktop feature set or a store-ready release.** Desktop project import and mobile password unlock remain to be ported. Person masks and automatic wrist/club paths are now implemented with native inference, while manual swing points remain available. The flight detector and small translation compensation do not replace desktop LK/RANSAC, and the native pose backends do not guarantee identical desktop masks or landmark results. Manual paths currently use start/apex/landing curves rather than arbitrary freehand strokes.

Real golf footage and physical Android/iPhone devices still need testing for tracking accuracy, codecs/audio, HDR, rotation, variable frame rates, heat and memory. Frame controls use the source timestamp table (Android index decode; iPhone exact-time decode). Analysis samples at at most 30fps with a 640px maximum dimension; timestamps and controls still include all source frames. Normal export keeps source dimensions using platform encoders, with overlays rasterized at analysis resolution; it is not an HDR-preserving pipeline. Android slow-motion development export uses analysis dimensions and 30fps, without audio. iPhone uses the highest-quality export preset and a video-only retimed composition for slow motion.

Keep the app open during long analysis/export. Background jobs and recovery of unfinished work after OS termination remain pending; completed edit saves can be reopened. Further work includes cache cleanup, richer annotation/popup editing, accessibility and device-specific validation. Live recordings already contain their tracer and do not reopen as editable tracking projects. Public App Store/Play release still requires signed device tests and store preparation.


### Popup information cards

New cards created in the editor default to **Top left** and the shot-start event. Existing saved anchors/times are preserved. Choose **Save and preview** to save and jump to an actual source frame where the card is visible. On Android, Settings → Add popup opens existing cards for editing/deletion plus New popup. On iPhone, Settings → popup rows offer Preview/Edit; the form can update an existing card or add a new one. If no source frame falls inside the configured interval, a warning stays visible so the anchor/delay/duration can be corrected rather than silently showing nothing.

After-shot cards now stay visible at least one second after they appear, even with a delay longer than one second. Previously their fixed end time could precede their start time, so they never rendered. An anchor at landing can also be beyond a short clip; this is reported, not silently retimed. Timed/video-end rules and older project values are retained. Cards use a clearer two-line label/value and stack when anchors collide, so a speed card no longer covers the distance card. Preview and MP4 export call the same timing/layout functions.

Regression tests cover delayed after-shot windows, explicit durations/video-end lifetime, choosing the first visible VFR frame, intervals outside the clip, and stacked/clamped cards. Android 14 emulator: two Korean distance/speed cards plus a landing card delayed two seconds were visible together at 3.200s in preview and in a 6-second/180-frame H.264 export. Adding a card through the actual dialog saved Top left defaults and moved to frame 10 at 0.300s. iPhone builds pass; its runtime behavior still needs physical-device validation.

### Drag timing markers and save to the photo album

Drag the yellow starting-ball marker or green landing flag horizontally. The time snaps to a real source frame, including VFR clips. Moving the start keeps the absolute landing time and spatial landing location; moving landing keeps the start and spatial landing location. The allowed flight interval is 0.2–15 seconds, further limited to available frames. The markers cannot cross. A predicted landing outside the clip can be dragged back onto the visible timeline. Automatic observations and `baseModel` remain unchanged; masks and wrist/club observations stay anchored to their actual source frames. No analysis is rerun. A drag is one undo step; cancellation restores its starting edit state. Edited project JSON saves on release, rather than on every move. On iPhone, VoiceOver can adjust an event one frame at a time.

Normal/slow-motion exports and completed live recordings now attempt photo-album saving automatically. Android publishes a complete, non-pending MediaStore video in **Movies/ShotTracer**, without requesting broad gallery permission. A repeated save checks its existing owned item before inserting another. iPhone uses Photos **add-only** authorization and the localized `NSPhotoLibraryAddUsageDescription`; it does not request read access to the user's library or create a custom album requiring broader access. Successful output URLs are remembered to avoid automatic duplicate additions. If permission is denied or saving fails, the app keeps its completed output and shows a retry message; enable adding photos in iOS Settings if necessary, then tap the Save icon. Gallery/Photos copies are independent of project folders and remain after project deletion. Android storage API details: [MediaStore guide](https://developer.android.com/training/data-storage/shared/media). iPhone saving API: [Photos video creation](https://developer.apple.com/documentation/photos/phassetchangerequest/creationrequestforassetfromvideo(atfileurl:)).

Android 14 emulator checks: dragging start from 0.000s to 0.300s kept landing at 1.000s and kept its position; dragging landing to 0.666666s kept start at 0.300s. One undo restored the complete landing drag. A narrow system-gesture exclusion on the handle rail prevents the first/last marker from invoking Android's Back gesture. A 1.2-second edited export and a 2.321-second virtual-camera recording appeared in Movies/ShotTracer with `IS_PENDING=0`, nonzero size and valid duration metadata. Re-saving the same export retained one MediaStore item. Capture finalization also performs album saving when the camera screen is closed. iPhone device builds and the generated Photos usage description were checked; actual Photos authorization, save/denial behavior and gestures still require an iPhone device.

### On-device person, swing and putt analysis

Select **Flight** or **Putt**, mark the starting ball, then tap **Analyze**. The imported-video editor analyzes up to 1.2 seconds before the selected frame and 15 seconds after it, within the clip. Ball tracking samples at most 30fps. Person analysis samples at 30fps near the selected frame and up to 15fps elsewhere. Results are saved with that shot: immutable automatic ball observations/model, camera offsets, automatic wrist/club observations and compact timestamped alpha-mask PNGs in an app-private analysis folder.

- **Android:** MediaPipe Tasks Vision 1.1.1, CPU, one full pose, segmentation enabled. Gradle `preparePoseModel` bundles the official immutable `pose_landmarker_full/float16/1` model; SHA-256 `5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1`. A clean development build needs network access for dependencies/model; the installed app has no INTERNET permission and performs no model download. The model is generated under `app/build/`, not committed. SDK `MPImage` owns its input bitmap, so the analysis adapter gives it a separate copy. [Google pose guide](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker/android)
- **iPhone:** Apple Vision `VNGeneratePersonSegmentationRequest` and `VNDetectHumanBodyPoseRequest`, using upright frames and converting bottom-origin joints to top-origin video coordinates. No extra downloadable model or account is required by the app. Both wrists must pass a confidence threshold; their midpoint is the hands path. Shoulder/ankle span estimates the club search radius. [Apple segmentation](https://developer.apple.com/documentation/vision/vngeneratepersonsegmentationrequest), [Apple body pose](https://developer.apple.com/documentation/vision/vndetecthumanbodyposerequest)
- **Club head:** the shared C++ detector uses three camera-aligned grayscale frames, excludes a dilated person mask and finds the farthest moving component attached near the wrists within the body-height-based club radius. Automatic swing samples are retained around the detected impact (±1.2 seconds). Teal shows wrists and gold shows club. Missing/low-confidence detections remain missing; gaps over 0.18 seconds split the drawn path. There is no automatic substitution of guessed club points.
- **Occlusion:** in settings, toggle **Person occlusion**; select wrists/club/both/off for automatic swing paths. The same renderer removes tracer/swing alpha behind the timestamped person mask in preview and export. Popups, shapes, logo and watermark remain readable. Masks are bounded to 256px width on disk and a small in-memory cache; masks more than 0.11 seconds from the requested frame are not reused. Changing display settings does not re-run analysis.
- **Putt:** normalized cross-correlation follows the selected ball's patch, with velocity prediction, camera offset correction, confidence gating and slow template adaptation. It supports balls below the flight detector's brightness threshold and stops after tracking loss or rest. The output follows observed ground points without a ballistic arc. Flight-time/speed and landing edits retime/adjust this ground path while preserving automatic observations; apex tools are disabled for putts. A mode change clears ball-analysis results and requires Analyze again.

If ball or person analysis fails, the UI reports the available point counts and the failure; successful independent results remain saved. Original observations stay separate from manual edits. Re-analysis keeps previous analysis folders for undo compatibility; project deletion removes those folders too. Detection quality still depends on visibility, motion blur, pose, background contrast and camera movement. Imported-video automatic analysis is distinct from the existing real-time camera recording path: live capture still draws observed ball points and does not run this heavier person/club pass during recording.

Android 14 emulator evidence: an official MediaPipe reference person image plus a dim synthetic rolling ball produced 36 masks, 36 wrist observations and 28 ball observations. The image had no moving club, and the detector returned zero club points. A shared-core synthetic moving shaft test positively detected its tip, excluded person pixels and rejected unattached/static motion. A manual tracer crossing the person's torso was exported both with and without occlusion: the selected torso region had 0 versus 257 red tracer pixels. At the same source time (0.900s), the app preview also had 0 red tracer pixels in that region. This validates runtime wiring/compositing, not golf accuracy. iPhone builds pass; Vision inference and mask alignment still need physical-device validation.

### iPhone live camera recording

Reinstall through Xcode, then tap **Record a live shot**. Allow camera access and optionally microphone access for sound. Full Photos library access is not requested.

1. Hold the phone upright on a stable support with the ball clearly visible.
2. Tap the starting ball in the camera view.
3. Tap **Start recording and tracking**, then hit the shot. Observed paths appear live.
4. Tap **Stop and save**, or wait for the 15-second maximum.
5. The completed video is automatically added to Photos; allow add-only access on first save and use **Save to photo album** to retry if needed. Share from the finished-video review. App-owned recordings also remain in the Files sharing directory under `captures/`, excluded from automatic cloud backup.

iPhone capture uses AVFoundation; the separate Android Camera2 implementation is described below. Live processing uses VGA (portrait 480×640) at up to 30fps; overload may drop frames. Both display and recording use existing `TrackingSession`/`st_render` composition. The yellow selection handle is an editor control and is not recorded. If tracking fails, the video is still saved and the failure is reported. Denied microphone access produces a silent recording with an explicit screen notice.

Backgrounding the app or camera interruption finalizes capture. Reselect the ball for a new session. Live videos have the tracer baked in; they are not editable trajectory projects for the existing editor. Existing import/edit workflows remain available. Physical camera capture, audio synchronization, live throughput, and real golf-shot accuracy remain unverified.


### Android live camera recording

Install the new APK and tap **Record a live shot**. Allow camera access. Microphone access is optional; if denied or unavailable, capture is silent with an explicit notice. Video import alone does not request camera access.

1. Hold the phone upright on a stable support and tap the starting ball.
2. Tap **Start recording and tracking**, then hit the shot. Observed paths appear live.
3. Tap **Stop and save**, or wait for the 15-second maximum.
4. The completed video is automatically added to the gallery under **ShotTracer**. Use **Save to photo album** to retry if needed.

Camera2 YUV_420_888 frames are read using each plane’s row/pixel strides and corrected for sensor orientation. The app requests a maximum dimension of 640px and up to 30fps; actual size/rate depend on the camera. Preview composites the shared `TrackingSession` tracer/watermark, then passes that exact bitmap to the EGL/MediaCodec encoder. The yellow selection handle is not recorded. Late camera frames and UI updates are not queued indefinitely; overload may drop frames.

When available, microphone PCM is encoded as AAC and muxed with H.264 into MP4. Pending packets while codec formats initialize and finalization waits are bounded. If the camera lacks a real-time sensor clock, video arrival time estimates the audio start offset; device synchronization needs validation.

Leaving the view or backgrounding closes the camera and finalizes recording. Temporary `.partial.mp4` files are renamed only on successful completion. Complete videos stay in app-private `files/captures/`; reopening live capture makes the most recent complete video available to save. Tracking failure still saves the watermarked video with a failure notice. Automatic app backups exclude recordings.

Run `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` for validation. Four added JVM tests check padded YUV rows, chroma pixel strides, crop, sensor rotation, and color bounds. Real golf accuracy, device-specific camera/codec support, audio synchronization, and sustained throughput require physical-device validation. Live output has a baked tracer and is not an editable trajectory project.


Android 14 emulator checks with a virtual camera passed: 15-second automatic stop, H.264 480×640 plus AAC MP4, silent manual stop, saving through Downloads, and background finalization of a roughly 2-second clip with no active camera clients. Physical camera/microphone input and real golf-shot accuracy remain unverified.

### Representative image, analysis choices and motion evidence

The owner-supplied `assets/brand/shottracer-user-logo.jpg` is preserved unchanged. A centered square crop supplies the Android launcher/header and iPhone AppIcon/header/empty editor. The earlier generated logo is retained as an unused alternative. No artwork is added to exported videos automatically.

For imported videos, pause just before impact, choose **Set start point** and tap the ball once. Selecting the start only saves its position/time; it does not run analysis. Then choose one of two workflows on either platform: **Analyze** runs offline ball/person/club tracking and fits a predicted trajectory, or **Landing** lets you tap the destination and draw a manual trajectory without analyzing. Seek forward to preview the growing line; adjust landing time on the timeline and the apex handle as needed. Automatic unseen landing remains a prediction, and its position can still be corrected manually. The live-camera workflow still requires starting recording after ball selection.

Settings now includes **Observed video motion**: ball displacement and median projected speed from up to the first two observed seconds, initial screen direction over up to 0.25 seconds, and club median projected speed near impact. These use actual source timestamps, camera-compensated observations and analysis-resolution pixels. They do not use the predicted trajectory, edited timing/handles, or an assumed smash factor. Fewer than three observations, non-increasing timestamps, gaps over 0.125 seconds or insufficient duration withhold the corresponding result. At 30 fps the club can be blurred or missed; this is not a launch monitor.

**Real carry in metres is not implemented by these image-plane statistics.** Camera pose/scale and ball launch conditions are needed; club motion alone cannot identify physical ball speed, spin or carry. A known reference length in the relevant plane and a suitable side-on video can support a future calibrated projection estimate, but not automatically resolve downrange depth or spin. The owner confirmed no calibration data is available and chose automatic tracers plus image-plane speed for this iteration; no unconfirmed physical assumptions are applied. Ball speed, launch angle/direction and spin govern initial flight ([Titleist launch conditions](https://www.titleist.com/learning-lab/performance/golf-shot-launch-conditions)); launch-monitor definitions distinguish club speed, ball speed and carry ([Trackman definitions](https://support.trackmangolf.com/hc/en-us/articles/5089892383515-Practice-Trackman-Data-Parameter-Definitions)). Existing distance/speed popup values remain manually entered.

Validation for this change: 76 Python/shared-core tests; Android debug build, JVM tests and lint; unsigned iPhone device-SDK build. Physical-phone accuracy and capture validation remain pending.

Android 14 emulator workflow check: selecting the start saved its coordinates with zero observations and no fitted model. Selecting a landing then saved a manual endpoint with zero observations. In a fresh copy of the same synthetic 60-frame clip, pressing Analyze after selecting the start produced 55 observations and a 16-value base model. Python/shared-core tests (76), Android build/JVM tests/lint and unsigned iPhone build passed. Physical-phone tracking accuracy remains unverified.

### Manual putt line visibility fix

In Putt mode, a selected start plus a manual landing now renders a ground line without requiring automatic observations. Previously the compositor hid all putts with fewer than two samples; empty-sample interpolation also incorrectly used the image origin. Both native preview/export renderers now allow manual endpoints and interpolate directly from the selected ball to the landing when there are zero or one observations. Start-only putts remain hidden until a landing is selected or automatic tracking supplies a path; tracked ground curves and immutable observations keep their existing behavior.

Use **Putt → Set start point → Landing tool → select destination**, then play or seek forward to see the growing line. Set landing time with the timeline marker as needed. Alternatively, use Analyze after selecting the start to track observed rolling motion.

Validation: 76 Python/shared-core tests, 21 Android JVM tests (three new manual-putt regressions), Android debug assembly/lint, unsigned iPhone build. A separate synthetic Android 14 emulator project with zero observations showed the manual line in preview and a 60-frame/30fps MP4, with tracer pixels verified at the midpoint and landing; automatic photo-album saving completed. iPhone physical-device execution remains unverified.

### Android device-gallery video import

The open/import icon now opens the system Files picker with `video/*`, `CATEGORY_OPENABLE`, `EXTRA_LOCAL_ONLY` and an initial local Videos location, instead of the cloud-enabled Photo Picker. Choose a device video from Videos or browse internal storage → DCIM/Camera, Movies or Download. The picker may look different by manufacturer; it is the system file browser, not a hard-coded Samsung Gallery/Google Photos app. No broad media-library permission is added. Cloud-only videos are excluded by the local-only request; a video already downloaded to device storage can be imported. Up to four selected videos are copied into separate app projects, with the first opened for editing. Original files are kept. iPhone import is unchanged.

Validation: Android 14 emulator opened `DocumentsUI` directly at local Videos, browsed the ShotTracer folder, selected a device-saved MP4 and returned to frame 1 of its 60-frame editor project. Android debug build, 24 JVM tests, lint and 76 Python/shared-core tests passed. Manufacturer-specific picker appearance still needs physical-device confirmation.

### Putt curve midpoint handle

After selecting a putt start and landing, or analyzing a rolling path, a cyan **Putt curve** handle appears halfway along the ground path. Drag it to bend the line, or select the former Apex tool (labeled Putt curve in Putt mode) and tap a new midpoint. Seek forward to preview the complete curve. Start and landing remain exact; undo/redo and project save/reopen preserve the edit. Moving the handle requires no re-analysis. Both preview and export use the same bent ground path.

The existing optional reference-coordinate `apex` field stores this ground midpoint in Putt mode; no new required JSON field is introduced. With no midpoint edit, the previous straight/observed path is unchanged. The offset from the original path midpoint is weighted by `4*f*(1-f)`, so it is zero at both endpoints and exact at halfway through flight time. Automatic observations/baseModel stay immutable; landing correction and retiming still apply. Switching tracking modes or resetting the start clears this midpoint using the existing apex reset behavior.

Validation: 76 Python/shared-core tests, 24 Android JVM tests including three new curve/endpoint/retiming regressions, Android debug assembly/lint and unsigned iPhone build. Android 14 emulator verified the cyan handle, drag persistence, undo/redo and a curved 60-frame MP4 saved to the album; exported tracer pixels were checked at the start, edited midpoint and landing. iPhone physical-device gesture/export validation remains pending.

### Pan while zoomed

On both Android and iPhone, pinch to enlarge the video (1×–6×), then drag an empty area with one finger to pan left/right/up/down without switching tools. Starting a drag on a start/landing/curve handle still edits that handle. Select the hand/pan tool to move the viewport even when the gesture starts over a handle. Double-tap to return to the fitted, centered view. Tap gestures remain available for placing points; a pan drag does not place a point.

Pan limits are calculated independently for the fitted image width and height, including letterboxed landscape videos. Zooming back to 1× centers the image. Pinch zoom uses the focal position, and edit coordinates are mapped back through the zoom and pan. The iPhone gesture surface stays fixed to the viewport to avoid moving-coordinate feedback. Viewport movement does not change saved shot coordinates or export framing.

Validation: 76 Python/shared-core tests, 27 Android JVM tests, Android assembly/lint and unsigned iPhone build. An isolated temporary instrumentation probe on Android 14 dispatched actual multi-pointer events to the editor PreviewView: pinch to ~1.67×, horizontal and vertical panning without project changes, handle edits after panning, explicit-pan priority over handles and double-tap fit all passed. The probe and its APK remain outside the repository. iPhone physical-device gesture validation remains pending.

### Android explicit analysis and club-path opt-in

Imported-video analysis starts only from either Analyze button. Selecting the starting ball pauses playback, saves its position/time and shows the manual/automatic choice; it does not start tracking. The two button callbacks are the only callers of `analyzeFromButton`. Live recording still starts tracking when the user explicitly starts recording.

New Android projects default Automatic swing paths to Off. Club-head paths require Settings → Automatic swing paths → Club or Both, then save settings. A new optional `clubTracingEnabled` field defaults to false in older projects: their previous default `autoSwing=both` is not treated as explicit club opt-in. Saved wrist display choices remain usable; the settings selection reflects the paths actually permitted. Explicitly enabling club paths persists across save/reopen, and Off/Hands disables them again. The global swing-display switch is also respected. Observations and automatic models remain stored, so enabling display after analysis does not require re-analysis. Both preview and export use the same display gate. This change applies to Android; iPhone settings are unchanged.

Validation: 76 Python/shared-core tests, 30 Android JVM tests, Android assembly/lint. Android 14 emulator confirmed zero observations after selecting the start, then 55 observations and a fitted model after pressing Analyze, with club display still disabled. A temporary renderer probe checked that legacy Both produced no club pixels, explicit Club persisted/reloaded and rendered, Off hid them again and all 11 synthetic club observations remained intact. Install the latest APK to receive these behaviors.
