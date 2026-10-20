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
    "hint.path": "공이 출발한 위치(시작점)를 클릭하세요", "hint.path2": "이제 공이 떨어진 위치(착지점)를 클릭하세요 — 그 뒤 핸들을 끌어 모양을 다듬습니다",
    "manual.group": "직접 그린 궤적", "manual.status": "선", "manual.none": "없음 (자동 궤적)", "manual.drawn": "그려짐 · 프레임 {f}",
    "manual.draw": "⌒ 궤적 그리기", "manual.redraw": "⌒ 다시 그리기", "manual.clear": "지우기",
    "manual.help": "시작점과 착지점을 클릭하면 궤적이 생기고, 시작·정점·착지 핸들을 끌어 모양을 다듬을 수 있습니다. 자동 분석이 실패해도 렌더링되며, 임팩트 프레임(지정 안 하면 그린 프레임)부터 체공 시간 동안 그려집니다.",
    "toast.drawn": "궤적을 만들었습니다 — 핸들을 끌어 다듬으세요", "toast.drawShort": "시작점과 착지점이 너무 가깝습니다",
    "reset.button": "↺ 프로젝트 초기화", "reset.title": "초기화",
    "reset.confirm": "'{name}' 프로젝트를 초기화할까요? 공 위치, 분석, 그린 선, 스타일, 팝업, 로고, 렌더 결과가 모두 지워집니다 (영상은 유지).",
    "reset.done": "초기화했습니다",
    "trace.name": "샷 {n}", "trace.add": "+ 샷 추가", "trace.addTitle": "한 영상에 샷 트레이서를 최대 {max}개까지 넣을 수 있습니다",
    "trace.delete": "선택한 샷 삭제", "trace.confirmDelete": "샷 {n}을(를) 삭제할까요? 이 샷의 공 위치, 분석, 선, 팝업이 지워집니다.",
    "trace.added": "샷 {n} 추가됨 — 공을 지정하거나 선을 그리세요", "trace.line": "선 색상", "trace.ownColor": "이 샷만 다른 색",
    "trace.popupsFor": "샷 {n}의 팝업입니다. 위치·시간은 이 샷 기준입니다.",
    "undo.undoTitle": "실행 취소 (Ctrl/⌘+Z)", "undo.redoTitle": "다시 실행 (Ctrl/⌘+Shift+Z)",
    "undo.undone": "실행 취소", "undo.redone": "다시 실행", "undo.nothing": "취소할 작업이 없습니다", "undo.nothingRedo": "다시 실행할 작업이 없습니다",
    "tool.path": "⌒ 궤적 그리기", "tool.circle": "◯ 원", "tool.line": "／ 선", "tab.draw": "그리기", "handle.start": "시작",
    "hint.circle": "원의 중심에서 바깥쪽으로 드래그하세요 (머리 위치 표시 등)", "hint.line": "드래그해서 선을 그리세요 (허리·척추 각도 표시 등)",
    "draw.tools": "도형 그리기", "draw.circle": "◯ 원", "draw.line": "／ 선", "draw.newColor": "새 도형 색상",
    "draw.help": "도형은 영상 전체에 같은 자리에 표시되고 내보내기에도 들어갑니다. 도형을 클릭해 선택하고 끌어서 옮기거나, 흰 점을 끌어 크기·방향을 바꾸세요. Delete 키로 삭제합니다.",
    "draw.shapes": "도형 {n}개", "draw.clearAll": "모두 지우기",
    "toast.landingPreview": "공에서 착지점까지 궤적을 만들었습니다 — 핸들로 다듬거나, 분석하면 실제 궤적으로 바뀝니다",
    "toast.landingNeedsBall": "먼저 공 위치를 지정하거나 궤적을 그리세요",
    "tool.swing": "✦ 스윙 포인트", "hint.swing": "클럽 헤드(또는 손)를 클릭하세요 — 클릭하면 다음 프레임으로 넘어갑니다. 현재 프레임의 점은 끌어서 옮기고 Delete로 지웁니다",
    "swing.points": "직접 지정한 스윙 포인트", "swing.step": "클릭 후 이동", "swing.frames": "{n}프레임",
    "swing.pointsHelp": "✦ 스윙 포인트를 켜고 프레임마다 클럽 헤드(또는 손)를 클릭하면, 그 점들을 부드럽게 잇는 선이 자동 인식 대신 그려집니다. 지금 {n}개 — 2개 이상 필요합니다.",
    "swing.pointsOn": "{n}개 포인트를 부드럽게 이은 선을 그립니다 (자동 인식 대신). 프레임 번호를 누르면 이동, ✕로 삭제합니다.",
    "wm.group": "워터마크", "wm.text": "Create by AJC_shottracer", "wm.on": "표시 중", "wm.off": "꺼짐",
    "wm.remove": "워터마크 지우기…", "wm.restore": "다시 켜기",
    "wm.noteOn": "모든 미리보기와 내보낸 영상 아래쪽에 표시됩니다. 지우려면 비밀번호가 필요합니다.",
    "wm.noteOff": "이 컴퓨터의 모든 영상에서 워터마크가 꺼져 있습니다. 다시 켤 때는 비밀번호가 필요 없습니다.",
    "wm.dialogTitle": "워터마크 지우기", "wm.dialogText": "워터마크를 지우려면 비밀번호를 입력하세요.",
    "wm.password": "비밀번호", "wm.wrong": "비밀번호가 틀렸습니다.", "wm.cancel": "취소", "wm.ok": "확인",
    "wm.removed": "워터마크를 지웠습니다",
    "zoom.in": "확대 (+, Ctrl/⌘+스크롤)", "zoom.out": "축소 (-)", "zoom.fit": "창에 맞추기 (0)", "zoom.wide": "영상 목록 숨기기 (화면 넓게)",
    "hint.edit": "핸들을 드래그해 궤적을 조정할 수 있습니다",
    "panel.selectVideo": "영상을 선택하세요.",

    "shot.group": "샷", "shot.mode": "모드", "shot.flight": "볼 비행", "shot.putt": "퍼트·칩", "shot.type": "샷 종류",
    "shot.chip": "칩 (0.8–2.5s)", "shot.pitch": "피치 (2–4.5s)", "shot.full": "풀스윙 (3.5–7.5s)", "shot.drive": "드라이브 (5–8.5s)",
    "shot.ball": "공 위치", "shot.ballAt": "프레임 {f} · ({xy})", "shot.unset": "미지정", "shot.impact": "임팩트 프레임",
    "shot.auto": "자동", "shot.analyse": "분석", "shot.reanalyse": "다시 분석", "shot.analysing": "분석 중…",
    "shot.help": "아래 '⦿ 공 지정'을 누르고 다운스윙 전 프레임에서 공을 클릭하세요. 공이 클럽 헤드에 가려졌으면 헤드를 클릭하면 됩니다.",
    "edit.group": "궤적 편집", "edit.autoTime": "체공 시간 자동", "edit.time": "체공 시간", "edit.height": "높이",
    "edit.landing": "착지점", "edit.set": "지정됨", "edit.clear": "지우기",
    "edit.fromDistance": "팝업에 입력한 거리에 맞춰 착지 시간 {s}초로 설정됨",
    "edit.distanceHint": "팝업 탭에서 거리(또는 캐리)를 입력하면 착지 시간이 그 거리에 맞춰집니다",
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
    "popup.delay": "등장 지연", "popup.hide": "사라지는 시점", "popup.hide.after_shot": "샷 끝나고 1초 후", "popup.hide.end": "영상 끝까지", "popup.hide.time": "표시 시간 지정", "popup.duration": "표시 시간", "popup.bg": "배경", "popup.accent": "강조",
    "popup.delete": "삭제",
    "anchor.landing": "착지점", "anchor.apex": "정점", "anchor.start": "출발점",
    "anchor.top-left": "좌상단", "anchor.top-right": "우상단", "anchor.bottom-left": "좌하단", "anchor.bottom-right": "우하단",

    "swing.group": "스윙 트레이서", "swing.track": "추적 대상", "swing.clubhead": "클럽 헤드", "swing.hands": "손",
    "swing.gradient": "그라데이션",
    "swing.note": "분석 단계에서 골퍼 자세(MediaPipe)와 움직임으로 클럽 헤드 경로를 계산합니다. 클럽 헤드가 배경에 묻히는 구간은 손 경로가 더 안정적입니다.",

    "export.logo": "로고", "export.changeLogo": "로고 변경", "export.uploadLogo": "로고 업로드",
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
    "hint.path": "Click where the ball starts", "hint.path2": "Now click where the ball lands — then drag the handles to shape the curve",
    "manual.group": "Hand-drawn path", "manual.status": "Line", "manual.none": "None (automatic path)", "manual.drawn": "Drawn · frame {f}",
    "manual.draw": "⌒ Draw path", "manual.redraw": "⌒ Redraw", "manual.clear": "Clear",
    "manual.help": "Click the start and the landing spot, then drag the start, apex and landing handles to shape the curve. It renders even when automatic analysis fails, from the impact frame (or the frame you drew on) over the flight time.",
    "toast.drawn": "Path created — drag the handles to shape it", "toast.drawShort": "Start and landing are too close",
    "reset.button": "↺ Reset project", "reset.title": "Reset",
    "reset.confirm": "Reset project '{name}'? Ball, analysis, drawn line, style, pop-ups, logo and rendered output are cleared (the video is kept).",
    "reset.done": "Project reset",
    "trace.name": "Shot {n}", "trace.add": "+ Add shot", "trace.addTitle": "Up to {max} shot tracers per video",
    "trace.delete": "Delete the selected shot", "trace.confirmDelete": "Delete shot {n}? Its ball, analysis, line and pop-ups are removed.",
    "trace.added": "Shot {n} added — pick the ball or draw a line", "trace.line": "Line color", "trace.ownColor": "Own color for this shot",
    "trace.popupsFor": "Pop-ups of shot {n}; positions and timing follow this shot.",
    "undo.undoTitle": "Undo (Ctrl/⌘+Z)", "undo.redoTitle": "Redo (Ctrl/⌘+Shift+Z)",
    "undo.undone": "Undone", "undo.redone": "Redone", "undo.nothing": "Nothing to undo", "undo.nothingRedo": "Nothing to redo",
    "tool.path": "⌒ Draw path", "tool.circle": "◯ Circle", "tool.line": "／ Line", "tab.draw": "Draw", "handle.start": "Start",
    "hint.circle": "Drag from the center outward (e.g. to mark the head)", "hint.line": "Drag to draw a line (e.g. hip or spine angle)",
    "draw.tools": "Shapes", "draw.circle": "◯ Circle", "draw.line": "／ Line", "draw.newColor": "New shape color",
    "draw.help": "Shapes stay in the same place for the whole video and are included in the export. Click a shape to select it, drag to move it, or drag its white dots to resize. Delete removes it.",
    "draw.shapes": "{n} shapes", "draw.clearAll": "Clear all",
    "toast.landingPreview": "Path created from the ball to the landing spot — shape it with the handles, or analyse to replace it with the tracked flight",
    "toast.landingNeedsBall": "Pick the ball or draw a path first",
    "tool.swing": "✦ Swing points", "hint.swing": "Click the club head (or hands) — each click steps to the next frame. Drag the current frame's point to move it, Delete removes it",
    "swing.points": "Your swing points", "swing.step": "Step after click", "swing.frames": "{n} frames",
    "swing.pointsHelp": "Turn on ✦ Swing points and click the club head (or hands) frame by frame: a smooth line through your points replaces the detected path. {n} so far — at least 2 are needed.",
    "swing.pointsOn": "Drawing a smooth line through your {n} points (instead of the detected path). Click a frame number to go there, ✕ to delete.",
    "wm.group": "Watermark", "wm.text": "Create by AJC_shottracer", "wm.on": "On", "wm.off": "Off",
    "wm.remove": "Remove watermark…", "wm.restore": "Turn back on",
    "wm.noteOn": "Shown at the bottom of every preview and exported video. Removing it needs the password.",
    "wm.noteOff": "The watermark is off for all videos on this computer. Turning it back on needs no password.",
    "wm.dialogTitle": "Remove watermark", "wm.dialogText": "Enter the password to remove the watermark.",
    "wm.password": "Password", "wm.wrong": "Wrong password.", "wm.cancel": "Cancel", "wm.ok": "OK",
    "wm.removed": "Watermark removed",
    "zoom.in": "Zoom in (+, Ctrl/⌘+scroll)", "zoom.out": "Zoom out (-)", "zoom.fit": "Fit to window (0)", "zoom.wide": "Hide the video list (wider view)",
    "hint.edit": "Drag the handles to adjust the trajectory",
    "panel.selectVideo": "Select a video.",

    "shot.group": "Shot", "shot.mode": "Mode", "shot.flight": "Ball flight", "shot.putt": "Putt·Chip", "shot.type": "Shot type",
    "shot.chip": "Chip (0.8–2.5s)", "shot.pitch": "Pitch (2–4.5s)", "shot.full": "Full swing (3.5–7.5s)", "shot.drive": "Drive (5–8.5s)",
    "shot.ball": "Ball", "shot.ballAt": "Frame {f} · ({xy})", "shot.unset": "Not set", "shot.impact": "Impact frame",
    "shot.auto": "Auto", "shot.analyse": "Analyse", "shot.reanalyse": "Re-analyse", "shot.analysing": "Analysing…",
    "shot.help": "Press '⦿ Pick ball' below and click the ball on a frame before the downswing. If the club head hides the ball, click the head.",
    "edit.group": "Edit trajectory", "edit.autoTime": "Auto flight time", "edit.time": "Flight time", "edit.height": "Height",
    "edit.landing": "Landing spot", "edit.set": "Set", "edit.clear": "Clear",
    "edit.fromDistance": "Landing time set to {s}s from the distance typed in the pop-up",
    "edit.distanceHint": "Type a distance (or carry) in the Pop-ups tab and the landing time follows it",
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
    "popup.delay": "Delay", "popup.hide": "Hide", "popup.hide.after_shot": "1 s after the shot ends", "popup.hide.end": "At the end of the video", "popup.hide.time": "After a set time", "popup.duration": "Duration", "popup.bg": "Background", "popup.accent": "Accent",
    "popup.delete": "Delete",
    "anchor.landing": "Landing spot", "anchor.apex": "Apex", "anchor.start": "Start",
    "anchor.top-left": "Top left", "anchor.top-right": "Top right", "anchor.bottom-left": "Bottom left", "anchor.bottom-right": "Bottom right",

    "swing.group": "Swing tracer", "swing.track": "Track", "swing.clubhead": "Club head", "swing.hands": "Hands",
    "swing.gradient": "Gradient",
    "swing.note": "The club-head path is computed during analysis from the golfer's pose (MediaPipe) and motion. Where the head blends into the background, the hands path is more stable.",

    "export.logo": "Logo", "export.changeLogo": "Change logo", "export.uploadLogo": "Upload logo",
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
  document.querySelectorAll("[data-i18n-placeholder]").forEach((el) => (el.placeholder = t(el.dataset.i18nPlaceholder)));
}
