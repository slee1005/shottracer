# 구독 구현과 스토어 설정

확정 모델(2026-11-03): 첫 실행부터 72시간 무료. 자동 구독 가입은 하지 않으며, 무료 기간 후 고객이 명시적으로 동의하면 월 US$2.99 자동 갱신 구독으로 편집을 계속 이용한다. 공개 앱의 특별 코드는 스토어 공식 코드를 사용하고, 별도 테스터 앱은 개별 서명한 72시간 연장 코드를 허용한다. 이번 개발에서 스토어 계정·상품·실제 결제·공개 출시는 실행하지 않았다.

## 구현한 앱 흐름

- 첫 실행 화면에서 3일 무료, 미국 기준 가격, 구매 동의와 갱신·취소를 안내한다. 계속 사용을 누르면 결제·카드 입력 없이 무료로 시작한다.
- 이용 권한은 영상 프로젝트와 별도로 저장한다. 프로젝트 삭제·초기화로 무료 기간이 재시작하지 않는다. 만료 후 새 가져오기·편집·분석·출력·촬영을 제한하되 이미 시작한 작업은 결과를 저장할 수 있다.
- Android: Play Billing 8.3.0으로 월 기본 요금제를 읽는다. 스토어 가격과 운영자의 Google Play 검증 공개키가 모두 준비되어야 구매 버튼이 활성화된다. 예상 앱·상품의 서명이 맞고 PURCHASED인 구매만 확인 처리 후 이용 권한을 부여한다. 승인 대기·취소·검증 실패는 유료 권한을 부여하지 않는다. 복원과 앱 복귀 시 활성 구매를 조회한다. Android가 인터넷 연결을 확인한 경우에만 최대 24시간 오프라인 캐시를 갱신하며, 온라인 조회에서 활성 구매가 없으면 취소한다. 오프라인 캐시 응답으로는 기간을 다시 늘리지 않는다. 서비스 계정 비밀키는 앱에 넣지 않는다.
- iPhone: StoreKit 2가 검증한 현재 거래·유예 기간 갱신 정보를 사용한다. 구매·복원·거래 업데이트를 처리하고 철회·업그레이드 전 거래·만료 거래는 권한을 주지 않는다. 검증한 거래 만료 시각은 StoreKit 캐시로 오프라인에서도 사용할 수 있다.
- 로컬 시계 기록과 단조 증가 시계를 함께 사용하며 큰 시계 역행 시 로컬 이용을 차단한다. iPhone은 기기 전용 Keychain, Android는 앱 전용·백업 제외 설정을 사용한다. **Android 앱 삭제/데이터 초기화로 로컬 무료 체험이 다시 시작될 수 있으며, 루팅·탈옥·기록 변조와 재설치까지 방지한 모델은 아니다.** 필요하면 출시 전 계정·서버 검증·신뢰 시각을 보강해야 한다. 서버는 배포하지 않았고 영상을 업로드하지 않는다.
- 실제 구매 가격은 스토어가 표시한다. US$2.99는 미국 기본 가격이며 다른 통화의 숫자가 모두 2.99라는 의미는 아니다.

## Google Play Console 준비

1. 최종 앱 ID를 확정하고 앱·결제 프로필·약관·테스트 요구 사항을 준비한다. 현재 개발 ID는 `com.ajc.shottracer.dev`다.
2. 구독 상품 `shottracer_monthly`, 기본 요금제 `monthly`, 기간 `P1M`, 미국 가격 USD 2.99로 등록·활성화한다. 앱이 이미 첫 72시간을 무료로 제공하므로 일반 요금제에는 무료 체험을 중복 추가하지 않는다.
3. 앱의 라이선스 RSA **공개키**를 복사한다. 서비스 계정 개인키가 아니다. `-PshottracerPlayPublicKey=BASE64_PUBLIC_KEY`로 빌드하거나 커밋하지 않는 로컬 Gradle 설정에 넣는다. 공개키가 비어 있으면 구매 버튼은 비활성화된다. `verifyReleaseSubscription`은 RSA 공개키가 준비되지 않은 출시 빌드도 거부한다.
4. 서명한 앱을 Play 테스트 트랙에 등록하고 라이선스 테스터로 구매·승인 대기·확인 처리·복원·취소·갱신·유예/보류·환불/철회를 시험한다. 직접 설치한 APK와 로컬 정책 테스트만으로 실결제가 검증된 것은 아니다. 안정적인 출시 검증과 긴 오프라인 기간이 필요하면 서버 검증/RTDN을 검토한다.
5. Console에서 개별 공식 프로모션 코드를 발급한다. 앱의 코드 사용은 Google Play로 연결하고 돌아오면 구매를 다시 조회한다. 사용 자격·지원 무료 기간·횟수 제한은 Google 정책을 따르며 자체 테스터 코드와 별개다.

## App Store Connect 준비

1. Apple Developer Program 가입이 아직 필요하다. 실제 앱 ID·서명과 약관·은행·세금 정보를 준비한다.
2. ShotTracer 구독 그룹과 `com.ajc.shottracer.monthly` 상품을 한 달·미국 USD 2.99로 등록한다. 앱의 첫 72시간 체험과 중복되는 기본 상품 무료 체험은 추가하지 않는다. 번역·심사 정보·개인정보 처리방침/지원 URL·갱신 약관을 준비한다.
3. 공개 앱에서는 네이티브 App Store 공식 코드 입력창을 사용한다. 자체 라이선스 코드로 공개 앱 기능을 해제하지 않는다. 공식 코드의 3일 반복 연장은 기간·고객 자격 규칙에 따라 달라져 보장하지 않는다.
4. `ShotTracer.storekit`은 월 USD 2.99 상품의 로컬 Xcode 테스트 설정이다. Scheme → Run → Options → StoreKit Configuration에서 선택한다. 실제 결제 권한으로 앱에 포함하지 않는다. JSON 문법은 확인했으며 Xcode/기기에서의 구매·환불 실행은 별도 검증이 필요하다.
5. iPhone의 Sandbox/TestFlight에서 구매·갱신·취소·승인 대기·복원·유예·환불/철회·오프라인 만료를 확인한다. 이번 작업에서는 실제·샌드박스 결제를 하지 않았다.

## 별도 테스터판과 3일 연장 코드

Android 일반 debug/release는 `TEST_CODES=false`다. `preview`는 앱 ID 뒤에 `.tester`가 붙고 **ShotTracer 테스터**로 표시되는 별도 앱이다. Play에 이 빌드를 제출하지 않는다. 빌드 명령은 `:app:assemblePreview`, 결과는 `app/build/outputs/apk/preview/app-preview.apk`다.

iPhone 테스터판도 별도 앱 ID·표시 이름과 `DEBUG SHOTTRACER_TEST_CODES` 조건을 모두 지정한다. 일반판의 자체 코드 함수는 항상 거부한다. `mobile-port`에서 빌드 예시는 다음과 같다. 서명·기기 설치는 별도로 준비해야 한다.

```sh
xcodebuild -project app/ios/ShotTracer.xcodeproj -scheme ShotTracer -configuration Debug -sdk iphoneos -destination generic/platform=iOS -derivedDataPath /private/tmp/shottracer-mobile-ios-tester CODE_SIGNING_ALLOWED=NO 'SWIFT_ACTIVE_COMPILATION_CONDITIONS=DEBUG SHOTTRACER_TEST_CODES' PRODUCT_BUNDLE_IDENTIFIER=com.ajc.shottracer.dev.tester INFOPLIST_KEY_CFBundleDisplayName=ShotTracerTester build
```

발급 개인키는 로컬 **mobile-port/private/test-code-issuer.pem**에 생성했으며 파일 권한은 소유자 전용이다. Git에서 제외하고 앱에도 포함하지 않는다. `test-code-public-key.txt`와 양쪽 앱에는 공개키만 들어 있다. 개인키를 안전하게 보관하고, 교체하려면 양쪽 앱 공개키도 함께 바꿔야 한다. 도구의 `init`은 이미 있는 키를 덮어쓰지 않는다.

테스터 앱의 구독 화면에서 **테스터 기기 ID**를 복사한 뒤 저장소 루트에서 발급한다.

```sh
.venv/bin/python mobile-port/tools/issue_access_codes.py issue --key mobile-port/private/test-code-issuer.pem --device '복사한-기기-ID' --platform android
# 별도 iPhone 테스터판은 --platform ios로 발급한다.
```

출력된 `ST3...` 코드를 해당 테스터에게 직접 전달한다. 테스터 앱의 특별 코드 입력란에 붙여넣는다. iPhone에는 테스터 연장 코드 섹션이 있다. 각 코드는 고유 ID·플랫폼·기기 ID·7일 사용 기한·정확히 259200초 연장 정보를 서명한다. 사용 시 **max(현재 만료, 현재 시각)+72시간**으로 바꾼다. 사용 기록과 만료 시각을 함께 저장해 다시 열어도 같은 코드를 사용할 수 없다. 새 코드를 개별 발급하면 계속 연장할 수 있으며 공통 무제한 암호는 없다. 테스터 앱 삭제/데이터 초기화는 로컬 재사용 보장의 범위 밖이다. 앱에 개인키나 온라인 발급 API를 배포하지 않는다.

## 확인한 공식 자료

- Apple 심사 3.1.1: https://developer.apple.com/app-store/review/guidelines/
- Apple 구독 혜택·고객 안내: https://developer.apple.com/app-store/subscriptions/
- Google 결제·대기·확인 처리: https://developer.android.com/google/play/billing/integrate
- Google 공식 코드 자격·기간: https://developer.android.com/google/play/billing/promo
- Play Billing 8.3.0: https://developer.android.com/google/play/billing/release-notes

최신 빌드·로컬 검증 결과는 상위 README 두 언어판을 참고한다. 실제 상품 활성화, 서명 배포, 개인정보/스토어 정보와 실제 결제 전 과정 검증이 출시 전에 남아 있다.

현재 구독은 각 스토어 계정별이다. Android와 iPhone이 하나의 플랫폼 공통 유료 계정을 공유하지는 않으며, 이를 원하면 별도 계정·서버 설계가 필요하다. 구매 복원은 해당 스토어 계정에 적용한다.
