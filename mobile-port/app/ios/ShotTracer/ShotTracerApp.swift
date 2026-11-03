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
    @State private var dragging = false
    @State private var pinching = false
    @State private var transformedGesture = false
    @State private var resetConfirm = false
    private func icon(_ key: String, _ symbol: String, disabled: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol).font(.system(size: 21)).frame(width: 44, height: 44).glassEffect(.regular, in: RoundedRectangle(cornerRadius: 16)) }
            .accessibilityLabel(Text(LocalizedStringKey(key))).help(Text(LocalizedStringKey(key))).disabled(disabled || model.busy)
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: 4) {
                if !fullscreen { header; shotBar; workflow }
                ZStack(alignment: .topTrailing) {
                    preview.frame(maxWidth: .infinity, maxHeight: .infinity).background(.black).clipShape(RoundedRectangle(cornerRadius:22)).overlay { RoundedRectangle(cornerRadius:22).stroke(.white.opacity(0.08)) }
                    if fullscreen { icon("editor.fullscreen", "arrow.down.right.and.arrow.up.left") { fullscreen = false }.padding(8) }
                }
                timeline
                if !fullscreen { tools; Text(model.status).font(.caption2).foregroundStyle(.secondary).lineLimit(3).padding(8).frame(maxWidth: .infinity, alignment: .leading).background(.white.opacity(0.04),in:RoundedRectangle(cornerRadius:12)) }
                if model.busy { ProgressView() }
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
            .onDisappear { model.pause() }
        }
    }
    private var header: some View {
        VStack(spacing:6) {
            HStack {
                VStack(alignment:.leading,spacing:1) { Text("app.title").font(.title2.bold()); Text("editor.onDevice").font(.system(size:9,weight:.bold,design:.rounded)).tracking(2).foregroundStyle(.mint) }
                Spacer()
                Picker("editor.trackingMode",selection:Binding(get:{ model.scene.shot.trackingMode ?? "flight" },set:{ model.setTrackingMode($0) })) {
                    Text("editor.modeFlight").tag("flight"); Text("editor.putt").tag("putt")
                }.pickerStyle(.segmented).frame(width:170).disabled(model.busy || !model.hasVideo)
            }
        ScrollView(.horizontal, showsIndicators: false) { HStack(spacing: 4) {
            PhotosPicker(selection: $selectedPhoto, matching: .videos, preferredItemEncoding: .current) {
                Image(systemName: "photo.on.rectangle.angled").font(.system(size: 21)).frame(width: 44, height: 44)
            }.accessibilityLabel(Text("action.photos")).disabled(model.busy)
            icon("action.import", "folder.badge.plus") { picking = true }
            icon("camera.open", "camera.fill") { model.pause(); showingCamera = true }
            icon("editor.projects", "square.stack") { model.reloadProjects(); showingProjects = true }
            icon("editor.undo", "arrow.uturn.backward", disabled: !model.canUndo) { model.undoEdit() }
            icon("editor.redo", "arrow.uturn.forward", disabled: !model.canRedo) { model.redoEdit() }
            icon("editor.settings", "slider.horizontal.3", disabled: !model.hasVideo) { settings = true }
            icon("editor.fullscreen", "arrow.up.left.and.arrow.down.right") { fullscreen = true }
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
    @ViewBuilder private var preview: some View {
        if let image = model.image {
            GeometryReader { area in
                let size = image.size
                let fit = min(area.size.width / size.width, area.size.height / size.height)
                let width = size.width * fit, height = size.height * fit
                ZStack {
                    ZStack(alignment: .topLeading) {
                        Image(uiImage: image).resizable()
                        if let overlay = model.overlay { Image(uiImage: overlay).resizable() }
                        ForEach(model.handles, id: \.0) { key, point in
                            Circle().stroke(key == "landing" ? Color.green : key == "apex" ? Color.cyan : Color.yellow, lineWidth: 2 / zoom)
                                .frame(width: 18 / zoom, height: 18 / zoom).position(x: point.x * width, y: point.y * height)
                        }
                    }
                    .frame(width: width, height: height).contentShape(Rectangle())
                    .gesture(DragGesture(minimumDistance: 0).onChanged { value in
                        guard !model.busy, !pinching else { return }
                        if model.tool == "pan" { pan = CGSize(width: lastPan.width + value.translation.width, height: lastPan.height + value.translation.height); return }
                        let point = EditPoint(value.location.x / width, value.location.y / height)
                        if !dragging {
                            dragging = true
                            dragHandle = model.handles.min { hypot($0.1.x - point.x, $0.1.y - point.y) < hypot($1.1.x - point.x, $1.1.y - point.y) }
                                .flatMap { key, p in hypot((p.x - point.x) * width, (p.y - point.y) * height) < 25 / zoom ? key : nil }
                            if dragHandle != nil { model.checkpoint() }
                        }
                        if let dragHandle { model.moveHandle(dragHandle, point) }
                    }.onEnded { value in
                        defer { dragging = false; dragHandle = nil; transformedGesture = false }
                        if model.tool == "pan" { lastPan = pan; return }
                        if pinching || transformedGesture { return }
                        if dragHandle != nil { model.persist() }
                        else { model.tap(EditPoint(value.location.x / width, value.location.y / height)) }
                    })
                    .simultaneousGesture(MagnifyGesture().onChanged { value in pinching = true; transformedGesture = true; zoom = min(6, max(1, lastZoom * value.magnification)) }.onEnded { _ in lastZoom = zoom; pinching = false })
                    .onTapGesture(count: 2) { zoom = 1; lastZoom = 1; pan = .zero; lastPan = .zero }
                    .scaleEffect(zoom).offset(pan)
                }.frame(width: area.size.width, height: area.size.height)
            }.accessibilityLabel(Text("preview.label"))
        } else { Image(systemName: "figure.golf").font(.system(size: 70)).foregroundStyle(.red).frame(maxWidth: .infinity, maxHeight: .infinity) }
    }
    private var timeline: some View {
        VStack(spacing: 2) {
            Text(String(format: NSLocalizedString("editor.frameCount", comment: ""), model.frameIndex + 1, model.frameCount, model.time)).font(.caption2).monospacedDigit()
            Slider(value: Binding(get: { Double(model.frameIndex) }, set: { model.pause(); model.seek(Int($0)) }), in: 0...Double(max(1, model.frameCount - 1)), step: 1).disabled(!model.hasVideo || model.busy)
            HStack(spacing: 6) {
                icon("editor.previous", "backward.frame.fill", disabled: !model.hasVideo) { model.step(-1) }
                icon("editor.play", model.playing ? "pause.fill" : "play.fill", disabled: !model.hasVideo) { if model.playing { model.pause() } else { model.play() } }
                icon("editor.next", "forward.frame.fill", disabled: !model.hasVideo) { model.step(1) }
                icon("action.analyze", "sparkle.magnifyingglass", disabled: model.scene.shot.ball == nil) { model.analyze() }
                icon("action.export", "square.and.arrow.up", disabled: !model.canExport) { model.export() }
                icon("editor.review", "play.rectangle.fill", disabled: model.output == nil) { reviewing = true }
                if let output = model.output { ShareLink(item: output) { Image(systemName: "square.and.arrow.down").frame(width: 40, height: 44) }.accessibilityLabel(Text("action.share")) }
            }
        }
    }
    private var tools: some View {
        ScrollView(.horizontal, showsIndicators: false) { HStack(spacing: 4) {
            ForEach([("ball", "scope"), ("landing", "flag.fill"), ("apex", "arrow.up.to.line"), ("path", "scribble.variable"), ("circle", "circle"), ("line", "line.diagonal"), ("swing", "figure.golf"), ("pan", "hand.point.up.left")], id: \.0) { key, symbol in
                icon("editor." + key, symbol, disabled: !model.hasVideo || (key == "apex" && model.scene.shot.trackingMode == "putt")) { model.chooseTool(key) }.tint(model.tool == key ? .mint : .white)
            }
            icon("editor.delete", "trash", disabled: !model.hasVideo) { model.deleteShape() }
            icon("editor.landingFrame", "flag.checkered", disabled: model.scene.shot.ball == nil) { model.landOnFrame() }
        } }
    }
}

struct ReviewView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var player: AVPlayer
    init(url: URL) { _player = State(initialValue: AVPlayer(url: url)) }
    var body: some View {
        NavigationStack { VideoPlayer(player: player).background(.black).navigationTitle(Text("editor.review"))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("camera.close") { dismiss() } } }
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
    @State private var popupAnchor = "landing"
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
    private var popupSection: some View {
        Section("editor.popup") {
            ForEach(model.scene.shot.popups.indices, id: \.self) { i in Text(model.scene.shot.popups[i].label + " " + model.scene.shot.popups[i].value) }
                .onDelete { model.scene.shot.popups.remove(atOffsets: $0) }
            Picker("editor.popup.kind", selection: $popupKind) { ForEach(["distance", "carry", "speed", "apex", "hangtime", "club", "player", "text"], id: \.self) { Text(LocalizedStringKey("editor.popup." + $0)).tag($0) } }
            TextField("editor.popup.label", text: $popupLabel); TextField("editor.popup.value", text: $popupValue)
            Picker("editor.anchor", selection: $popupAnchor) { ForEach(["landing", "apex", "start", "top-left", "top-right", "bottom-left", "bottom-right"], id: \.self) { Text(LocalizedStringKey("editor." + $0)).tag($0) } }
            Picker("editor.popup.hide", selection: $popupHide) { ForEach(["after_shot", "end", "time"], id: \.self) { Text(LocalizedStringKey("editor.popup." + $0)).tag($0) } }
            number("delay", value: $popupDelay, range: -5...15); number("popupDuration", value: $popupDuration, range: 0.1...30)
            Button("editor.popup.add") {
                var popup = EditPopup(); popup.kind = popupKind; popup.label = popupLabel.isEmpty ? NSLocalizedString("editor.popup." + popupKind, comment: "") : popupLabel
                popup.value = popupValue; popup.anchor = popupAnchor; popup.hide = popupHide; popup.delay = popupDelay; popup.duration = popupDuration
                model.scene.shot.popups.append(popup); popupLabel = ""; popupValue = ""
            }
            Text("editor.popup.note").font(.footnote).foregroundStyle(.secondary)
        }
    }
}
