import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

@main
struct ShotTracerApp: App {
    var body: some Scene { WindowGroup { EditorView() } }
}

struct EditorView: View {
    @StateObject private var model = EditorModel()
    @State private var picking = false
    @State private var selectedPhoto: PhotosPickerItem?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    Text("prototype.notice").font(.footnote).foregroundStyle(.secondary)
                    if let image = model.image {
                        let size = image.size
                        GeometryReader { geometry in
                            ZStack(alignment: .topLeading) {
                                Image(uiImage: image).resizable()
                                if let overlay = model.overlay { Image(uiImage: overlay).resizable() }
                                if let ball = model.ball {
                                    Circle().stroke(.yellow, lineWidth: 2).frame(width: 18, height: 18)
                                        .position(x: ball.x / size.width * geometry.size.width,
                                                  y: ball.y / size.height * geometry.size.height)
                                }
                            }
                            .contentShape(Rectangle())
                            .gesture(SpatialTapGesture().onEnded { tap in
                                model.selectBall(CGPoint(x: tap.location.x / geometry.size.width,
                                                         y: tap.location.y / geometry.size.height))
                            })
                            .accessibilityLabel(Text("preview.label"))
                        }
                        .aspectRatio(size, contentMode: .fit)
                        Slider(value: $model.time, in: 0...max(0.001, model.duration - 0.04), onEditingChanged: { editing in
                            if !editing { model.refreshFrame() }
                        }).disabled(model.busy)
                        Text(String(format: NSLocalizedString("status.time", comment: ""), model.time, model.duration)).monospacedDigit()
                    } else {
                        Image(systemName: "figure.golf").font(.system(size: 72)).padding(30).foregroundStyle(.red)
                    }
                    PhotosPicker(selection: $selectedPhoto, matching: .videos, preferredItemEncoding: .current) {
                        Text("action.photos")
                    }.buttonStyle(.borderedProminent)
                    .onChange(of: selectedPhoto) { _, item in
                        guard let item else { return }
                        model.importPhoto(item)
                        selectedPhoto = nil
                    }
                    Button("action.import") { picking = true }.buttonStyle(.bordered)
                    Button("action.analyze") { model.analyze() }.buttonStyle(.borderedProminent)
                        .disabled(model.ball == nil || model.image == nil)
                    Button("action.export") { model.export() }.buttonStyle(.bordered)
                        .disabled(!model.canExport)
                    if model.busy { ProgressView() }
                    Text(model.status).font(.callout).accessibilityAddTraits(.updatesFrequently)
                    if let output = model.output { ShareLink("action.share", item: output) }
                }
                .padding()
                .disabled(model.busy)
            }
            .navigationTitle(Text("app.title"))
            .fileImporter(isPresented: $picking, allowedContentTypes: [.movie]) { result in
                switch result {
                case .success(let url): model.importVideo(url)
                case .failure: break
                }
            }
        }
    }
}
