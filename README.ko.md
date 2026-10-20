[English](README.md) | **한국어**

# shottracer <a href="https://www.paypal.com/donate/?business=57PYF6ECLXA6L&no_recurring=0&currency_code=USD"><img src="https://www.paypalobjects.com/en_US/i/btn/btn_donate_SM.gif" border="0" title="PayPal - The safer, easier way to pay online!" alt="Donate with PayPal button" /></a>

골프 스윙 동영상에 공의 비행 궤적(트레이서), 스윙 경로, 데이터 팝업을 입히는 로컬 편집기.
영상은 이 컴퓨터 밖으로 나가지 않습니다.

## 독립 실행 앱

Shot Tracer는 독립 실행 앱으로 만들 수 있습니다. Python, uv, ffmpeg 설치가 필요 없고, ffmpeg와 자세 인식 모델까지
모두 앱 안에 들어갑니다. 앱 파일은 이 저장소에 **올리지 않으므로**, 사용할 운영체제에서 직접 빌드하세요:

```bash
uv sync --group desktop
uv run --group desktop python packaging/build.py      # -> dist/ 폴더
```

GitHub에서는 **Actions → Build app → Run workflow** 로 macOS·Windows용을 함께 빌드할 수 있고, 실행 페이지에서
7일 동안 내려받을 수 있습니다.

| 시스템 | 파일 | 설치 |
| --- | --- | --- |
| macOS, Apple 실리콘(M1 이상) | `ShotTracer-<버전>-mac-arm64.dmg` | DMG를 열고 **Shot Tracer**를 **응용 프로그램** 폴더로 끌어 놓기 |
| Windows 10/11, 64비트(x64) | `ShotTracer-<버전>-windows-x64.zip` | 오른쪽 클릭 → **압축 풀기…** 후 `ShotTracer\ShotTracer.exe` 실행 |

아직 유료 개발자 인증서로 서명하지 않아서, 처음 실행할 때 경고가 나옵니다.

- **macOS:** "확인되지 않은 개발자가 배포했기 때문에 열 수 없습니다" → 창을 닫고, 응용 프로그램 폴더에서
  **Shot Tracer**를 오른쪽 클릭 → **열기** → **열기**. (또는 시스템 설정 → 개인정보 보호 및 보안 → **그래도 열기**.)
  처음 한 번만 하면 됩니다.
- **Windows:** "Windows의 PC 보호" → **추가 정보** → **실행**. 앱 창은 Microsoft Edge WebView2를 쓰며, Windows 11과
  최신 Windows 10에는 기본으로 들어 있습니다. 앱 대신 브라우저가 열리면
  [WebView2 런타임](https://developer.microsoft.com/microsoft-edge/webview2/)을 설치하세요.

앱은 편집기를 자체 창으로 열고, 창을 닫으면 종료됩니다. 프로젝트는 `~/.shottracer/projects`
(Windows: `%USERPROFILE%\.shottracer\projects`)에 저장되어 아래 개발자 버전과 공유되며, 문제 해결용 로그는
`~/.shottracer/shottracer.log`에 남습니다. 앱에는 FFmpeg(GPL)가 별도 프로그램으로 포함되어 있습니다(앱 안
`licenses/FFMPEG-LICENSE.txt` 참고).

## 설치 (개발자 버전)

앱 대신 소스에서 실행하려면:

shottracer는 이 컴퓨터에서 실행되는 작은 Python 서버와 웹 브라우저로 동작합니다. 다음 세 가지 도구가 필요합니다.

| 도구 | 용도 | 필수 여부 |
| --- | --- | --- |
| [uv](https://docs.astral.sh/uv/) | 이 프로젝트 전용으로 Python 3.13과 라이브러리를 설치 (시스템 전체에는 설치하지 않음) | 필수 |
| [ffmpeg](https://ffmpeg.org/) | H.264로 내보내기 + **원본 소리 유지** | 권장 (없으면 소리 없이 저장됨) |
| [Git](https://git-scm.com/) | 코드 내려받기·업데이트 | 선택 (ZIP으로 받아도 됨) |

처음 `uv sync` 할 때 약 300–500 MB(Python과 라이브러리)를 내려받고, 첫 분석 때 9 MB 자세 인식 모델을 내려받습니다.
그 뒤로는 인터넷 없이 동작합니다.

### macOS

**지원:** Apple 실리콘 Mac(M1, M2, M3, M4 …). 자세 인식 라이브러리(MediaPipe)가 Intel Mac용을 제공하지 않아
**Intel Mac은 지원하지 않습니다.** 애플 메뉴 → **이 Mac에 관하여** → *칩*에 **Apple M…** 으로 나오면 됩니다.

1. **터미널 열기** (응용 프로그램 → 유틸리티 → 터미널, 또는 `⌘ Space` 후 *터미널* 입력).

2. **Homebrew 설치** (패키지 관리자, 이미 있으면 건너뛰기). 아래를 붙여넣고 안내에 따르세요(Mac 암호를 물어봅니다):
   ```bash
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
   ```
   설치가 끝나면 *Next steps* 아래에 `echo … >> ~/.zprofile`, `eval …` 같은 줄이 2~3개 나옵니다.
   그 줄들을 그대로 실행한 뒤 터미널을 닫았다 다시 여세요. `brew --version` 으로 확인합니다.

3. **uv, ffmpeg, Git 설치:**
   ```bash
   brew install uv ffmpeg git
   ```
   `uv --version`, `ffmpeg -version` 이 모두 버전을 출력하면 됩니다.

4. **shottracer 내려받기** (홈 폴더에):
   ```bash
   cd ~
   git clone https://github.com/slee1005/shottracer.git
   cd shottracer
   ```
   Git 없이: GitHub 페이지에서 **Code → Download ZIP** 을 눌러 받고 압축을 푼 뒤, 터미널에서
   `cd ~/Downloads/shottracer-main` (폴더를 둔 위치로) 이동합니다.

5. **라이브러리 설치** (처음 한 번, 몇 분 걸림):
   ```bash
   uv sync
   ```

6. **편집기 실행:**
   ```bash
   uv run shottracer-ui
   ```
   브라우저에서 <http://127.0.0.1:8765> 이 열립니다. 작업하는 동안 터미널은 열어 두고, 끝낼 때는 터미널에서
   `Ctrl+C` 를 누르세요.

**다음부터 실행:** 터미널에서 `cd ~/shottracer && uv run shottracer-ui`

**최신 버전으로 업데이트:** `cd ~/shottracer && git pull && uv sync`

### Windows

**지원:** Windows 10 또는 11, 64비트, Intel/AMD 프로세서(x64). OpenCV가 ARM용 Windows를 지원하지 않아
**ARM 기반 Windows(예: 스냅드래곤 노트북)는 지원하지 않습니다.** **설정 → 시스템 → 정보** → *시스템 종류*가
**64비트 운영 체제, x64 기반 프로세서** 이면 됩니다.

> Windows 지원은 macOS보다 새롭고 테스트가 적습니다. 문제가 있으면 오류 메시지와 함께
> [이슈](https://github.com/slee1005/shottracer/issues)로 알려 주세요.

1. **PowerShell 열기:** Windows 키를 누르고 *PowerShell* 입력 후 Enter (관리자 권한 필요 없음).

2. **uv 설치:**
   ```powershell
   powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"
   ```
   (또는 `winget install --id=astral-sh.uv -e`). 설치 후 **PowerShell을 닫았다 다시 열고** `uv --version` 으로 확인합니다.

3. **ffmpeg, Git 설치** (Windows 10/11에 기본 포함된 winget 사용):
   ```powershell
   winget install --id=Gyan.FFmpeg -e
   winget install --id=Git.Git -e
   ```
   약관 동의를 물으면 동의하세요. 새 명령을 인식하도록 **PowerShell을 다시 한 번 닫았다 열고**
   `ffmpeg -version`, `git --version` 으로 확인합니다.

4. **shottracer 내려받기** (사용자 폴더에):
   ```powershell
   cd ~
   git clone https://github.com/slee1005/shottracer.git
   cd shottracer
   ```
   Git 없이: GitHub 페이지에서 **Code → Download ZIP** 을 받고, ZIP을 오른쪽 클릭 → **압축 풀기…** 한 뒤
   `cd $HOME\Downloads\shottracer-main\shottracer-main` (`pyproject.toml` 이 있는 폴더로) 이동합니다.

5. **라이브러리 설치** (처음 한 번, 몇 분 걸림):
   ```powershell
   uv sync
   ```

6. **편집기 실행:**
   ```powershell
   uv run shottracer-ui
   ```
   브라우저에서 <http://127.0.0.1:8765> 이 열립니다(안 열리면 주소를 직접 입력). 작업하는 동안 PowerShell 창은 열어 두고,
   끝낼 때는 그 창에서 `Ctrl+C` 를 누르세요.

**다음부터 실행:** PowerShell에서 `cd ~\shottracer; uv run shottracer-ui`

**최신 버전으로 업데이트:** `cd ~\shottracer; git pull; uv sync`

### 문제 해결

| 증상 | 해결 |
| --- | --- |
| `uv`, `ffmpeg`, `git` 을 "찾을 수 없음" / "인식되지 않음" | 설치 후 터미널(PowerShell)을 닫았다 다시 여세요. macOS는 Homebrew의 *Next steps* 줄을 실행했는지 확인 |
| 내보낸 영상에 소리가 없음 | ffmpeg가 없습니다. 위 방법으로 설치하고 편집기를 재시작한 뒤 다시 렌더링 |
| `address already in use` / 8765 포트 사용 중 | 이미 실행 중인 창을 닫거나 `uv run shottracer-ui --port 9000` 으로 실행 |
| Mac에서 `uv sync` 실패 | Apple 실리콘 Mac인지 확인 (Intel Mac은 미지원) |
| Windows에서 `uv sync` 실패 | x64 PC인지 확인 (ARM은 미지원) |
| 브라우저가 열리지 않음 | <http://127.0.0.1:8765> 을 직접 여세요 |
| 업데이트 후 화면이 이상함 | `Ctrl+C` 로 끄고 다시 실행한 뒤 브라우저를 새로고침 |

## 실행

[설치](#설치-개발자-버전) 후 `shottracer` 폴더에서 `uv run shottracer-ui` 를 실행하면 브라우저에서 <http://127.0.0.1:8765> 이 열립니다.

1. **영상 추가** (여러 개 가능, 드래그&드롭 지원)
2. 타임라인을 다운스윙 전 프레임으로 옮기고 **⦿ 공 지정** → 공 클릭
   (공이 클럽 헤드에 가려졌으면 헤드를 클릭)
3. **분석** — 카메라 보정, 공 추적, 골퍼 분리, 스윙 경로를 계산 (짧은 클립 기준 수 초)
4. 미리보기에서 **착지** 핸들을 끌어 방향·휘어짐·거리를, **정점** 핸들을 위아래로 끌어 높이를 조정
5. 스타일·팝업·스윙·로고 설정 후 **이 영상 렌더링** 또는 상단 **모두 렌더링**(일괄 처리)

단축키: `←/→` 프레임 이동(`Shift`+10), `Space` 재생, `B` 공 지정, `L` 착지점, `D` 궤적 그리기, `P` 스윙 포인트, `C` 원, `S` 선, `Delete` 도형 삭제, `1`–`4` 샷 선택, `+`/`-`/`0` 확대/축소/창에 맞추기(또는 `Ctrl/⌘`+스크롤, 핀치), `Ctrl/⌘+Z` 실행 취소, `Ctrl/⌘+Shift+Z` 다시 실행, `Esc` 도구 해제.

**한 영상에 여러 샷 (최대 4개):** 도구 막대의 **+ 샷 추가**를 누르세요. 샷마다 공 위치, 분석, 그린 선, 궤적 편집,
팝업, (선택) 선 색상이 따로 있고, 스타일·스윙·슬로모션·로고는 공통입니다. 샷 탭과 팝업 탭은 선택한 샷을 편집합니다.

**궤적 직접 그리기:** **⌒ 궤적 그리기**(`D`)를 누르고 공이 출발한 곳과 떨어진 곳을 클릭한 뒤, **시작·정점·착지** 핸들을
끌어 모양을 다듬으세요. 그린 궤적이 자동 궤적을 대신하며, 분석 없이도(자동 인식이 실패해도) 렌더링됩니다. 임팩트 프레임
(지정 안 하면 그린 프레임)부터 체공 시간 동안 그려지고, 기본적으로 착지 직후 사라집니다(스타일 → *착지 후 유지*).
**↺ 프로젝트 초기화**(샷 탭 또는 목록의 ↺)는 영상만 남기고 모두 지웁니다.

**스윙 포인트:** 자동 인식된 클럽 헤드·손 경로가 어긋나면 **✦ 스윙 포인트**(`P`)를 켜고 프레임마다 클럽 헤드(또는 손)를
클릭하세요. 클릭할 때마다 다음 프레임으로 넘어갑니다(기본 2프레임, 스윙 탭에서 변경). 포인트가 2개 이상이면 그 점들을
부드럽게 잇는 곡선이 자동 경로를 대신합니다. 현재 프레임의 점은 끌어서 옮기고 `Delete`로 지웁니다.

**스윙 분석용 도형:** **◯ 원**(`C`, 중심에서 바깥으로 드래그)과 **／ 선**(`S`)으로 머리·허리 위치 등을 표시합니다. 영상 전체에
같은 자리에 표시되고 내보내기에도 들어갑니다. 도형을 클릭해 선택하고 끌어서 옮기거나 흰 점을 끌어 크기를 바꾸며, `Delete`로
지웁니다. 색상·두께는 **그리기** 탭에 있습니다.

화면 오른쪽 위에서 **English / 한국어**를 고를 수 있습니다(브라우저에 저장, 첫 실행은 브라우저 언어를 따름).
`http://127.0.0.1:8765/#lang=en` 처럼 주소로 지정할 수도 있습니다.

처음 분석할 때 MediaPipe 자세 모델(약 9 MB)을 내려받아 SHA-256을 검증한 뒤
`~/Library/Caches/shottracer/models`에 캐시합니다. 프로젝트는 `~/.shottracer/projects`에 저장됩니다
(`SHOTTRACER_HOME`으로 변경 가능).

## 기능

| 탭 | 기능 |
| --- | --- |
| 샷 | 볼 비행 / 퍼트·칩 모드, 샷 종류(체공 시간 범위), 임팩트 프레임 수동 지정, 체공 시간·높이·착지점 편집 |
| 스타일 | 프리셋(클래식·TV·네온·코멧·미니멀), 색·두께·글로우, 코멧 꼬리, **휴먼 컷아웃**(트레이서가 골퍼 뒤로), 볼 헤드, 착지 효과, 착지 후 유지 시간, 슬로모션 |
| 팝업 | 거리·캐리·볼 스피드·정점 높이·체공 시간·클럽·선수·텍스트 카드. 착지점/정점/출발점을 따라가거나 화면 모서리에 고정, 등장 지연·표시 시간·색 |
| 스윙 | 클럽 헤드 또는 손의 스윙 경로, 색·두께·그라데이션 |
| 내보내기 | 로고 워터마크(위치·크기·불투명도), 원본 해상도 렌더링(오디오 유지), 다운로드 |

거리·스피드 같은 수치는 영상만으로는 측정할 수 없으므로 직접 입력합니다(체공 시간은 측정값 자동).

**워터마크:** 미리보기와 내보낸 영상 아래쪽에 *Create by AJC_shottracer* 문구가 들어갑니다. 지우려면
(내보내기 탭 → **워터마크 지우기…**) 비밀번호가 필요하며, 지운 뒤에는 다시 켤 때까지 그 컴퓨터의 모든 영상에서 꺼져 있습니다.

## CLI

```bash
uv run shottracer swing.mp4 --ball 984,994 --ball-frame 20 [--landing 1400,600] \
    [--putt] [--style neon] [--swing clubhead] [--slowmo 3]
uv run shottracer --project <ID> -o out.mp4     # UI에서 편집한 프로젝트를 렌더링
```

## 동작 방식

1. **카메라 보정** – 배경 특징점(LK optical flow + RANSAC 유사변환)으로 패닝/흔들림을 보정해
   모든 좌표를 기준 프레임 좌표계로 맞춥니다.
2. **골퍼 분리** – MediaPipe Pose의 사람 마스크(컷아웃)와 손목 위치(스윙 경로).
3. **공 후보** – 보정된 앞/뒤 프레임과의 3-프레임 차분에서 작은 이동 물체를 찾고 골퍼·클럽 영역은 제외.
4. **임팩트 감지** – 공 위치에서 출발해 *감속하며* 멀어지는 강한 후보 3개가 처음 나타나는 프레임
   (가속하는 테이크어웨이와 구분됨).
5. **궤적 모델** – 원근을 포함한 탄도 모델
   `u = u0 + (a·s + c·t²)/(1 + w·s)`, `v = vh + (v0 − vh − h·(b·s − g·t²))/(1 + w·s)`
   (`s`: 공기저항 반영 시간, `w`: 멀어지는 속도, `vh`: 지평선, `h`: 사용자 높이 배율).
   추적이 끊긴 뒤는 이 모델로 착지까지 연장하고, 관측이 짧으면 샷 종류별 대표 체공 시간을 씁니다.
   편집(착지점·체공 시간·높이)은 임팩트 시각과 원근을 고정한 채 즉시 재피팅합니다.
6. **퍼트** – 템플릿 매칭으로 굴러가는 공을 멈출 때까지 추적.
7. **스윙 경로** – 움직임 마스크에서 골퍼 실루엣을 빼면 샤프트·헤드가 남으므로, 손에 연결된 가장 먼 점을 헤드로 추정.
   검출값은 손 기준 극좌표(클럽 길이 r, 각도 θ)로 바꿔 다듬습니다: 회전 방향을 이어가는 각도 펼치기,
   주변 변화량에 비례한 허용치로 튀는 점 제거, Savitzky-Golay 평활, 그리고 프레임 사이를 손 중심 원호로 보간
   (임팩트 직전 드라이버 헤드는 30fps에서 프레임당 100° 이상 돌기 때문). 회전이 큰 구간은 원근을 고려해 반지름을 매끄럽게 줄입니다.
8. **렌더링** – 미리보기와 최종 출력이 같은 렌더러를 사용(WYSIWYG). 슬로모션 구간은 프레임 블렌딩.

## 한계

- 공이 배경과 대비가 낮아지면 추적이 일찍 끊기고 이후는 모델 예측입니다. 착지점을 지정하면 가장 정확합니다.
- 퍼트 추적은 합성 영상으로만 검증했습니다. 실제 퍼팅 영상에서는 조정이 필요할 수 있습니다.
- 클럽 헤드는 배경과 겹치거나 모션 블러가 심하면 끊길 수 있습니다(손 경로가 더 안정적).
- 슬로모션을 쓰면 오디오가 제외됩니다.
- 분석은 공 지정 프레임 앞 1초~뒤 15초 구간만 수행합니다(긴 영상도 빠르게). 그 뒤로는 트레이서가 사라집니다.
- 2D/3D 지도 트레이서, 드론 영상, 라이브 스트리밍은 지원하지 않습니다.

## 테스트

```bash
uv run pytest
```

## 후원

shottracer가 도움이 되셨다면 후원으로 개발을 응원해 주세요:
<a href="https://www.paypal.com/donate/?business=57PYF6ECLXA6L&no_recurring=0&currency_code=USD"><img src="https://www.paypalobjects.com/en_US/i/btn/btn_donate_LG.gif" border="0" title="PayPal - The safer, easier way to pay online!" alt="Donate with PayPal button" /></a>
