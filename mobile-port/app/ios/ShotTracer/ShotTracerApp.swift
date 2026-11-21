import AVKit
import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

@main struct ShotTracerApp: App { var body: some Scene { WindowGroup { EditorView() } } }

struct EditorView: View {
    @StateObject private var model = EditorModel()
    @State private var showingCamera = false
    @State private var settings = false
    @State private var reviewing = false
    @State private var infoKey: String?
    @State private var editingInfo = false
    @State private var infoDraft = ShotInfo()
    @State private var infoUnit = "m"
    @State private var fullscreen = false
    @State private var showingProjects = false
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var zoom = 1.0
    @State private var lastZoom = 1.0
    @State private var pan = CGSize.zero
    @State private var lastPan = CGSize.zero
    @State private var dragHandle: String?
    @State private var dragPanning = false
    @State private var pinchOriginPan = CGSize.zero
    @State private var dragging = false
    @State private var pinching = false
    @State private var transformedGesture = false
    @State private var resetConfirm = false
    @State private var draggingTime: String?
    @State private var markerOrigin: Double?
    @State private var panOrigin: Double?
    @Environment(\.horizontalSizeClass) private var timelineSize
    @Environment(\.verticalSizeClass) private var verticalSize
    @Environment(\.scenePhase) private var editorPhase
    @Environment(\.dynamicTypeSize) private var typeSize
    private var scrollsWorkspace: Bool { typeSize.isAccessibilitySize || verticalSize == .compact }
    private func icon(_ key: String, _ symbol: String, disabled: Bool = false, selected: Bool = false,
                      primary: Bool = false, allowsBusy: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 21, weight: .medium)).frame(width: 44, height: 44)
                .foregroundStyle(primary ? Color.white : selected ? Color.blue : Color.primary)
                .background(primary ? Color.blue : selected ? Color.blue.opacity(0.12) : Color.clear,
                            in: RoundedRectangle(cornerRadius: 14))
        }.buttonStyle(.plain).contentShape(Rectangle())
            .accessibilityIdentifier(key)
            .accessibilityLabel(Text(LocalizedStringKey(key))).help(Text(LocalizedStringKey(key)))
            .accessibilityAddTraits(selected ? .isSelected : [])
            .disabled(disabled || model.busy && !allowsBusy || model.editingTimeline)
    }
    var body: some View {
        NavigationStack {
            Group {
                if scrollsWorkspace {
                    ScrollView { workspace }
                } else { workspace }
            }
            .padding(.horizontal, 12).background(Color(uiColor: .systemGroupedBackground)).tint(.blue)
            .toolbar(.hidden, for: .navigationBar)
            .fullScreenCover(isPresented: $showingCamera) { LiveCameraView() }
            .fullScreenCover(isPresented: $reviewing) { if let url = model.output { ReviewView(url: url) } }
            .sheet(isPresented: $editingInfo) { shotInfoEditor }
            .sheet(isPresented: $settings) { EditorSettings(model: model).onAppear { model.checkpoint() }.onDisappear { model.commitEdits() } }
            .sheet(isPresented: $showingProjects) { ProjectList(model: model) }
            .confirmationDialog("editor.resetConfirm", isPresented: $resetConfirm) { Button("editor.resetAction", role: .destructive) { model.reset() } }
            .onChange(of: selectedPhoto) { _, item in guard let item else { return }; model.importPhoto(item); selectedPhoto = nil }
            .onDisappear { model.pause(); model.cancelAnalysis(); model.endTimeEdit(cancel:true); draggingTime=nil; markerOrigin=nil; panOrigin=nil }
            .onChange(of:editorPhase) { _,phase in if phase != .active { model.pause(); model.cancelAnalysis(); model.endTimeEdit(cancel:true); draggingTime=nil; markerOrigin=nil; panOrigin=nil } }
        }
    }
    private var workspace: some View {
        VStack(spacing: 8) {
            if !fullscreen { header; guidance }
            ZStack(alignment: .bottom) {
                preview.background(.black).clipShape(RoundedRectangle(cornerRadius: 18))
                if model.isAnalyzing { analysisIndicator.padding(12) }
            }.frame(minHeight: 120, idealHeight: scrollsWorkspace ? 300 : nil,
                    maxHeight: scrollsWorkspace ? 300 : .infinity)
            if model.hasVideo {
                if model.scene.shot.analysisFailed == true {
                    Label("editor.manualNotice", systemImage: "exclamationmark.circle")
                        .font(.caption).foregroundStyle(.orange).frame(maxWidth: .infinity, alignment: .leading)
                }
                filmstrip.frame(height: typeSize.isAccessibilitySize ? 132 : 98)
                HStack(spacing: 4) {
                    if fullscreen { icon("editor.fullscreen", "arrow.down.right.and.arrow.up.left") { fullscreen=false } }
                    Spacer(minLength: 0); transport; Spacer(minLength: 0)
                }
                if !fullscreen {
                    workflow
                    if model.scene.shots.count > 1 { shotBar }
                    shotInfoTabs
                }
            } else {
                PhotosPicker(selection: $selectedPhoto, matching: .videos, preferredItemEncoding: .current) {
                    Image(systemName: "photo.on.rectangle.angled").font(.title2).frame(width: 52, height: 48)
                }.buttonStyle(.borderedProminent).accessibilityLabel(Text("action.photos")).disabled(model.busy)
            }
            if !model.isAnalyzing && (model.hasVideo || model.busy || model.status != NSLocalizedString("hint.import", comment:"")) {
                HStack {
                    if model.busy { ProgressView().controlSize(.small) }
                    Text(model.status).font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("editor.status")
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
        }.padding(.bottom, 6)
    }
    private var header: some View {
        HStack(spacing: 4) {
            icon("editor.projects", "square.stack") { model.reloadProjects(); showingProjects = true }
            Text("app.title").font(.headline).lineLimit(1).minimumScaleFactor(0.6)
            Spacer(minLength: 0)
            icon("editor.settings", "slider.horizontal.3", disabled: !model.hasVideo) { settings = true }
            Menu {
                PhotosPicker(selection: $selectedPhoto, matching: .videos, preferredItemEncoding: .current) { Label("action.photos", systemImage: "photo.on.rectangle.angled") }
                Button { model.pause(); showingCamera=true } label: { Label("camera.open", systemImage:"camera.fill") }
                Button { fullscreen=true } label: { Label("editor.fullscreen", systemImage:"arrow.up.left.and.arrow.down.right") }.disabled(!model.hasVideo)
                Button { model.undoEdit() } label: { Label("editor.undo", systemImage:"arrow.uturn.backward") }.disabled(!model.canUndo)
                Button { model.redoEdit() } label: { Label("editor.redo", systemImage:"arrow.uturn.forward") }.disabled(!model.canRedo)
                Button { model.addShot() } label: { Label("editor.add", systemImage:"plus.circle") }.disabled(!model.hasVideo || model.scene.shots.count>=4)
                if model.output != nil {
                    Button { reviewing=true } label: { Label("editor.review", systemImage:"play.rectangle") }
                    Button { model.saveToAlbum() } label: { Label("editor.albumSave", systemImage:"square.and.arrow.down") }
                }
                Button(role:.destructive) { resetConfirm=true } label: { Label("editor.resetAction", systemImage:"arrow.counterclockwise") }.disabled(!model.hasVideo)
            } label: { Image(systemName:"ellipsis.circle").font(.system(size:21)).frame(width:44,height:44) }
                .accessibilityLabel(Text("editor.more")).disabled(model.busy || model.editingTimeline)
        }
    }
    private var guidance: some View {
        VStack(alignment: .leading, spacing: 5) {
            Label(LocalizedStringKey(model.isAnalyzing ? "editor.guide.analysisStage" : model.needsManualTrace ? "editor.guide.landingStage" : model.scene.shot.hasTrace ? "editor.guide.previewStage" : "editor.guide.impactStage"),
                  systemImage: model.isAnalyzing ? "viewfinder" : model.needsManualTrace ? "flag" : model.scene.shot.hasTrace ? "point.topleft.down.to.point.bottomright.curvepath" : "scope")
                .font(.caption).foregroundStyle(.secondary)
            Text(LocalizedStringKey(model.guidanceTitle)).font(.headline).accessibilityIdentifier("editor.guidance.title")
            Text(LocalizedStringKey(model.guidanceMessage)).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal:false,vertical:true)
        }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 4)
    }
    private var analysisIndicator: some View {
        HStack(spacing: 10) {
            ProgressView()
            VStack(alignment: .leading, spacing: 4) {
                Text(LocalizedStringKey(model.guidanceTitle)).font(.caption)
                if model.analysisPhase != .checking { ProgressView(value:model.analysisProgress) }
            }
            icon("editor.cancelAnalysis", "xmark", allowsBusy:true) { model.cancelAnalysis() }
        }.padding(10).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
    }
    private var shotBar: some View {
        HStack {
            ForEach(model.scene.shots.indices, id: \.self) { index in
                Button { model.selectShot(index) } label: {
                    Image(systemName:"\(index+1).circle\(index == model.scene.selected ? ".fill" : "")").font(.title2).frame(width:44,height:44)
                }.accessibilityLabel(String(format:NSLocalizedString("editor.shotNumber",comment:""),index+1))
                    .accessibilityAddTraits(index == model.scene.selected ? .isSelected : [])
            }
            icon("editor.add", "plus.circle.fill", disabled: !model.hasVideo || model.scene.shots.count >= 4) { model.addShot() }
            Spacer(minLength: 0)
        }.disabled(model.busy || model.editingTimeline)
    }
    private var workflow: some View {
        HStack {
            icon("editor.ball", "scope", selected:model.tool == "ball") { model.pause(); model.chooseTool("ball") }
            Spacer()
            icon("editor.landing", "flag.fill", disabled:model.scene.shot.ball == nil, selected:model.tool == "landing") { model.selectLandingTool() }
            Spacer()
            if model.isAnalyzing {
                icon("editor.cancelAnalysis", "xmark", primary:true, allowsBusy:true) { model.cancelAnalysis() }
            } else if model.needsManualTrace {
                icon("editor.confirmManual", "checkmark", disabled:!model.scene.shot.readyForManual, primary:true) { model.confirmManualTrace() }
            } else if model.scene.shot.hasTrace && model.tool != "ball" {
                icon("action.export", "square.and.arrow.up", primary:true) { model.export() }
            } else {
                icon("editor.analyzeAction", "viewfinder", disabled:!model.scene.shot.readyToAnalyze, primary:true) { model.analyze() }
            }
        }.padding(.horizontal, 14).padding(.vertical, 4)
    }
    private func viewportPan(_ value: CGSize, _ fitted: CGSize) -> CGSize {
        let x=fitted.width*max(0,zoom-1)/2, y=fitted.height*max(0,zoom-1)/2
        return CGSize(width:min(x,max(-x,value.width)),height:min(y,max(-y,value.height)))
    }
    private func videoPoint(_ location: CGPoint, _ viewport: CGSize, _ fitted: CGSize) -> EditPoint {
        EditPoint((location.x-(viewport.width-fitted.width*zoom)/2-pan.width)/(fitted.width*zoom),
                  (location.y-(viewport.height-fitted.height*zoom)/2-pan.height)/(fitted.height*zoom))
    }
    @ViewBuilder private var preview: some View {
        if let image = model.image {
            GeometryReader { area in
                let size = image.size
                let fit = min(area.size.width / size.width, area.size.height / size.height)
                let width = size.width * fit, height = size.height * fit
                let fitted=CGSize(width:width,height:height)
                ZStack {
                    ZStack(alignment: .topLeading) {
                        Image(uiImage: image).resizable().accessibilityIdentifier("editor.sourceFrame")
                        if let overlay = model.overlay { Image(uiImage: overlay).resizable() }
                        ForEach(model.handles, id: \.0) { key, point in
                            if !model.playing && !model.busy {
                                Image(systemName: key == "ball" ? "scope" : key == "landing" ? "flag.fill" : "circle.dotted")
                                    .font(.system(size:25/zoom,weight:.medium)).foregroundStyle(.white)
                                    .frame(width:44/zoom,height:44/zoom)
                                    .background(.black.opacity(0.55),in:Circle())
                                    .overlay(Circle().stroke(model.tool == key ? Color.blue : Color.clear,lineWidth:2/zoom))
                                    .position(x: point.x * width, y: point.y * height)
                                    .accessibilityLabel(Text(LocalizedStringKey(key == "ball" ? "editor.ball" : key == "landing" ? "editor.landing" : "editor.apex")))
                                    .accessibilityHint(Text("editor.handleHint")).accessibilityIdentifier("editor.handle."+key)
                            }
                        }
                    }
                    .frame(width: width, height: height).accessibilityElement(children:.contain).accessibilityIdentifier("editor.videoFrame")
                    .scaleEffect(zoom).offset(pan)
                }.frame(width: area.size.width, height: area.size.height).contentShape(Rectangle())
                    .coordinateSpace(name:"videoViewport")
                    .gesture(DragGesture(minimumDistance:0,coordinateSpace:.named("videoViewport")).onChanged { value in
                        guard !model.busy, !pinching, !transformedGesture else { return }
                        let point=videoPoint(value.location,area.size,fitted)
                        if !dragging {
                            dragging=true; dragPanning=false; lastPan=pan
                            let start=videoPoint(value.startLocation,area.size,fitted)
                            let placingPoint = model.tool == "ball" && !model.scene.shot.hasTrace || model.tool == "landing" && model.scene.shot.landingConfirmed != true
                            dragHandle = placingPoint ? nil : model.handles.min {
                                hypot(($0.1.x-start.x)*width,($0.1.y-start.y)*height) < hypot(($1.1.x-start.x)*width,($1.1.y-start.y)*height)
                            }.flatMap { key,p in hypot((p.x-start.x)*width*zoom,(p.y-start.y)*height*zoom)<25 ? key : nil }
                            if dragHandle != nil { model.checkpoint() }
                        }
                        if let dragHandle {
                            model.moveHandle(dragHandle,EditPoint(min(1,max(0,point.x)),min(1,max(0,point.y))))
                        } else if zoom > 1 && hypot(value.translation.width,value.translation.height)>6 {
                            dragPanning=true
                            pan=viewportPan(CGSize(width:lastPan.width+value.translation.width,height:lastPan.height+value.translation.height),fitted)
                        }
                    }.onEnded { value in
                        defer { dragging=false; dragHandle=nil; transformedGesture=false; dragPanning=false; lastPan=pan }
                        guard !model.busy, !pinching, !transformedGesture else { return }
                        if dragHandle != nil { model.persist(); return }
                        if dragPanning || hypot(value.translation.width,value.translation.height)>6 { return }
                        let point=videoPoint(value.location,area.size,fitted)
                        if point.x>=0 && point.x<=1 && point.y>=0 && point.y<=1 { model.tap(point) }
                    })
                    .simultaneousGesture(MagnifyGesture().onChanged { value in
                        if !pinching { pinchOriginPan=pan; if dragHandle != nil { model.persist(); dragHandle=nil } }
                        pinching=true; transformedGesture=true
                        let next=min(6,max(1,lastZoom*value.magnification)), ratio=next/lastZoom
                        let fx=(value.startAnchor.x-0.5)*area.size.width, fy=(value.startAnchor.y-0.5)*area.size.height
                        zoom=next
                        pan=viewportPan(CGSize(width:fx-(fx-pinchOriginPan.width)*ratio,height:fy-(fy-pinchOriginPan.height)*ratio),fitted)
                    }.onEnded { _ in lastZoom=zoom; lastPan=pan; pinching=false })
                    .onTapGesture(count:2) { zoom=1; lastZoom=1; pan = .zero; lastPan = .zero }
                    .onChange(of:fitted) { _,size in pan=viewportPan(pan,size); lastPan=pan }

            }.accessibilityLabel(Text("preview.label"))
        } else { Image("Brand").resizable().scaledToFit().padding(40).frame(maxWidth: .infinity, maxHeight: .infinity).accessibilityLabel(Text("app.title")) }
    }
    @ViewBuilder private var transport: some View {
        icon("editor.previous", "backward.frame.fill", disabled: !model.hasVideo) { model.step(-1) }
        icon("editor.play", model.playing ? "pause.fill" : "play.fill", disabled: !model.hasVideo) { if model.playing { model.pause() } else { model.play() } }
        icon("editor.next", "forward.frame.fill", disabled: !model.hasVideo) { model.step(1) }
        if fullscreen { icon("action.export", "square.and.arrow.up", disabled:!model.canExport) { model.export() } }
    }
    private var filmstrip: some View {
        let tile: CGFloat = timelineSize == .regular ? 108 : 72
        let interval = model.snapshotInterval
        return GeometryReader { area in
            let center = area.size.width / 2
            let clock = model.time
            let events = model.timelineEvents
            let px = tile / interval
            let laneHeight = min(66, area.size.height * 0.56)
            let lane = area.size.height - laneHeight
            let ruler = min(18, area.size.height * 0.18)
            let snapshot = lane - ruler
            let labelSize: CGFloat = 11
            ZStack(alignment: .topLeading) {
                Rectangle().fill(Color(uiColor: .secondarySystemGroupedBackground))
                    .contentShape(Rectangle())
                    .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .named("shotFilmstrip"))
                        .onChanged { value in
                            guard !model.busy, draggingTime == nil, abs(value.translation.width) > 3 else { return }
                            if panOrigin == nil { panOrigin = clock; model.pause() }
                            let frame = nearestSourceFrame(model.times, (panOrigin ?? clock) - value.translation.width / px)
                            if frame != model.frameIndex { model.seek(frame) }
                        }.onEnded { value in
                            if panOrigin == nil, !model.busy, draggingTime == nil {
                                model.pause()
                                model.seek(nearestSourceFrame(model.times, clock + (value.location.x - center) / px))
                            }
                            panOrigin = nil
                        })
                    .accessibilityLabel(Text("editor.timelineSeek"))
                    .accessibilityIdentifier("editor.timeline.seek")
                    .accessibilityValue(filmClock(clock))
                    .accessibilityAdjustableAction { direction in
                        if !model.busy { model.step(direction == .increment ? 1 : -1) }
                    }
                ForEach(model.snapshotFrames, id: \.self) { index in
                    if model.times.indices.contains(index) {
                        let time = model.times[index]
                        let x = center + (time - clock) * px
                        if abs(x - center) <= center + tile {
                            VStack(alignment: .leading, spacing: 0) {
                                Text(filmClock(time)).font(.system(size: labelSize)).monospacedDigit().padding(.leading, 3)
                                    .frame(height: ruler)
                                ZStack {
                                    Color.primary.opacity(0.08)
                                    if let image = model.thumbnails[index] {
                                        Image(uiImage: image).resizable().scaledToFit()
                                    }
                                }.frame(width: tile - 1, height: snapshot).clipped()
                            }.frame(width: tile).position(x: x, y: lane / 2)
                                .allowsHitTesting(false).accessibilityHidden(true)
                        }
                    }
                }
                Rectangle().fill(Color.primary.opacity(0.04)).frame(height: laneHeight).offset(y: lane).allowsHitTesting(false)
                if let first = events.first, let last = events.last, events.count > 1 {
                    let left = max(0, center + (first.time - clock) * px)
                    let right = min(area.size.width, center + (last.time - clock) * px)
                    if right > left {
                        Capsule().fill(.yellow.opacity(0.65)).frame(width: right - left, height: 3)
                            .offset(x: left, y: lane + 2).allowsHitTesting(false)
                    }
                }
                if laneHeight >= 58 {
                    Text("editor.timelineTracer").font(.system(size: 10)).foregroundStyle(.secondary)
                        .offset(x: 5, y: area.size.height - 13).allowsHitTesting(false)
                }
                ForEach(events) { event in
                    if let firstTime = model.times.first, let lastTime = model.times.last {
                        let row = event.key == "ball" ? 0 : event.key == "apex" ? 1 : 2
                        let edgeTime = min(lastTime, max(firstTime, event.time))
                        let x = center + (edgeTime - clock) * px
                        Text("▴ " + eventLabel(event, compact: true)).font(.system(size: labelSize, weight: .medium)).monospacedDigit()
                            .padding(.horizontal, 4).background(.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 3))
                            .foregroundStyle(eventColor(event.key)).opacity(event.inClip ? 1 : 0.55).fixedSize().contentShape(Rectangle())
                            .position(x: x, y: lane + laneHeight * (0.20 + CGFloat(row) * 0.25))
                            .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .named("shotFilmstrip")).onChanged { value in
                                guard !model.busy, panOrigin == nil else { return }
                                if draggingTime == nil && abs(value.translation.width) > 3 {
                                    draggingTime = event.key; markerOrigin = edgeTime; model.beginTimeEdit()
                                }
                                if draggingTime == event.key, let origin = markerOrigin {
                                    model.dragTime(event.key, nearestSourceFrame(model.times, origin + value.translation.width / px))
                                }
                            }.onEnded { _ in
                                if draggingTime == event.key { model.endTimeEdit(); draggingTime = nil; markerOrigin = nil }
                                else { jump(event) }
                            })
                            .accessibilityLabel(eventLabel(event)).accessibilityHint(Text("editor.timelineDrag"))
                            .accessibilityAdjustableAction { direction in
                                guard !model.busy else { return }
                                model.beginTimeEdit()
                                model.dragTime(event.key, event.frame + (direction == .increment ? 1 : -1))
                                model.endTimeEdit()
                            }
                    }
                }
                Rectangle().fill(Color.primary).frame(width: 2, height: area.size.height)
                    .position(x: center, y: area.size.height / 2).allowsHitTesting(false).accessibilityHidden(true)
            }.coordinateSpace(name: "shotFilmstrip").clipped()
        }
    }
    private func filmClock(_ time: Double) -> String {
        let cs = max(0, Int((time * 100).rounded()))
        return String(format: "%02d:%02d.%02d", cs / 6000, cs / 100 % 60, cs % 100)
    }
    private func eventColor(_ key: String) -> Color {
        switch key {
        case "ball": .yellow
        case "apex": .cyan
        default: .green
        }
    }
    private func jump(_ event: ShotEvent) {
        zoom=1; lastZoom=1; pan = .zero; lastPan = .zero
        model.jumpToEvent(event)
    }
    private func eventLabel(_ event: ShotEvent, compact: Bool = false) -> String {
        let key = event.key == "ball" ? "editor.timelineStart" : event.key == "apex" ? "editor.timelineApex"
            : event.estimated ? "editor.timelineEstimated" : "editor.timelineLanding"
        let name = NSLocalizedString(key, comment: "")
        return (compact || event.inClip) ? name + " " + filmClock(event.time)
            : String(format: NSLocalizedString("editor.timelineOutside", comment: ""), name, event.time)
    }
    private func editInfo(_ key: String) {
        model.pause(); infoKey=key
        infoDraft=model.scene.shot.shotInfo?[key] ?? ShotInfo()
        if key != "height" {
            infoUnit=ShotMeasurement(infoDraft.value)?.unit == "yds" ? "yds" : "m"
            infoDraft.value=distanceInput(infoDraft.value,unit:infoUnit)
        }
        editingInfo=true
    }
    private var shotInfoTabs: some View {
        let total = model.scene.shot.shotInfo?["total"]?.value ?? ""
        return HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text("editor.info.total").font(.caption).foregroundStyle(.secondary)
                Text(total.isEmpty ? NSLocalizedString("editor.distancePrompt",comment:"") : total)
                    .font(.caption).lineLimit(2).fixedSize(horizontal:false,vertical:true)
            }.frame(maxWidth:.infinity,alignment:.leading)
            ForEach(shotInfoKeys, id: \.self) { key in
                icon("editor.info."+key,key == "total" ? "ruler" : key == "carry" ? "arrow.up.forward" : "arrow.up.to.line",
                     selected:model.scene.shot.shotInfo?[key]?.visible == true) { editInfo(key) }
            }
        }
    }

    private var shotInfoEditor: some View {
        NavigationStack {
            Form {
                if infoKey == "height" {
                    TextField("editor.info.value", text: $infoDraft.value)
                } else {
                    TextField("editor.info.amount", text: $infoDraft.value).keyboardType(.decimalPad)
                    Picker("editor.info.unit", selection: Binding(get: { infoUnit }, set: { next in
                        let amount = infoDraft.value.trimmingCharacters(in: .whitespacesAndNewlines)
                        if !amount.isEmpty { infoDraft.value = distanceInput(amount + " " + infoUnit, unit: next) }
                        infoUnit = next
                    })) {
                        Text(verbatim: "yds").tag("yds")
                        Text(verbatim: "m").tag("m")
                    }.pickerStyle(.segmented)
                }
                Toggle("editor.info.visible", isOn: $infoDraft.visible)
                if infoKey == "total" { Text("editor.distanceExplanation").font(.footnote).foregroundStyle(.secondary) }
            }
            .navigationTitle(LocalizedStringKey("editor.info." + (infoKey ?? "total")))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { editingInfo=false } label: { Image(systemName:"xmark").frame(width:44,height:44) }.accessibilityLabel(Text("editor.info.cancel"))
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        if let infoKey {
                            model.checkpoint()
                            let amount = String(infoDraft.value.trimmingCharacters(in: .whitespacesAndNewlines).prefix(24))
                            infoDraft.value = infoKey != "height" && !amount.isEmpty ? amount + " " + infoUnit : amount
                            var info = model.scene.shot.shotInfo ?? [:]
                            info[infoKey] = infoDraft
                            model.scene.shot.shotInfo = info
                            model.commitEdits()
                        }
                        editingInfo = false
                    } label: { Image(systemName:"checkmark").frame(width:44,height:44) }.accessibilityLabel(Text("editor.info.done")).accessibilityIdentifier("editor.info.done")
                }
            }
        }.presentationDetents([.medium])
    }

}

struct ReviewView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var player: AVPlayer
    let url: URL
    init(url: URL) { self.url=url; _player = State(initialValue: AVPlayer(url: url)) }
    var body: some View {
        NavigationStack { VideoPlayer(player: player).background(.black).navigationTitle(Text("editor.review"))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("camera.close") { dismiss() } }; ToolbarItem(placement:.primaryAction) { ShareLink(item:url) { Image(systemName:"square.and.arrow.up") }.accessibilityLabel(Text("action.share")) } }
        }.onAppear { player.defaultRate = 1; player.playImmediately(atRate: 1) }.onDisappear { player.pause() }
    }
}

struct ProjectList: View {
    @ObservedObject var model: EditorModel
    @Environment(\.dismiss) private var dismiss
    @State private var selected = Set<URL>()
    @State private var deletion: [URL] = []
    @State private var confirming = false
    var body: some View {
        NavigationStack {
            List {
                ForEach(model.projects, id: \.path) { file in
                    HStack {
                        Button {
                            if selected.contains(file) { selected.remove(file) } else { selected.insert(file) }
                        } label: { Image(systemName: selected.contains(file) ? "checkmark.circle.fill" : "circle") }
                            .accessibilityLabel(Text("editor.selectProject")).buttonStyle(.borderless)
                        Button(file.deletingLastPathComponent().lastPathComponent.prefix(8).description) { dismiss(); model.openProject(file) }.buttonStyle(.borderless)
                    }.swipeActions { Button("editor.deleteProjects", role: .destructive) { deletion = [file]; confirming = true } }
                }
            }
            .navigationTitle(Text("editor.projects")).disabled(model.busy)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("editor.done") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button("editor.selectAll") { selected = selected.count == model.projects.count ? [] : Set(model.projects) }.disabled(model.busy || model.projects.isEmpty)
                }
                ToolbarItemGroup(placement: .bottomBar) {
                    Button("editor.batch") { dismiss(); model.exportAll() }.disabled(model.busy || model.projects.isEmpty)
                    Spacer()
                    Button("editor.deleteSelected", role: .destructive) { deletion = Array(selected); confirming = true }.disabled(model.busy || selected.isEmpty)
                }
            }
            .confirmationDialog("editor.deleteProjectsConfirm", isPresented: $confirming, titleVisibility: .visible) {
                Button("editor.deleteSelected", role: .destructive) { model.deleteProjects(deletion); selected.subtract(deletion); deletion = [] }
            }
        }
    }
}

struct EditorSettings: View {
    @ObservedObject var model: EditorModel
    @State private var logoPicker = false
    @State private var resetConfirm = false
    @State private var popupKind = "text"
    @State private var popupLabel = ""
    @State private var popupValue = ""
    @State private var popupAnchor = "top-left"
    @State private var popupEditing: Int?
    @State private var popupWarning=false
    @State private var popupHide = "after_shot"
    @State private var popupDelay = 0.0
    @State private var popupDuration = 3.0
    @Environment(\.dismiss) private var dismiss
    private func number(_ key: String, value: Binding<Double>, range: ClosedRange<Double>) -> some View {
        VStack(alignment: .leading) { HStack { Text(LocalizedStringKey("editor." + key)); Spacer(); Text(value.wrappedValue, format: .number.precision(.fractionLength(2))).monospacedDigit() }; Slider(value: value, in: range) }
    }
    var body: some View {
        NavigationStack {
            Form {
                Section("editor.motionTitle") { Text(model.motionReport()).font(.footnote).textSelection(.enabled) }
                Toggle("editor.personOcclusion", isOn: Binding(
                    get: { model.scene.personOcclusion != false },
                    set: { model.scene.personOcclusion = $0 }
                )).accessibilityIdentifier("editor.personOcclusion")
                Section("editor.shotSettings") {
                    Picker("editor.trackingMode", selection:Binding(get:{ model.scene.shot.trackingMode ?? "flight" },set:{ model.setTrackingMode($0) })) {
                        Text("editor.modeFlight").tag("flight"); Text("editor.putt").tag("putt")
                    }
                    number("flight", value: $model.scene.shot.flight, range: 0.2...15)
                    if model.scene.shot.trackingMode != "putt" { number("apexFraction", value: Binding(get: { model.scene.shot.apexFraction * model.scene.shot.flight }, set: { model.scene.shot.apexFraction = min(0.62, max(0.35, $0 / model.scene.shot.flight)) }), range: (0.35 * model.scene.shot.flight)...(0.62 * model.scene.shot.flight)) }
                    number("speed", value: $model.scene.shot.speed, range: 0.5...4)
                    TextField("editor.color", text: $model.scene.shot.color).textInputAutocapitalization(.characters)
                    Button("editor.deleteShot", role: .destructive) { model.deleteShot(); dismiss() }.disabled(model.scene.shots.count <= 1)
                }
                Section("editor.style") {
                    Picker("editor.preset", selection: $model.scene.preset) { ForEach(["classic", "tv", "neon", "comet", "minimal"], id: \.self) { key in Text(LocalizedStringKey("editor." + key)).tag(key) } }
                    number("width", value: $model.scene.lineWidth, range: 0.2...5)
                    number("glow", value: $model.scene.glow, range: 0...3)
                    number("hold", value: $model.scene.hold, range: -1...30)
                    Toggle("editor.head", isOn: $model.scene.head); Toggle("editor.marker", isOn: $model.scene.marker)
                }
                popupSection
                Section("editor.logo") {
                    Button("editor.chooseLogo") { logoPicker = true }
                    number("logoSize", value: $model.scene.logoSize, range: 0.02...0.5)
                    number("logoOpacity", value: $model.scene.logoOpacity, range: 0...1)
                    Picker("editor.anchor", selection: $model.scene.logoCorner) { ForEach(["top-left", "top-right", "bottom-left", "bottom-right"], id: \.self) { Text(LocalizedStringKey("editor." + $0)).tag($0) } }
                    Button("editor.removeLogo") { model.scene.logo = nil }
                }
                Section("editor.slowmo") {
                    Toggle("editor.slowmoSilent", isOn: $model.scene.slowmo)
                    number("slowFactor", value: $model.scene.slowFactor, range: 1...8)
                    number("slowBefore", value: $model.scene.slowBefore, range: 0...5)
                    number("slowAfter", value: $model.scene.slowAfter, range: 0...10)
                }
                Button("editor.reset", role: .destructive) { resetConfirm = true }
            }
            .navigationTitle(Text("editor.settings"))
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button { dismiss() } label: { Image(systemName:"checkmark").frame(width:44,height:44) }.accessibilityLabel(Text("editor.done")).accessibilityIdentifier("editor.settings.done") } }
            .fileImporter(isPresented: $logoPicker, allowedContentTypes: [.image]) { result in if case .success(let url) = result { model.importLogo(url) } }
            .confirmationDialog("editor.resetConfirm", isPresented: $resetConfirm) { Button("editor.reset", role: .destructive) { model.reset(); dismiss() } }
        }
    }
    private func editPopup(_ i: Int) {
        let p=model.scene.shot.popups[i]; popupEditing=i; popupKind=p.kind; popupLabel=p.label; popupValue=p.value
        popupAnchor=p.anchor; popupHide=p.hide; popupDelay=p.delay; popupDuration=p.duration; popupWarning=false
    }
    private var popupSection: some View {
        Section("editor.popup") {
            ForEach(model.scene.shot.popups.indices,id:\.self) { i in
                HStack {
                    VStack(alignment:.leading) {
                        Text(model.scene.shot.popups[i].label+" "+model.scene.shot.popups[i].value).font(.caption)
                        if !model.popupIsReachable(i) { Text("editor.popupOutside").font(.caption2).foregroundStyle(.orange) }
                    }
                    Spacer()
                    Button("editor.popupPreview") { if model.previewPopup(i) { dismiss() } else { editPopup(i); popupWarning=true } }.buttonStyle(.borderless)
                    Button("editor.popupEdit") { editPopup(i) }.buttonStyle(.borderless)
                }
            }.onDelete { model.scene.shot.popups.remove(atOffsets:$0); popupEditing=nil; popupWarning=false }
            Button("editor.popupNew") { popupEditing=nil; popupLabel=""; popupValue=""; popupAnchor="top-left"; popupDelay=0; popupHide="after_shot"; popupDuration=3; popupWarning=false }
            Picker("editor.popup.kind", selection: $popupKind) { ForEach(["distance", "carry", "speed", "apex", "hangtime", "club", "player", "text"], id: \.self) { Text(LocalizedStringKey("editor.popup." + $0)).tag($0) } }
            TextField("editor.popup.label", text: $popupLabel); TextField("editor.popup.value", text: $popupValue)
            Picker("editor.anchor", selection: $popupAnchor) { ForEach(["landing", "apex", "start", "top-left", "top-right", "bottom-left", "bottom-right"], id: \.self) { Text(LocalizedStringKey("editor." + $0)).tag($0) } }
            Picker("editor.popup.hide", selection: $popupHide) { ForEach(["after_shot", "end", "time"], id: \.self) { Text(LocalizedStringKey("editor.popup." + $0)).tag($0) } }
            number("delay", value: $popupDelay, range: -5...15); number("popupDuration", value: $popupDuration, range: 0.1...30)
            Button {
                var popup=EditPopup(); popup.kind=popupKind
                popup.label=popupLabel.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty ? NSLocalizedString("editor.popup."+popupKind,comment:"") : popupLabel.trimmingCharacters(in:.whitespacesAndNewlines)
                popup.value=popupValue.trimmingCharacters(in:.whitespacesAndNewlines); popup.anchor=popupAnchor; popup.hide=popupHide; popup.delay=popupDelay; popup.duration=popupDuration
                let index: Int
                if let editing=popupEditing,model.scene.shot.popups.indices.contains(editing) { index=editing; model.scene.shot.popups[index]=popup }
                else { model.scene.shot.popups.append(popup); index=model.scene.shot.popups.count-1 }
                popupEditing=index
                if model.previewPopup(index) { dismiss() } else { popupWarning=true }
            } label: { Text(LocalizedStringKey(popupEditing == nil ? "editor.popup.add" : "editor.popupUpdate")) }
            if popupWarning { Text("editor.popupOutside").font(.footnote).foregroundStyle(.orange) }
            Text("editor.popup.note").font(.footnote).foregroundStyle(.secondary)
        }
    }
}
