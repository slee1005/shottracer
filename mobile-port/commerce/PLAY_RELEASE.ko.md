# Google Play 무료판 배포 안내

패키지 **com.ajc.shottracer**, 버전 **0.0.1**, 코드 **3**, 최소 API **34**, compile/target **36**. 개발판 `.dev`·테스터판 `.dev.tester`는 별도 데이터를 유지하며 모두 무료다. 기본 워터마크는 유지한다.

## 로컬 산출물

- Play 업로드: `mobile-port/release/ShotTracer-0.0.1-free-play.aab`
- 직접 설치 APK: `mobile-port/release/ShotTracer-0.0.1-free.apk`
- AAB 옆 `.aab.sha256` 및 `.verification.json`: 체크섬·검증 결과
- 기존 업로드 서명: Git에서 제외한 `private/android-upload.p12`, `private/android-upload.properties`, `private/android-upload-certificate.pem`

서명키·설정 파일은 안전하게 함께 백업한다. 무료판에 Play 구매 검증 공개키나 테스터 발급 개인키는 필요 없다. 이전 `ShotTracer-0.0.1-play.aab`는 코드 1 구독판이므로 무료 배포에 사용하지 않는다. 산출물은 커밋하지 않는다.

## 재빌드

JDK 17, SDK 36, NDK 27.0.12077973, CMake 3.22.1, Gradle 8.11.1을 사용한다. `ANDROID_HOME`을 준비된 SDK로 지정하고 저장소 루트에서 실행한다.

```sh
.venv/bin/python mobile-port/tools/build_play_bundle.py --bundletool /절대경로/bundletool-all-1.18.3.jar --java-home /절대경로/jdk17
```

이미 설치된 Gradle을 쓰려면 `--gradle /절대경로/gradle`을 추가한다. bundletool 1.18.3의 고정 SHA-256은 `a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`다. 도구는 번들·JVM 테스트·출시 린트를 실행하고 서명 인증서·JAR·패키지·API·모델 해시·16KB ELF/번들 정렬 및 결제 SDK·결제/네트워크 권한 부재를 확인한다. 설치 APK는 별도 `:app:assembleRelease` 결과 `app/android/app/build/outputs/apk/release/app-release.apk`다.

## 제출 전

1. Play Console에서 앱 가격을 **무료**로 설정한다. 이 앱에는 구독 상품 설정이 필요 없다.
2. 코드 **3**의 무료판 AAB를 내부 테스트에 업로드하고 기존 앱 서명을 유지한다. 이미 코드 3 이상을 올렸다면 더 높은 versionCode로 재빌드한다.
3. 개인정보·Data safety·콘텐츠 등급·앱 액세스·심사 안내와 계정별 테스트·프로덕션 접근 요건을 확인한다. 최신 Console 요구 사항은 제출 시 다시 확인한다.
4. 실기기의 영상 가져오기·추적·출력·앨범·카메라·마이크·16KB 환경을 검증한다.

로컬 빌드 검증은 스토어 심사 승인이나 실기기 품질 검증이 아니다. 이번 작업에서 Play 업로드·공개·계정 변경은 수행하지 않는다. iPhone은 별도 Apple 가입·서명·실기기·App Store 준비가 필요하다.
