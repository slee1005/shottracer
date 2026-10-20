"use strict";
// UI strings. Keys are shared; {name} placeholders are filled by t(key, vars).

const STRINGS = {
  ko: {
    "app.add": "영상 추가", "app.renderAll": "모두 렌더링", "app.drop": "여기에 동영상을 끌어다 놓으세요",
    "app.placeholder": "왼쪽에서 영상을 추가하거나 선택하세요",
    "app.prev": "이전 프레임 (←)", "app.play": "재생 (Space)", "app.next": "다음 프레임 (→)",
    "tool.ball": "⦿ 공 지정", "tool.landing": "✕ 착지점", "tool.guide": "편집 가이드",
    "tab.shot": "샷", "tab.style": "스타일", "tab.popups": "팝업", "tab.swing": "스윙", "tab.export": "내보내기",

    "toast.analysed": "분석 완료", "toast.uploading": "업로드 중: {name}", "toast.uploaded": "{name} 추가됨 — 공을 클릭하세요",
    "toast.uploadFailed": "업로드 실패: {msg}", "toast.saveFailed": "저장 실패: {msg}",
    "toast.ballSet": "공 위치 지정됨 — '분석' 버튼을 누르세요", "toast.landingFlightOnly": "착지점은 볼 비행 모드에서만 쓸 수 있습니다",
    "toast.noneReady": "분석이 끝난 영상이 없습니다", "toast.queued": "{n}개 영상 렌더링 대기열에 추가",

    "badge.analysing": "분석 {p}%", "badge.queued": "대기", "badge.rendering": "렌더 {p}%", "badge.error": "오류",
    "badge.done": "완료", "badge.ready": "준비됨", "badge.needAnalysis": "분석 필요", "badge.needBall": "공 지정 필요",
    "list.delete": "삭제", "list.confirmDelete": "'{name}' 프로젝트를 삭제할까요?",

    "handle.landing": "착지", "handle.apex": "정점 ↕",
    "hint.ball": "다운스윙 전 프레임에서 공(가려졌으면 클럽 헤드)을 클릭",
    "hint.landing": "공이 떨어지는 지점을 클릭 (가이드 핸들로 드래그도 가능)",
    "hint.edit": "핸들을 드래그해 궤적을 조정할 수 있습니다",
    "panel.selectVideo": "영상을 선택하세요.",

    "shot.group": "샷", "shot.mode": "모드", "shot.flight": "볼 비행", "shot.putt": "퍼트·칩", "shot.type": "샷 종류",
    "shot.chip": "칩 (0.8–2.5s)", "shot.pitch": "피치 (2–4.5s)", "shot.full": "풀스윙 (3.5–7.5s)", "shot.drive": "드라이브 (5–8.5s)",
    "shot.ball": "공 위치", "shot.ballAt": "프레임 {f} · ({xy})", "shot.unset": "미지정", "shot.impact": "임팩트 프레임",
    "shot.auto": "자동", "shot.analyse": "분석", "shot.reanalyse": "다시 분석", "shot.analysing": "분석 중…",
    "shot.help": "아래 '⦿ 공 지정'을 누르고 다운스윙 전 프레임에서 공을 클릭하세요. 공이 클럽 헤드에 가려졌으면 헤드를 클릭하면 됩니다.",
    "edit.group": "궤적 편집", "edit.autoTime": "체공 시간 자동", "edit.time": "체공 시간", "edit.height": "높이",
    "edit.landing": "착지점", "edit.set": "지정됨", "edit.clear": "지우기",
    "edit.help": "미리보기의 <b style=\"color:#00e0ff\">착지</b> 핸들을 끌어 방향·휘어짐·거리를, <b style=\"color:#ff9f1a\">정점</b> 핸들을 위아래로 끌어 높이를 조정하세요.",
    "stats.title": "분석 결과", "stats.impact": "임팩트 프레임 ({s}s)", "stats.tracked": "공 검출 프레임",
    "stats.flight": "체공 시간", "stats.roll": "굴러간 시간", "stats.apex": "정점 도달",

    "style.presets": "프리셋", "preset.classic": "클래식", "preset.tv": "TV", "preset.neon": "네온", "preset.comet": "코멧", "preset.minimal": "미니멀",
    "style.line": "라인", "style.color": "색상", "style.core": "코어 색상", "style.width": "두께", "style.glow": "글로우",
    "style.tailOnly": "꼬리만 표시 (코멧)", "style.tail": "꼬리 길이", "style.effects": "효과",
    "style.cutout": "휴먼 컷아웃 (골퍼 뒤로)", "style.ballHead": "볼 헤드", "style.landingFx": "착지 효과",
    "style.keep": "끝까지 유지", "style.hold": "착지 후 유지",
    "slowmo.group": "슬로모션 (내보내기에 적용)", "common.enable": "사용", "slowmo.factor": "배속",
    "slowmo.before": "임팩트 전", "slowmo.after": "임팩트 후", "slowmo.note": "슬로모션을 쓰면 오디오는 제외됩니다.",

    "popup.add": "팝업 추가", "popup.note": "거리·스피드 같은 값은 영상만으로 측정할 수 없어 직접 입력합니다. 체공 시간은 비워두면 측정값이 들어갑니다.",
    "popup.distance": "거리", "popup.carry": "캐리", "popup.speed": "볼 스피드", "popup.apex": "정점 높이",
    "popup.hangtime": "체공 시간", "popup.club": "클럽", "popup.player": "선수", "popup.text": "텍스트",
    "popup.playerName": "홍길동", "popup.textValue": "텍스트", "popup.clubValue": "7번 아이언",
    "popup.label": "라벨", "popup.value": "값", "popup.auto": "자동 (측정값)", "popup.position": "위치",
    "popup.delay": "등장 지연", "popup.keep": "계속 표시", "popup.duration": "표시 시간", "popup.bg": "배경", "popup.accent": "강조",
    "popup.delete": "삭제",
    "anchor.landing": "착지점", "anchor.apex": "정점", "anchor.start": "출발점",
    "anchor.top-left": "좌상단", "anchor.top-right": "우상단", "anchor.bottom-left": "좌하단", "anchor.bottom-right": "우하단",

    "swing.group": "스윙 트레이서", "swing.track": "추적 대상", "swing.clubhead": "클럽 헤드", "swing.hands": "손",
    "swing.gradient": "그라데이션",
    "swing.note": "분석 단계에서 골퍼 자세(MediaPipe)와 움직임으로 클럽 헤드 경로를 계산합니다. 클럽 헤드가 배경에 묻히는 구간은 손 경로가 더 안정적입니다.",

    "export.logo": "로고 / 워터마크", "export.changeLogo": "로고 변경", "export.uploadLogo": "로고 업로드",
    "export.remove": "제거", "export.size": "크기", "export.opacity": "불투명도", "export.group": "내보내기",
    "export.rendering": "렌더링 {p}%", "export.render": "이 영상 렌더링", "export.download": "⬇ 결과 다운로드",
    "export.note": "원본 해상도로 렌더링되며 오디오가 유지됩니다(슬로모션 제외). 여러 영상은 상단 '모두 렌더링'으로 한꺼번에 처리할 수 있습니다.",
    "busy.analysing": "분석 중 {p}% {msg}",

    "stage.decoding + pose": "디코딩·자세 분석", "stage.camera motion": "카메라 보정", "stage.ball": "공 추적",
    "stage.swing": "스윙 분석", "stage.done": "완료",
  },
  en: {
    "app.add": "Add videos", "app.renderAll": "Render all", "app.drop": "Drop videos here",
    "app.placeholder": "Add or select a video on the left",
    "app.prev": "Previous frame (←)", "app.play": "Play (Space)", "app.next": "Next frame (→)",
    "tool.ball": "⦿ Pick ball", "tool.landing": "✕ Landing", "tool.guide": "Edit guides",
    "tab.shot": "Shot", "tab.style": "Style", "tab.popups": "Pop-ups", "tab.swing": "Swing", "tab.export": "Export",

    "toast.analysed": "Analysis complete", "toast.uploading": "Uploading: {name}", "toast.uploaded": "{name} added — click the ball",
    "toast.uploadFailed": "Upload failed: {msg}", "toast.saveFailed": "Save failed: {msg}",
    "toast.ballSet": "Ball set — press \"Analyse\"", "toast.landingFlightOnly": "The landing spot is only available in ball-flight mode",
    "toast.noneReady": "No analysed videos yet", "toast.queued": "{n} video(s) queued for rendering",

    "badge.analysing": "Analysing {p}%", "badge.queued": "Queued", "badge.rendering": "Rendering {p}%", "badge.error": "Error",
    "badge.done": "Done", "badge.ready": "Ready", "badge.needAnalysis": "Needs analysis", "badge.needBall": "Pick the ball",
    "list.delete": "Delete", "list.confirmDelete": "Delete project '{name}'?",

    "handle.landing": "Landing", "handle.apex": "Apex ↕",
    "hint.ball": "On a frame before the downswing, click the ball (or the club head if it hides the ball)",
    "hint.landing": "Click where the ball lands (or drag the guide handle)",
    "hint.edit": "Drag the handles to adjust the trajectory",
    "panel.selectVideo": "Select a video.",

    "shot.group": "Shot", "shot.mode": "Mode", "shot.flight": "Ball flight", "shot.putt": "Putt·Chip", "shot.type": "Shot type",
    "shot.chip": "Chip (0.8–2.5s)", "shot.pitch": "Pitch (2–4.5s)", "shot.full": "Full swing (3.5–7.5s)", "shot.drive": "Drive (5–8.5s)",
    "shot.ball": "Ball", "shot.ballAt": "Frame {f} · ({xy})", "shot.unset": "Not set", "shot.impact": "Impact frame",
    "shot.auto": "Auto", "shot.analyse": "Analyse", "shot.reanalyse": "Re-analyse", "shot.analysing": "Analysing…",
    "shot.help": "Press '⦿ Pick ball' below and click the ball on a frame before the downswing. If the club head hides the ball, click the head.",
    "edit.group": "Edit trajectory", "edit.autoTime": "Auto flight time", "edit.time": "Flight time", "edit.height": "Height",
    "edit.landing": "Landing spot", "edit.set": "Set", "edit.clear": "Clear",
    "edit.help": "Drag the <b style=\"color:#00e0ff\">Landing</b> handle in the preview for direction, curve and distance, and the <b style=\"color:#ff9f1a\">Apex</b> handle up or down for height.",
    "stats.title": "Analysis", "stats.impact": "Impact frame ({s}s)", "stats.tracked": "Frames with ball",
    "stats.flight": "Flight time", "stats.roll": "Roll time", "stats.apex": "Time to apex",

    "style.presets": "Presets", "preset.classic": "Classic", "preset.tv": "TV", "preset.neon": "Neon", "preset.comet": "Comet", "preset.minimal": "Minimal",
    "style.line": "Line", "style.color": "Color", "style.core": "Core color", "style.width": "Width", "style.glow": "Glow",
    "style.tailOnly": "Tail only (comet)", "style.tail": "Tail length", "style.effects": "Effects",
    "style.cutout": "Human cut-out (behind golfer)", "style.ballHead": "Ball head", "style.landingFx": "Landing effect",
    "style.keep": "Keep until the end", "style.hold": "Hold after landing",
    "slowmo.group": "Slow motion (applied on export)", "common.enable": "Enable", "slowmo.factor": "Speed",
    "slowmo.before": "Before impact", "slowmo.after": "After impact", "slowmo.note": "Audio is dropped when slow motion is on.",

    "popup.add": "Add pop-up", "popup.note": "Values such as distance or speed cannot be measured from video alone, so type them in. Leave hang time empty to use the measured value.",
    "popup.distance": "Distance", "popup.carry": "Carry", "popup.speed": "Ball speed", "popup.apex": "Apex height",
    "popup.hangtime": "Hang time", "popup.club": "Club", "popup.player": "Player", "popup.text": "Text",
    "popup.playerName": "Player Name", "popup.textValue": "Text", "popup.clubValue": "7 Iron",
    "popup.label": "Label", "popup.value": "Value", "popup.auto": "Auto (measured)", "popup.position": "Position",
    "popup.delay": "Delay", "popup.keep": "Keep showing", "popup.duration": "Duration", "popup.bg": "Background", "popup.accent": "Accent",
    "popup.delete": "Delete",
    "anchor.landing": "Landing spot", "anchor.apex": "Apex", "anchor.start": "Start",
    "anchor.top-left": "Top left", "anchor.top-right": "Top right", "anchor.bottom-left": "Bottom left", "anchor.bottom-right": "Bottom right",

    "swing.group": "Swing tracer", "swing.track": "Track", "swing.clubhead": "Club head", "swing.hands": "Hands",
    "swing.gradient": "Gradient",
    "swing.note": "The club-head path is computed during analysis from the golfer's pose (MediaPipe) and motion. Where the head blends into the background, the hands path is more stable.",

    "export.logo": "Logo / watermark", "export.changeLogo": "Change logo", "export.uploadLogo": "Upload logo",
    "export.remove": "Remove", "export.size": "Size", "export.opacity": "Opacity", "export.group": "Export",
    "export.rendering": "Rendering {p}%", "export.render": "Render this video", "export.download": "⬇ Download result",
    "export.note": "Renders at the original resolution and keeps the audio (except with slow motion). Use 'Render all' at the top to batch several videos.",
    "busy.analysing": "Analysing {p}% {msg}",

    "stage.decoding + pose": "decoding + pose", "stage.camera motion": "camera motion", "stage.ball": "ball",
    "stage.swing": "swing", "stage.done": "done",
  },
};

// Server analysis errors are English; translate the known ones by prefix.
const SERVER_ERRORS_KO = [
  ["pick the ball first", "먼저 공을 지정하세요"],
  ["could not find the ball leaving its position", "공이 출발하는 순간을 찾지 못했습니다. 공 위치를 확인하거나 임팩트 프레임을 직접 지정하세요."],
  ["the ball never started rolling", "공이 굴러가기 시작하지 않았습니다. 공 위치나 프레임을 확인하세요."],
  ["not enough ball observations", "임팩트 후 공이 충분히 보이지 않아 궤적을 만들 수 없습니다."],
  ["the ball is too close to the frame border", "공이 화면 가장자리에 너무 가깝습니다."],
  ["ball frame must be within", "공 지정 프레임이 영상 범위를 벗어났습니다."],
  ["not enough frames after the ball frame", "공 지정 프레임 뒤에 프레임이 부족합니다."],
];

let LANG = localStorage.getItem("lang") || ((navigator.language || "").toLowerCase().startsWith("ko") ? "ko" : "en");

function t(key, vars = {}) {
  const s = STRINGS[LANG][key] ?? STRINGS.en[key] ?? key;
  return s.replace(/\{(\w+)\}/g, (_, k) => (k in vars ? vars[k] : `{${k}}`));
}

function serverText(msg) {
  if (!msg) return "";
  if (LANG === "ko") {
    for (const [prefix, ko] of SERVER_ERRORS_KO) if (msg.startsWith(prefix)) return ko;
    if (`stage.${msg}` in STRINGS.ko) return STRINGS.ko[`stage.${msg}`];
  }
  return msg;
}

function setLang(lang) {
  LANG = lang in STRINGS ? lang : "en";
  localStorage.setItem("lang", LANG);
  document.documentElement.lang = LANG;
  document.querySelectorAll("[data-i18n]").forEach((el) => (el.textContent = t(el.dataset.i18n)));
  document.querySelectorAll("[data-i18n-title]").forEach((el) => (el.title = t(el.dataset.i18nTitle)));
}
