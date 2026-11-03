# Mobile port handoff

Follow the root `../AGENTS.md`. Read both README files here and `../docs/ARCHITECTURE.md` before implementing the mobile port.

- This directory contains an initial native app prototype plus the release plan. Keep implemented, build-validated, device-validated, and proposed functionality distinct. Read README section 9 before extending it.
- First implementation: SwiftUI iOS and Kotlin Android call the shared C++ C ABI. Flutter remains an alternative, not an installed dependency.
- iPhone LiveCamera processes capture, tracking, and writing on one serial queue, using the same TrackingSession raster for live preview and saved video. Keep the default watermark, optional microphone notice, 15-second limit, and camera shutdown on dismissal/background/interruption. Real-device capture remains unverified.
- Keep mobile work in this directory. Preserve existing desktop behavior and the owner's unrelated changes.
- The user confirmed standalone offline phone use with automatic ball tracking required in the first release, on Android and iPhone. A manual-only editor is an internal milestone, not fulfillment. Do not add PC/cloud dependencies or runtime network calls without an explicit scope change.
- The user confirmed public distribution through Apple App Store and Google Play. Include signing, store metadata, privacy disclosures, and applicable beta-testing requirements in release preparation. Account enrollment, payments, and actual publication are separate actions; do not perform them from this planning confirmation.
- The user already has a Google Play developer account. Its individual/organization type, creation date, verification, and production access are unconfirmed; do not assume testing exemptions or require new enrollment. The user confirmed they have not joined Apple Developer Program.
- Interpret the user's requested iOS support as iOS 26.6.2 or later, an OS requirement rather than an iPhone model. This is a requested target, not verified SDK/deployment-target compatibility. Validate toolchain support and physical devices before claiming compatibility; the user specified Android 14 or later as the planned minimum. Keep minimum OS separate from the store submission target SDK.
- Keep the existing starting-ball selection workflow as the planning baseline. Fully automatic initial ball localization and first-release inclusion of person occlusion, automatic swing paths, and putt tracking remain separate scope decisions.
- Preserve default-on watermark and salted scrypt verification, `base_model`, fast edits, old project defaults/validators, coordinate meanings, and en/ko localization.
- Preview and export must use the same mobile rendering implementation and scene rules. Compare with desktop numeric and visual references; do not create preview-only effects.
- Keep the English and Korean READMEs aligned. For future mobile UI, document localization mapping; web UI strings still belong in `../src/shottracer/web/i18n.js`.
- Validate native media decoding, shared rendering, and export on physical Android and iPhone devices before expanding the port. Record measurements and device/OS versions.
- Add meaningful compatibility/numeric tests when porting model behavior. Run root `uv run pytest` after changes, plus applicable mobile checks once an implementation exists.
- Use temporary `SHOTTRACER_HOME` for experiments. Never touch the owner's real projects or commit source videos, installers, build artifacts, credentials, or signing keys.
