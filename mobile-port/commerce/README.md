# Subscription implementation and store setup

Owner-confirmed model (2026-11-03): first launch starts 72 hours of free use. There is no automatic subscription enrollment. After the trial the user must explicitly confirm a USD 2.99/month auto-renewing subscription to continue editing. Public special codes use the storefront; separately identified tester apps accept individually signed +72-hour codes. Store accounts, purchases and store publication are not created by this code change.

## Implemented app flow

- The first-launch screen explains the three days, requested US price, purchase consent and renewal/cancellation. Continue enters the free app without paying or entering card details.
- Access state is separate from video projects. Project reset/deletion does not reset the trial. Expiry gates new imports, editing, analysis, exports and camera recording. Already-started work may finish and save its result.
- Android: Play Billing 8.3.0 loads the monthly base plan; the buy button stays disabled until the storefront supplies a price and the owner's Play licensing public key is supplied. Only PURCHASED, correctly signed purchases for the expected package/product can grant access; initial purchases are acknowledged. Pending/cancelled/unverified purchases never grant paid access. Restore and foreground queries refresh active purchases. A signed active-purchase check creates an app-private offline lease of at most 24 hours only when Android reports a validated network; an online successful empty query revokes it. Cached offline store replies do not renew that lease. No service-account credentials are embedded.
- iPhone: StoreKit 2 verified current entitlements and verified grace-period renewal information determine access. Purchases, restore and transaction updates use StoreKit. Revoked/upgraded/expired entries do not grant access. The verified transaction expiry is usable offline through StoreKit's cache.
- Trial time uses stored wall-clock history plus a monotonic process clock; large backwards clock changes deny local access. iPhone trial/codes are stored in a device-only Keychain item; Android uses app-private, backup-disabled preferences. **Android uninstall/clear-data can reset the local trial; rooted/jailbroken/local state tampering and reinstall-proof trials are not solved.** A production-grade account/server entitlement and trusted-time layer is needed if this threat matters. Do not describe this local trial as fraud-proof. No verification server is deployed here, and no videos are uploaded.
- Declared prices come from native storefront metadata at checkout. USD 2.99 is the requested US base price, not a promise of the same numeric amount in other currencies.

## Required Play Console setup

1. Register the intended Android application/package and complete its payments profile, agreements and testing requirements. The current development package is `com.ajc.shottracer.dev`; finalize the public ID before registering products.
2. Create auto-renewing subscription **shottracer_monthly**, base plan **monthly**, **P1M**, US base price **USD 2.99**. Do not add another introductory free trial to the ordinary base plan: the app already gives the first 72 hours without a purchase. Activate the plan in intended countries.
3. Copy the app's licensing RSA public key (public, not a service-account/private key). Build with `-PshottracerPlayPublicKey=BASE64_PUBLIC_KEY` or store that property in an uncommitted local Gradle properties file. With an empty key the purchase button deliberately remains disabled, and `verifyReleaseSubscription` rejects a release build until an RSA public key is configured.
4. Upload a signed build to an appropriate Play testing track, configure license testers and validate purchase, pending approval, acknowledgement, restore, cancellation, renewal, grace/account hold and refund/revocation. Sideloaded APK builds and local policy tests are not proof of working store purchases. Consider server verification/RTDN for production and longer offline leases.
5. Create individual official promo codes in Play Console. The in-app Redeem action routes to Google Play; returning to the app re-queries ownership. Eligibility, supported free durations and redemption limits are determined by Play, not by the local +3-day tester issuer.

## Required App Store Connect setup

1. Apple Developer Program enrollment is still outstanding. Configure the app's real bundle ID, signing and agreements/banking/tax details.
2. Create subscription group **ShotTracer** and product **com.ajc.shottracer.monthly**, one month, US price **USD 2.99**, without a second introductory trial on the ordinary product. Provide subscription localization/review metadata, required privacy policy/support URLs and the renewal terms. The app already grants the first 72 hours without purchasing.
3. Use the native Redeem code sheet for official App Store offer codes. Do not put proprietary license-code unlocks in the public App Store build. Repeat three-day redemptions depend on App Store offer durations and customer eligibility and cannot be guaranteed independently of those rules.
4. `ShotTracer.storekit` is a local Xcode StoreKit configuration with the monthly USD 2.99 test product. Select it under Scheme → Run → Options → StoreKit Configuration to prepare local purchase tests; it is not bundled as a production entitlement. Its JSON syntax is checked, but full StoreKit purchase/refund execution still requires Xcode/device testing in this environment.
5. Validate sandbox/TestFlight purchase, renewal, cancellation, pending approval, restore, grace period, refund/revocation and offline expiry on an iPhone. No real or sandbox purchase has been made in this task.

## Separate tester builds and owner-issued extension codes

Public Android debug/release variants have `TEST_CODES=false`. Android `preview` has a separate application ID ending in `.tester`, the name **ShotTracer Tester**, and accepts tester codes. Do not submit this build to Play. Build it with `:app:assemblePreview`; install `app/build/outputs/apk/preview/app-preview.apk`.

For iPhone, explicitly build with both `DEBUG SHOTTRACER_TEST_CODES` compilation conditions, a separate bundle ID ending in `.tester`, and a Tester display name. Ordinary builds compile the redemption method to return false. Example (signing/device installation remains owner-managed):

```sh
xcodebuild -project app/ios/ShotTracer.xcodeproj -scheme ShotTracer -configuration Debug -sdk iphoneos -destination generic/platform=iOS -derivedDataPath /private/tmp/shottracer-mobile-ios-tester CODE_SIGNING_ALLOWED=NO 'SWIFT_ACTIVE_COMPILATION_CONDITIONS=DEBUG SHOTTRACER_TEST_CODES' PRODUCT_BUNDLE_IDENTIFIER=com.ajc.shottracer.dev.tester INFOPLIST_KEY_CFBundleDisplayName=ShotTracerTester build
```

The owner issuer key was initialized locally at **mobile-port/private/test-code-issuer.pem**, with owner-only file permissions. It is ignored by Git and is never embedded in either app. `test-code-public-key.txt` and the native verifier constants contain only its public key. Preserve the private file securely; replacing it requires rotating the public key in both apps. `init` refuses to overwrite an existing issuer.

In the tester subscription screen copy the **Tester device ID**, then run from the repository root:

```sh
.venv/bin/python mobile-port/tools/issue_access_codes.py issue --key mobile-port/private/test-code-issuer.pem --device 'COPIED-DEVICE-ID' --platform android
# Use --platform ios for the separately identified iPhone tester app.
```

Send the resulting `ST3...` code manually to that tester. Paste it into the tester app's special-code field (iPhone has a Tester extension code section). Each signed code has a unique nonce, platform, device ID, a seven-day redemption window and exactly 259200 seconds of extension. Applying it changes expiry to **max(current expiry, now) + 72 hours**. Used nonces persist with expiry, so the same code cannot be reused after reopening. New independently issued codes can extend again; there is no shared universal unlock password. Tester app reinstall/cleared data is outside this local replay guarantee. No issuer key or code issuance network endpoint is shipped.

## Sources checked

- Apple App Review 3.1.1 (store unlock mechanisms): https://developer.apple.com/app-store/review/guidelines/
- Apple subscription offers and customer disclosures: https://developer.apple.com/app-store/subscriptions/
- Google Billing purchase/pending/acknowledgement integration: https://developer.android.com/google/play/billing/integrate
- Google promo-code eligibility/durations: https://developer.android.com/google/play/billing/promo
- Play Billing 8.3.0: https://developer.android.com/google/play/billing/release-notes

See the English/Korean mobile READMEs for the latest build and local-test results. Actual store-product activation, signed distribution, privacy/store metadata and real billing lifecycle tests remain release blockers.

Subscriptions are currently storefront/account-specific. Android and iPhone do not share one cross-platform paid account; that would require an additional account/server design. Store restore applies to the corresponding store account.
