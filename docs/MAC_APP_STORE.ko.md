# AJCShotTracer Mac App Store 빌드 및 심사

2026년 11월 14일, Apple Silicon용 macOS **0.0.1 (빌드 3)**을 제출했고 App Store Connect에서 **Waiting for Review**를 확인했습니다. 기존 iOS 앱과 같은 앱 레코드와 번들 ID `com.johnajc.shottracer`를 사용했습니다. 출시 방식은 수동입니다. [macOS 버전 페이지](https://appstoreconnect.apple.com/apps/6834880054/distribution/macos/version/inflight)

## 제출용 PKG 빌드

1. Apple Developer의 **Certificates, Identifiers & Profiles → Profiles**에서 **Mac App Store Connect → Mac** 프로파일을 만듭니다. 기존 `com.johnajc.shottracer` App ID와 이 Mac의 키체인에 개인 키가 있는 **Apple Distribution** 인증서를 선택하고 프로파일을 다운로드합니다. 같은 팀의 **Mac Installer Distribution** 인증서도 키체인에 있어야 합니다. 기존 인증서는 키체인에서 표시되는 이름을 그대로 사용합니다. [Apple 프로비저닝 안내](https://developer.apple.com/help/account/provisioning-profiles/create-an-app-store-provisioning-profile/)
2. Apple Silicon Mac에서 아래 명령을 실행합니다. `<다음 빌드 번호>`는 App Store Connect에 이미 올린 macOS 빌드 번호보다 큰 정수로 바꾸세요. 프로파일과 인증서 이름은 계정 환경에 맞게 지정합니다.

   ```bash
   uv sync --group desktop
   uv run --group desktop python packaging/build.py --mac-app-store \
     --build-number '<다음 빌드 번호>' \
     --profile "$HOME/Downloads/AJCShotTracer_macOS_App_Store.provisionprofile" \
     --app-identity 'Apple Distribution: John Lee (X389CZ6G6D)' \
     --installer-identity '3rd Party Mac Developer Installer: John Lee (X389CZ6G6D)'
   ```

3. `dist/AJCShotTracer-<버전>-build<번호>-MacAppStore-arm64.pkg`와 SHA-256 파일을 확인합니다. 스크립트는 서명 전에 앱을 자체 테스트하고, 프로파일을 `Contents/embedded.provisionprofile`에 넣습니다. 임시 앱 번들 전체의 `com.apple.quarantine` 속성을 확인하고 제거한 뒤, FFmpeg와 라이브러리부터 앱까지 서명합니다. 최종 PKG를 다시 풀어 앱 서명, 프로파일 내용, quarantine 속성 부재를 확인합니다. 스토어 배포 프로파일이 포함된 앱의 설치 및 실행 검증은 TestFlight에서 진행할 수 있습니다.

## 업로드와 심사 제출

1. App Store Connect의 AJCShotTracer 레코드에 **Add Platform → macOS**로 플랫폼을 추가합니다. macOS 버전은 PKG의 마케팅 버전과 일치해야 합니다.
2. Apple Transporter에 서명된 PKG를 추가하고 **Deliver**를 누릅니다. App Store Connect의 **TestFlight → macOS → Build Uploads**에서 해당 빌드가 **Complete**로 바뀔 때까지 확인합니다. Transporter의 **Delivered**는 전송 완료를 뜻합니다. [Apple 빌드 업로드 안내](https://developer.apple.com/help/app-store-connect/manage-builds/upload-builds/)
3. **Distribution → macOS 버전 → Add Build**에서 번호를 확인해 빌드를 연결합니다. Mac 전용 설명과 스크린샷, 심사 연락처 및 테스트 절차, 지원·마케팅 URL을 확인합니다. 이번 제출에는 실제 편집기 화면을 캡처한 1440 × 900 스크린샷을 사용했습니다. 샌드박스 설명에는 사용자가 선택한 영상과 저장 대상에 접근하기 위한 권한 및 `127.0.0.1`에서만 실행되는 편집기 서버를 기록했습니다.
4. 수동 출시를 선택하고 **Save → Add for Review → Submit for Review**를 누릅니다. **Waiting for Review**가 표시되면 심사 접수가 완료된 것입니다.

## 첫 제출에서 해결한 오류

| 빌드 | Apple 응답 | 해결 |
| --- | --- | --- |
| 1 | **90889**. 프로비저닝 프로파일 누락으로 TestFlight 사용 불가 | App ID와 Apple Distribution 인증서가 일치하는 Mac App Store 프로파일 생성 및 포함 |
| 2 | **91109**. 내장 프로파일에 다운로드 시 붙은 `com.apple.quarantine` 속성이 남아 처리 실패 | 제출용 번들에서 해당 속성을 제거한 뒤 재서명. 스크립트에서는 프로파일의 파일 내용만 복사하고 완성된 PKG도 검사 |
| 3 | 처리 완료 및 심사 접수 | App Store Connect에서 macOS 0.0.1에 빌드 3을 연결하고 제출 |
