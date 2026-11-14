# Google Play 업로드 번들

정식 ID **com.ajc.shottracer**, 버전 **0.0.1**, 버전 코드 **1**, Android 14 이상(최소 34), compile/target **36**이다. `.dev`와 `.dev.tester` 앱은 별도로 유지한다. 정식 앱 설치는 해당 개발·테스터 앱의 내부 프로젝트를 덮어쓰지 않는다.

## 이 Mac에 생성한 파일

- 업로드 파일: `mobile-port/release/ShotTracer-0.0.1-play.aab`(43,612,838바이트).
- 체크섬: 바로 옆 `.aab.sha256` 파일.
- 검증 결과: 옆의 `ShotTracer-0.0.1-play.verification.json`.
- 비공개 업로드 키: `mobile-port/private/android-upload.p12`.
- 비공개 서명 설정·비밀번호: `mobile-port/private/android-upload.properties`.
- 공개 업로드 인증서: `mobile-port/private/android-upload-certificate.pem`.
- Play 구매 검증 공개키: `mobile-port/private/play-billing-public-key.txt`.

이후 업데이트를 위해 비공개 키 저장소와 설정 파일을 **함께 안전하게 백업**한다. 비밀번호·키 저장소 내용을 채팅으로 보내거나 커밋하지 않는다. 업로드 키, Play 라이선스 공개키, 테스터 코드 발급키는 서로 다른 용도다. 비공개 파일과 AAB·검증 결과는 모두 Git에서 제외한다. 테스터 코드 발급을 계속하려면 별도 발급 개인키도 보관한다.

## 업로드

1. Play Console에서 새 앱을 선택하고 이 AAB를 내부 테스트 릴리스에 업로드한 뒤 Play 앱 서명을 설정한다. 번들은 새 운영자 업로드 키로 서명했다. 실제 배포 앱 서명키는 Play 앱 서명에서 Google이 관리한다.
2. Console에서 패키지 **com.ajc.shottracer**, 버전 코드 **1**, 대상 API **36**과 인증서를 확인한다. 빌드 도구가 업로드·공개를 자동 실행하지는 않았다.
3. `shottracer_monthly` / `monthly`를 한 달·미국 USD 2.99로 등록/활성화하고 라이선스 테스터로 구매·복원·승인 대기·갱신·취소·환불을 확인한다. 앱이 첫 실행 72시간을 무료로 제공하므로 일반 상품에 무료 체험을 중복 추가하지 않는다.
4. 스토어 정보, 개인정보/Data safety, 앱 액세스·심사 안내, 콘텐츠 등급, 계정 확인과 해당 계정의 테스트·프로덕션 접근 요건을 완료한다. 실제 기기에서 카메라·추적·출력·앨범 저장을 확인한다. 번들 구조 검증이 심사 승인이나 실기기 검증을 의미하지는 않는다.

## 재빌드

JDK 17, SDK 플랫폼 36, Android 빌드 도구, NDK 27.0.12077973, CMake 3.22.1을 준비하고 JAVA_HOME/ANDROID_HOME을 지정한다. Gradle wrapper 8.11.1과 AGP 8.10.1을 고정했다. 네이티브 빌드의 `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`을 유지한다.

공식 **bundletool-all-1.18.3.jar**: https://github.com/google/bundletool/releases/tag/1.18.3 . 필수 SHA-256은 `a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`다.

저장소 루트에서 실행한다.

```sh
.venv/bin/python mobile-port/tools/build_play_bundle.py --bundletool /절대경로/bundletool-all-1.18.3.jar --java-home /절대경로/jdk17
```

도구는 로컬 서명 설정과 제공된 라이선스 공개키를 읽어 `bundleRelease`, JVM 테스트와 출시 린트를 실행하고 AAB를 검증한 뒤 `release/`에 파일·체크섬·검증 결과를 복사한다. 키가 없거나 유효하지 않거나 서명 설정이 누락되면 출시 빌드를 중단한다. 가짜 구매 검증 키를 생성하지 않는다. 이후 Play 업로드는 versionCode를 높여야 하며 기존 업로드 키를 계속 사용하고 새로 초기화하지 않는다.

## 완료한 검증

- 정식 패키지·디버그/테스트 전용 해제·서명된 AAB와 예상 업로드 인증서 일치.
- 공식 SHA-256으로 확인한 bundletool의 구조 검사와 JAR 서명 검사.
- 실제 대상/최소 API·버전·패키지 매니페스트 값 검사.
- arm64-v8a·x86_64 라이브러리 4개의 16KB 이상 ELF LOAD 정렬·주소 일치와 BundleConfig의 PAGE_ALIGNMENT_16K 요청 검사.
- 고정된 포즈 모델 해시와 제공된 Play 라이선스 공개키 포함 검사.
- Python/코어/발급기/아티팩트 테스트 85개, JVM 테스트 38개, 개발·출시 빌드·린트 통과.

2026-11-03 확인한 공식 자료: [대상 API](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en), [16KB 페이지](https://developer.android.com/guide/practices/page-sizes), [앱 서명](https://developer.android.com/studio/publish/app-signing). 실제 구독 결제, Play 접수·심사와 16KB 실기기 동작 검증은 남아 있다.
