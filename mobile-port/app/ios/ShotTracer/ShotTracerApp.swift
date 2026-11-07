import AVKit
import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

@main struct ShotTracerApp: App { var body: some Scene { WindowGroup { EditorView() } } }

struct EditorView: View {
    @StateObject private var model = EditorModel()
    @State private var picking = false
    @State private var showingCamera = false
    @State private var settings = false
    @State private var reviewing = false
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
    @Environment(\.scenePhase) private var editorPhase
    private func icon(_ key: String, _ symbol: String, disabled: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol).font(.system(size: 21)).frame(width: 44, height: 44).glassEffect(.regular, in: RoundedRectangle(cornerRadius: 16)) }
            .accessibilityLabel(Text(LocalizedStringKey(key))).help(Text(LocalizedStringKey(key))).disabled(disabled || model.busy || model.editingTimeline)
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: 4) {
                if fullscreen {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 4) {
                            icon("editor.fullscreen", "arrow.down.right.and.arrow.up.left") { fullscreen = false }
                            transport
                        }
                    }.fixedSize(horizontal: false, vertical: true)
                } else {
                    header
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            workflow.frame(width: 252)
                            shotBar.fixedSize(horizontal: true, vertical: false)
                        }
                    }.fixedSize(horizontal: false, vertical: true)
                    tools
                }
                HStack {
                    Text(model.status).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                    if model.hasVideo {
                        Text(String(format: NSLocalizedString("editor.frameCount", comment: ""), model.frameIndex + 1, model.frameCount, model.time))
                            .monospacedDigit().lineLimit(1).minimumScaleFactor(0.75)
                    }
                    if model.busy { ProgressView().controlSize(.small) }
                }.font(.caption2).foregroundStyle(.secondary)
                GeometryReader { area in
                    let timelineHeight = model.hasVideo ? area.size.height * 0.2 : 0
                    VStack(spacing: 0) {
                        preview.frame(width: area.size.width, height: area.size.height - timelineHeight)
                            .background(.black).clipShape(RoundedRectangle(cornerRadius: 18))
                        if model.hasVideo { filmstrip.frame(height: timelineHeight) }
                    }
                }
            }
            .padding(.horizontal, 8).background(LinearGradient(colors:[Color(red:0.04,green:0.09,blue:0.12),Color(red:0.03,green:0.05,blue:0.08)],startPoint:.topLeading,endPoint:.bottomTrailing)).preferredColorScheme(.dark).tint(.mint)
            .toolbar(.hidden, for: .navigationBar)
            .fullScreenCover(isPresented: $showingCamera) { LiveCameraView() }
            .fullScreenCover(isPresented: $reviewing) { if let url = model.output { ReviewView(url: url) } }
            .sheet(isPresented: $settings) { EditorSettings(model: model).onAppear { model.checkpoint() }.onDisappear { model.commitEdits() } }
            .sheet(isPresented: $showingProjects) { ProjectList(model: model) }
            .confirmationDialog("editor.resetConfirm", isPresented: $resetConfirm) { Button("editor.resetAction", role: .destructive) { model.reset() } }
            .fileImporter(isPresented: $picking, allowedContentTypes: [.movie]) { result in if case .success(let url) = result { model.importVideo(url) } }
            .onChange(of: selectedPhoto) { _, item in guard let item else { return }; model.importPhoto(item); selectedPhoto = nil }
            .onDisappear { model.pause(); model.endTimeEdit(cancel:true); draggingTime=nil; markerOrigin=nil; panOrigin=nil }
            .onChange(of:editorPhase) { _,phase in if phase != .active { model.pause(); model.endTimeEdit(cancel:true); draggingTime=nil; markerOrigin=nil; panOrigin=nil } }
        }
    }
    private var header: some View {
        VStack(spacing:6) {
            HStack {
                Image("Brand").resizable().scaledToFit().frame(width:36,height:36).clipShape(RoundedRectangle(cornerRadius:8)).accessibilityHidden(true)
                VStack(alignment:.leading,spacing:1) { Text("app.title").font(.title3.bold()); Text("editor.onDevice").font(.system(size:9,weight:.bold,design:.rounded)).tracking(2).foregroundStyle(.mint) }
                Spacer()
                Picker("editor.trackingMode",selection:Binding(get:{ model.scene.shot.trackingMode ?? "flight" },set:{ model.setTrackingMode($0) })) {
                    Text("editor.modeFlight").tag("flight"); Text("editor.putt").tag("putt")
                }.pickerStyle(.segmented).frame(width:150).disabled(model.busy || !model.hasVideo)
            }
        ScrollView(.horizontal, showsIndicators: false) { HStack(spacing: 4) {
            PhotosPicker(selection: $selectedPhoto, matching: .videos, preferredItemEncoding: .current) {
                Image(systemName: "photo.on.rectangle.angled").font(.system(size: 21)).frame(width: 44, height: 44)
            }.accessibilityLabel(Text("action.photos")).disabled(model.busy)
            icon("action.import", "folder.badge.plus") { picking = true }
            icon("camera.open", "camera.fill") { model.pause(); showingCamera = true }
            icon("editor.projects", "square.stack") { model.reloadProjects(); showingProjects = true }
            icon("editor.fullscreen", "arrow.up.left.and.arrow.down.right") { fullscreen = true }
            transport
            icon("editor.undo", "arrow.uturn.backward", disabled: !model.canUndo) { model.undoEdit() }
            icon("editor.redo", "arrow.uturn.forward", disabled: !model.canRedo) { model.redoEdit() }
            icon("editor.settings", "slider.horizontal.3", disabled: !model.hasVideo) { settings = true }
        } }
        }
    }
    private var shotBar: some View {
        HStack {
            ForEach(model.scene.shots.indices, id: \.self) { index in
                Button(String(format: NSLocalizedString("editor.shotNumber", comment: ""), index + 1)) { model.selectShot(index) }
                    .font(.caption).padding(.horizontal, 12).padding(.vertical, 6).background(index == model.scene.selected ? Color.mint.opacity(0.25) : Color.white.opacity(0.08), in: Capsule())
            }
            icon("editor.add", "plus.circle.fill", disabled: !model.hasVideo || model.scene.shots.count >= 4) { model.addShot() }
            Spacer(minLength: 0)
        }.frame(height: 34)
    }
    private var workflow: some View {
        HStack {
            Button { model.pause(); model.chooseTool("ball") } label: {
                Label { Text(LocalizedStringKey(model.scene.shot.ball == nil ? "editor.startUnset" : "editor.startChecked")) } icon: {
                    Image(systemName: model.scene.shot.ball == nil ? "scope" : "checkmark.circle.fill")
                }
            }.tint(model.scene.shot.ball == nil ? .white : .green)
            Spacer(minLength: 2)
            Button("editor.analyzeAction") { model.analyze() }.buttonStyle(.glassProminent).tint(.mint).disabled(model.scene.shot.ball == nil)
            Spacer(minLength: 2)
            Button { model.pause(); resetConfirm = true } label: { Label("editor.resetAction", systemImage: "arrow.counterclockwise") }
        }.font(.caption).buttonStyle(.glass).disabled(model.busy || !model.hasVideo)
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
                        Image(uiImage: image).resizable()
                        if let overlay = model.overlay { Image(uiImage: overlay).resizable() }
                        ForEach(model.handles, id: \.0) { key, point in
                            Circle().stroke(key == "landing" ? Color.green : key == "apex" ? Color.cyan : Color.yellow, lineWidth: 2 / zoom)
                                .frame(width: 18 / zoom, height: 18 / zoom)
                                .overlay(alignment:point.x > 0.7 ? .topTrailing : .topLeading) {
                                    if key == "ball" || key == "landing" || (key == "apex" && model.scene.shot.trackingMode == "putt") {
                                        Text(LocalizedStringKey(key == "ball" ? "editor.timelineStart" : key == "apex" ? "editor.puttCurve" : "editor.timelineLanding"))
                                            .font(.system(size:10/zoom,weight:.bold)).foregroundStyle(key == "ball" ? Color.yellow : key == "apex" ? Color.cyan : Color.green)
                                            .padding(3/zoom).background(.black.opacity(0.8),in:RoundedRectangle(cornerRadius:3/zoom)).fixedSize()
                                            .offset(x:(point.x > 0.7 ? -20 : 20)/zoom,y:(point.y < 0.08 ? 20 : -14)/zoom)
                                    }
                                }.position(x: point.x * width, y: point.y * height)
                        }
                    }
                    .frame(width: width, height: height)
                    .scaleEffect(zoom).offset(pan)
                }.frame(width: area.size.width, height: area.size.height).contentShape(Rectangle())
                    .coordinateSpace(name:"videoViewport")
                    .gesture(DragGesture(minimumDistance:0,coordinateSpace:.named("videoViewport")).onChanged { value in
                        guard !model.busy, !pinching, !transformedGesture else { return }
                        let point=videoPoint(value.location,area.size,fitted)
                        if !dragging {
                            dragging=true; dragPanning=false; lastPan=pan
                            let start=videoPoint(value.startLocation,area.size,fitted)
                            dragHandle = model.tool == "pan" ? nil : model.handles.min {
                                hypot(($0.1.x-start.x)*width,($0.1.y-start.y)*height) < hypot(($1.1.x-start.x)*width,($1.1.y-start.y)*height)
                            }.flatMap { key,p in hypot((p.x-start.x)*width*zoom,(p.y-start.y)*height*zoom)<25 ? key : nil }
                            if dragHandle != nil { model.checkpoint() }
                        }
                        if let dragHandle {
                            model.moveHandle(dragHandle,EditPoint(min(1,max(0,point.x)),min(1,max(0,point.y))))
                        } else if (model.tool == "pan" || zoom>1) && hypot(value.translation.width,value.translation.height)>6 {
                            dragPanning=true
                            pan=viewportPan(CGSize(width:lastPan.width+value.translation.width,height:lastPan.height+value.translation.height),fitted)
                        }
                    }.onEnded { value in
                        defer { dragging=false; dragHandle=nil; transformedGesture=false; dragPanning=false; lastPan=pan }
                        guard !model.busy, !pinching, !transformedGesture else { return }
                        if dragHandle != nil { model.persist(); return }
                        if dragPanning || model.tool == "pan" || hypot(value.translation.width,value.translation.height)>6 { return }
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
        icon("action.analyze", "sparkle.magnifyingglass", disabled: model.scene.shot.ball == nil) { model.analyze() }
        icon("action.export", "square.and.arrow.up", disabled: !model.canExport) { model.export() }
        icon("editor.review", "play.rectangle.fill", disabled: model.output == nil) { reviewing = true }
        if model.output != nil { icon("editor.albumSave","square.and.arrow.down") { model.saveToAlbum() } }
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
            let labelSize = min(10, laneHeight * 0.21)
            ZStack(alignment: .topLeading) {
                Rectangle().fill(Color(red: 0.06, green: 0.09, blue: 0.13))
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
                                    Color.white.opacity(0.08)
                                    if let image = model.thumbnails[index] {
                                        Image(uiImage: image).resizable().scaledToFit()
                                    }
                                }.frame(width: tile - 1, height: snapshot).clipped()
                            }.frame(width: tile).position(x: x, y: lane / 2)
                                .allowsHitTesting(false).accessibilityHidden(true)
                        }
                    }
                }
                Rectangle().fill(.white.opacity(0.06)).frame(height: laneHeight).offset(y: lane).allowsHitTesting(false)
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
                Rectangle().fill(.white).frame(width: 2, height: area.size.height)
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
    private func eventButton(_ key: String) -> some View {
        let event=model.timelineEvents.first { $0.key == key }
        return Button { if let event { jump(event) } } label: {
            Text(event.map { eventLabel($0) } ?? NSLocalizedString(key == "ball" ? "editor.timelineStartUnset" : key == "apex" ? "editor.timelineApexUnset" : "editor.timelineLandingUnset",comment:""))
                .font(.system(size:10,weight:.medium)).monospacedDigit().lineLimit(2).minimumScaleFactor(0.75)
                .frame(width: 108, height: 44).background(.white.opacity(0.06),in:RoundedRectangle(cornerRadius:12))
        }.buttonStyle(.plain).foregroundStyle(eventColor(key)).disabled(model.busy || event?.inClip != true)
    }
    private var tools: some View {
        ScrollView(.horizontal, showsIndicators: false) { HStack(spacing: 4) {
            ForEach([("ball", "scope"), ("landing", "flag.fill"), ("apex", "arrow.up.to.line"), ("path", "scribble.variable"), ("circle", "circle"), ("line", "line.diagonal"), ("swing", "figure.golf"), ("pan", "hand.point.up.left")], id: \.0) { key, symbol in
                icon(key == "apex" && model.scene.shot.trackingMode == "putt" ? "editor.puttCurve" : "editor." + key, symbol, disabled: !model.hasVideo || (key == "apex" && model.scene.shot.trackingMode == "putt" && !model.scene.shot.hasGroundPath)) { model.chooseTool(key) }.tint(model.tool == key ? .mint : .white)
            }
            icon("editor.delete", "trash", disabled: !model.hasVideo) { model.deleteShape() }
            icon("editor.landingFrame", "flag.checkered", disabled: model.scene.shot.ball == nil) { model.landOnFrame() }
            if model.hasVideo { eventButton("ball"); eventButton("apex"); eventButton("landing") }
        } }
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
                Section("editor.autoSwing") {
                    Toggle("editor.personOcclusion",isOn:Binding(get:{ model.scene.personOcclusion != false },set:{ model.scene.personOcclusion=$0 }))
                    Picker("editor.autoSwing",selection:Binding(get:{ model.scene.autoSwing ?? "both" },set:{ model.scene.autoSwing=$0 })) {
                        ForEach(["both","hands","club","off"],id:\.self) { Text(LocalizedStringKey("editor."+$0)).tag($0) }
                    }
                    Text("editor.autoNote").font(.footnote).foregroundStyle(.secondary)
                }
                Section("editor.shotSettings") {
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
                    Toggle("editor.swingEnabled", isOn: $model.scene.swingEnabled)
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
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("editor.done") { dismiss() } } }
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
