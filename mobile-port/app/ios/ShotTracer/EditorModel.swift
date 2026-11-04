import AVFoundation
import Combine
import CoreImage
import CoreTransferable
import PhotosUI
import Photos
import SwiftUI
import UniformTypeIdentifiers

/// A picker URL expires after the transfer callback, so retain an owned file,
/// rather than loading a potentially large movie into Data.
private struct PickedMovie: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { received in
            let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let file = folder.appendingPathComponent("video.\(received.file.pathExtension.isEmpty ? "mov" : received.file.pathExtension)")
            do {
                try FileManager.default.copyItem(at: received.file, to: file)
                return PickedMovie(url: file)
            } catch {
                try? FileManager.default.removeItem(at: folder)
                throw error
            }
        }
    }
}

@MainActor
final class EditorModel: ObservableObject {
    @Published var image: UIImage?
    @Published var overlay: UIImage?
    @Published var scene = EditorScene()
    @Published var frameIndex = 0
    @Published var time = 0.0
    @Published var duration = 0.0
    @Published var busy = false
    @Published var playing = false
    @Published var editingTimeline=false
    private var timeUndo: EditorScene?
    @Published var tool = "ball"
    @Published var status = NSLocalizedString("hint.import", comment: "")
    @Published var output: URL?
    @Published var projects: [URL] = []
    private(set) var times = [0.0]
    private var source: URL?
    private var renderer = EditorRenderer(EditorScene())
    private var task: Task<Void, Never>?
    private var previewTask: Task<Void, Never>?
    private var playTask: Task<Void, Never>?
    private var previewGeneration = 0
    private var displayedTime = 0.0
    private var pending: EditPoint?
    private var undo: [EditorScene] = []
    private var redo: [EditorScene] = []
    var hasVideo: Bool { source != nil }
    var canExport: Bool { scene.shots.contains { $0.ball != nil && ($0.edited || !$0.base.isEmpty) } }
    var canUndo: Bool { !undo.isEmpty }
    var canRedo: Bool { !redo.isEmpty }
    var frameCount: Int { times.count }
    var timelineEvents: [ShotEvent] { hasVideo ? makeShotEvents(scene.shot,times,duration) : [] }
    func jumpToEvent(_ event: ShotEvent) {
        guard !busy,event.inClip else { return }
        pause(); chooseTool(event.key); seek(event.frame)
    }
    func beginTimeEdit() {
        guard !busy,!editingTimeline else { return }
        pause(); timeUndo=scene; editingTimeline=true; pending=nil
    }
    func dragTime(_ key: String,_ frame: Int) {
        guard editingTimeline,!busy else { return }
        guard let target=retimeShot(&scene.shot,key,frame,times) else { return }
        tool=key; commitEdits(save:false)
        if target != frameIndex { seek(target) }
    }
    func endTimeEdit(cancel: Bool = false) {
        guard let before=timeUndo else { return }
        if cancel { scene=before; commitEdits(save:false) }
        else if before.shot.start != scene.shot.start || before.shot.flight != scene.shot.flight || before.shot.landing != scene.shot.landing || before.shot.timingLandingEstimated != scene.shot.timingLandingEstimated {
            undo.append(before); if undo.count>50 { undo.removeFirst() }; redo=[]; persist()
        }
        timeUndo=nil; editingTimeline=false
    }
    var handles: [(String, EditPoint)] {
        let shot = scene.shot, m = interpolated(shot.camera, displayedTime)
        func point(_ p: EditPoint) -> EditPoint { EditPoint(p.x + m.x, p.y + m.y) }
        var result: [(String, EditPoint)] = []
        if let ball = shot.ball { result.append(("ball", point(ball)))
            if shot.edited || !shot.base.isEmpty { result.append(("landing", point(shot.endpoint))); if shot.trackingMode != "putt" { result.append(("apex", point(shot.peak))) } }
        }
        if tool == "line" || tool == "circle" { for i in scene.shapes.indices { result.append(("shape:\(i):a", scene.shapes[i].a)); result.append(("shape:\(i):b", scene.shapes[i].b)) } }
        return result
    }
    init() { reloadProjects() }
    func reloadProjects() {
        let root = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let folders = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        projects = folders.flatMap { folder in ((try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []).filter { $0.lastPathComponent.hasPrefix("source.") } }.sorted { $0.path > $1.path }
    }
    func checkpoint() { undo.append(scene); if undo.count > 50 { undo.removeFirst() }; redo.removeAll() }
    func undoEdit() { guard let previous = undo.popLast() else { return }; redo.append(scene); scene = previous; commitEdits() }
    func redoEdit() { guard let next = redo.popLast() else { return }; undo.append(scene); scene = next; commitEdits() }
    func persist() {
        guard let source else { return }
        do { try JSONEncoder().encode(scene).write(to: source.deletingLastPathComponent().appendingPathComponent("mobile-project.json"), options: .atomic) }
        catch { status = error.localizedDescription }
    }
    func commitEdits(save: Bool = true) {
        output = nil; renderer = EditorRenderer(scene); overlay = renderer.overlay(displayedTime)
        if save { persist() }
    }
    func chooseTool(_ key: String) { tool = key; pending = nil; status = NSLocalizedString("editor.hint." + key, comment: "") }
    func addShot() {
        guard hasVideo, scene.shots.count < 4 else { status = NSLocalizedString("editor.maxShots", comment: ""); return }
        checkpoint(); var shot = EditShot(); shot.start = displayedTime
        scene.shots.append(shot); scene.selected = scene.shots.count - 1; tool = "ball"; commitEdits()
    }
    func deleteShot() { guard scene.shots.count > 1 else { return }; checkpoint(); scene.shots.remove(at: scene.selected); scene.selected = min(scene.selected, scene.shots.count - 1); commitEdits() }
    func deleteShape() { if !scene.shapes.isEmpty { checkpoint(); scene.shapes.removeLast(); commitEdits() } else { deleteShot() } }
    func landOnFrame() { guard scene.shot.ball != nil, displayedTime > scene.shot.start else { return }; checkpoint(); scene.shot.landing = scene.shot.endpoint; scene.shot.flight = min(15, max(0.2, displayedTime - scene.shot.start)); chooseTool("landing"); commitEdits() }
    func reset() {
        guard !busy,!editingTimeline, hasVideo else { return }
        pause(); checkpoint(); let w = scene.width, h = scene.height
        scene = EditorScene(); scene.width = w; scene.height = h; chooseTool("ball"); commitEdits(); seek(0)
    }
    func deleteProjects(_ files: [URL]) {
        guard !busy, !files.isEmpty else { return }
        let root = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].resolvingSymlinksInPath()
        let known = Set(projects)
        let folders = Set(files.filter { known.contains($0) }.map { $0.deletingLastPathComponent().resolvingSymlinksInPath() })
            .filter { $0.deletingLastPathComponent() == root }
        guard !folders.isEmpty else { return }
        pause(); previewTask?.cancel(); previewGeneration += 1; busy = true
        task = Task {
            let deleted = await Task.detached { folders.filter { folder in
                do { try FileManager.default.removeItem(at: folder); return true } catch { return false }
            } }.value
            if let source, !FileManager.default.fileExists(atPath: source.path) {
                self.source = nil; output = nil; image = nil; overlay = nil; scene = EditorScene()
                renderer = EditorRenderer(scene); times = [0]; frameIndex = 0; time = 0; displayedTime = 0; duration = 0
                undo = []; redo = []; pending = nil; tool = "ball"
            }
            reloadProjects(); busy = false
            status = deleted.count == folders.count ? String(format: NSLocalizedString("editor.projectsDeleted", comment: ""), deleted.count) : NSLocalizedString("editor.deleteFailed", comment: "")
        }
    }
    func selectShot(_ index: Int) { guard !editingTimeline, !busy, scene.shots.indices.contains(index) else { return }; scene.selected = index; persist() }
    private func reference(_ p: EditPoint) -> EditPoint { let m = interpolated(scene.shot.camera, displayedTime); return EditPoint(p.x - m.x, p.y - m.y) }
    func moveHandle(_ key: String, _ p: EditPoint) {
        let ref = reference(p)
        switch key {
        case "ball": scene.shot.ball = ref; scene.shot.manual = true
        case "landing": scene.shot.landing = ref; scene.shot.timingLandingEstimated=false
        case "apex": scene.shot.apex = ref
        default:
            let parts = key.split(separator: ":")
            if parts.count == 3, let i = Int(parts[1]), scene.shapes.indices.contains(i) {
                if parts[2] == "a" { scene.shapes[i].a = p } else { scene.shapes[i].b = p }
            }
        }
        commitEdits(save: false)
    }
    func tap(_ point: EditPoint) {
        guard !busy, hasVideo else { return }
        let p = EditPoint(min(1, max(0, point.x)), min(1, max(0, point.y))), r = reference(point)
        switch tool {
        case "ball": checkpoint(); scene.shot.ball = p; scene.shot.start = displayedTime; scene.shot.base = []; scene.shot.baseModel = nil; scene.shot.camera = []; scene.shot.autoHands=nil; scene.shot.autoClub=nil; scene.shot.masks=nil; scene.shot.landing = nil; scene.shot.timingLandingEstimated=nil; scene.shot.apex = nil; scene.shot.manual = false; commitEdits(); status=NSLocalizedString("editor.startOptions",comment:"")
        case "landing", "apex":
            checkpoint(); if scene.shot.ball == nil { scene.shot.ball = EditPoint(0.2, 0.8); scene.shot.start = displayedTime }
            moveHandle(tool, p); persist()
        case "path": checkpoint(); if pending == nil { scene.shot.ball = p; scene.shot.start = displayedTime; scene.shot.manual = true; pending = p } else { scene.shot.landing = r; pending = nil }; commitEdits()
        case "circle", "line": if let first = pending { checkpoint(); scene.shapes.append(EditShape(kind: tool, a: first, b: p)); pending = nil; commitEdits() } else { pending = p }
        case "swing": checkpoint(); scene.shot.swing.removeAll { abs($0.time - displayedTime) < 0.00001 }; scene.shot.swing.append(EditSample(displayedTime, r.x, r.y)); scene.shot.swing.sort { $0.time < $1.time }; commitEdits(); step(2)
        default: break
        }
    }
    func importVideo(_ url: URL) {
        guard beginImport() else { return }
        task = Task { defer { busy = false }; do { try await copyVideo(url) } catch { status = error.localizedDescription } }
    }
    func importPhoto(_ item: PhotosPickerItem) {
        guard beginImport() else { return }
        task = Task { defer { busy = false }; do {
            guard let movie = try await item.loadTransferable(type: PickedMovie.self) else { throw MobileError.missingVideo }
            defer { try? FileManager.default.removeItem(at: movie.url.deletingLastPathComponent()) }
            try await copyVideo(movie.url)
        } catch { status = error.localizedDescription } }
    }
    private func beginImport() -> Bool {
        guard !busy,!editingTimeline else { return false }; pause(); previewTask?.cancel(); previewGeneration += 1
        busy = true; status = NSLocalizedString("status.importing", comment: ""); return true
    }
    private func copyVideo(_ incoming: URL) async throws {
        let accessed = incoming.startAccessingSecurityScopedResource()
        defer { if accessed { incoming.stopAccessingSecurityScopedResource() } }
        var folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var imported = false; defer { if !imported { try? FileManager.default.removeItem(at: folder) } }
        var values = URLResourceValues(); values.isExcludedFromBackup = true; try folder.setResourceValues(values)
        let local = folder.appendingPathComponent("source.\(incoming.pathExtension.isEmpty ? "mov" : incoming.pathExtension)")
        try await Task.detached { try FileManager.default.copyItem(at: incoming, to: local) }.value
        try await prepare(local); imported = true; reloadProjects()
    }
    func openProject(_ file: URL) {
        guard beginImport() else { return }
        task = Task { defer { busy = false }; do { try await prepare(file) } catch { status = error.localizedDescription } }
    }
    private func prepare(_ file: URL) async throws {
        let asset = AVURLAsset(url: file); let seconds = try await asset.load(.duration).seconds
        guard seconds.isFinite, seconds > 0 else { throw MobileError.missingVideo }
        let rows = try await Self.frameTimes(file)
        let frame = try await Self.frame(file, at: rows[0]).0
        let project = file.deletingLastPathComponent().appendingPathComponent("mobile-project.json")
        var restored = (try? JSONDecoder().decode(EditorScene.self, from: Data(contentsOf: project))) ?? EditorScene()
        if restored.shots.isEmpty { restored.shots = [EditShot()] }
        restored.shots = Array(restored.shots.prefix(4)); restored.selected = min(max(0, restored.selected), restored.shots.count - 1)
        restored.width = frame.width; restored.height = frame.height
        scene = restored; source = file; times = rows; duration = seconds; frameIndex = 0; time = rows[0]; displayedTime = rows[0]
        undo = []; redo = []; image = UIImage(cgImage: frame); renderer = EditorRenderer(scene); overlay = renderer.overlay(displayedTime)
        let files = (try? FileManager.default.contentsOfDirectory(at: file.deletingLastPathComponent(), includingPropertiesForKeys: nil)) ?? []
        output = files.filter { $0.lastPathComponent.hasPrefix("traced-") }.max { ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) < ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) }
        status = NSLocalizedString("hint.ball", comment: "")
    }
    func importLogo(_ file: URL) {
        guard !busy, let source else { return }
        let accessed = file.startAccessingSecurityScopedResource(); defer { if accessed { file.stopAccessingSecurityScopedResource() } }
        do {
            let target = source.deletingLastPathComponent().appendingPathComponent("logo-\(UUID().uuidString).png")
            try FileManager.default.copyItem(at: file, to: target); checkpoint(); scene.logo = target.path; commitEdits()
        } catch { status = error.localizedDescription }
    }
    func seek(_ frame: Int) { frameIndex = min(max(0, frame), times.count - 1); time = times[frameIndex]; refreshFrame() }
    func step(_ delta: Int) { pause(); seek(frameIndex + delta) }
    func play() { guard !busy, hasVideo else { return }; playing = true; advance() }
    func pause() { playing = false; playTask?.cancel() }
    private func advance() {
        guard playing, frameIndex < times.count - 1 else { pause(); return }
        let factor = scene.slowmo && slowIntervals(scene, duration).contains(where: { time >= $0.0 && time < $0.1 }) ? scene.slowFactor : 1.0
        let delta = (times[frameIndex + 1] - times[frameIndex]) * factor
        playTask = Task { try? await Task.sleep(for: .seconds(max(0.001, delta))); guard !Task.isCancelled, playing else { return }; seek(frameIndex + 1) }
    }
    func refreshFrame() {
        guard let source, !busy else { return }
        previewGeneration += 1; let generation = previewGeneration, requested = time; previewTask?.cancel()
        previewTask = Task {
            do { let (frame, actual) = try await Self.frame(source, at: requested)
                guard !Task.isCancelled, generation == previewGeneration else { return }
                image = UIImage(cgImage: frame); displayedTime = actual; overlay = renderer.overlay(actual)
                if playing { advance() }
            } catch { if generation == previewGeneration { pause(); status = error.localizedDescription } }
        }
    }
    func setTrackingMode(_ mode: String) {
        guard !busy, (scene.shot.trackingMode ?? "flight") != mode else { return }
        checkpoint(); scene.shot.trackingMode=mode; scene.shot.base=[]; scene.shot.baseModel=nil
        scene.shot.landing=nil; scene.shot.timingLandingEstimated=nil; scene.shot.apex=nil; scene.shot.manual=false; scene.shot.flight=4
        chooseTool("ball"); commitEdits(); status=NSLocalizedString("editor.modeChanged",comment:"")
    }
    func motionReport() -> String {
        let shot=scene.shot, impact=scene.shot.baseModel?[3] ?? scene.shot.base.first?.time ?? scene.shot.start
        func summary(_ rows: [EditSample]) -> [Double]? {
            let points=rows.flatMap { [$0.time,$0.x*Double(scene.width),$0.y*Double(scene.height)] }; var out=[Double](repeating:0,count:5)
            return points.withUnsafeBufferPointer { st_motion_summary($0.baseAddress,Int32(rows.count),&out) } == 0 ? out : nil
        }
        let rows=Array(shot.base.dropFirst()).filter { $0.time>=impact && $0.time<=impact+2 }
        let ball=summary(rows), launch=summary(rows.filter { $0.time <= (rows.first?.time ?? 0)+0.25 })
        let club=summary((shot.autoClub ?? []).filter { $0.time>=impact-0.25 && $0.time<=impact+0.05 })
        var lines=[ball.map { String(format:NSLocalizedString("editor.motionBall",comment:""),$0[0],hypot($0[1],$0[2]),$0[3]) } ?? NSLocalizedString("editor.motionMissing",comment:"")]
        if let launch, hypot(launch[1],launch[2])>=2 { lines.append(String(format:NSLocalizedString("editor.motionDirection",comment:""),atan2(-launch[2],launch[1])*180/Double.pi)) }
        lines.append(club.map { String(format:NSLocalizedString("editor.motionClub",comment:""),$0[3],Int($0[4])) } ?? NSLocalizedString("editor.motionClubMissing",comment:""))
        lines.append(NSLocalizedString("editor.motionCalibration",comment:"")); return lines.joined(separator:"\n")
    }
    func analyze() {
        guard !busy,!editingTimeline, let source, let ball = scene.shot.ball else { return }
        pause(); busy=true; output=nil
        let start=scene.shot.start, end=min(duration,start+15), width=scene.width, height=scene.height, id=scene.shot.id, rows=times
        let putt=scene.shot.trackingMode == "putt"
        status=NSLocalizedString("status.analyzing",comment:"")
        task=Task { defer { busy=false }; do {
            let data=try await Task.detached {
                let result=try TrackingSession(width:width,height:height,ball:CGPoint(x:ball.x*Double(width),y:ball.y*Double(height)),putt:putt)
                let folder=source.deletingLastPathComponent().appendingPathComponent("analysis-"+UUID().uuidString,isDirectory:true)
                let person=try? PersonAnalysis(folder); var personError=person == nil
                let generator=Self.generator(source); var last = -1.0, lastPerson = -1.0
                for time in rows where time >= max(0,start-1.2) && time <= end {
                    if last >= 0 && time-last < 1.0/30-0.000001 { continue }
                    try Task.checkCancellation(); let frame=try await generator.image(at:CMTime(seconds:time,preferredTimescale:60000))
                    let actual=frame.actualTime.seconds
                    if actual <= last { continue }
                    if actual >= start { try result.push(frame.image,time:actual) }
                    if let person,!personError,actual-lastPerson >= (abs(actual-start) <= 1.2 ? 1.0/30 : 1.0/15)-0.000001 {
                        let m=result.samples(camera:true).last
                        do { try person.push(frame.image,actual,EditPoint(m?.x ?? 0,m?.y ?? 0)) } catch { personError=true }
                        lastPerson=actual
                    }
                    last=actual
                }
                return (result.samples(),result.samples(camera:true),person?.hands ?? [],person?.clubs ?? [],person?.masks ?? [],personError)
            }.value
            if let i=scene.shots.firstIndex(where: { $0.id == id }) {
                checkpoint(); let samples=data.0, model=putt ? nil : NativeFlight.fit(data.0,ball)
                let impact=model?[3] ?? samples.first?.time ?? start
                scene.shots[i].base=samples; scene.shots[i].baseModel=model; scene.shots[i].camera=data.1; scene.shots[i].start=impact
                scene.shots[i].autoHands=data.2.filter { $0.time >= impact-1.2 && $0.time <= impact+1.2 }
                scene.shots[i].autoClub=data.3.filter { $0.time >= impact-1.2 && $0.time <= impact+1.2 }; scene.shots[i].masks=data.4
                if putt && samples.count >= 3 { scene.shots[i].flight=max(0.2,(samples.last?.time ?? impact)-impact) }
                if let model { scene.shots[i].flight=model[10]; scene.shots[i].apexFraction=min(0.62,max(0.35,NativeFlight.apex(model)/model[10])) }
                commitEdits()
            }
            status=String(format:NSLocalizedString("editor.analysisResult",comment:""),data.0.count,scene.shot.autoHands?.count ?? 0,scene.shot.autoClub?.count ?? 0)
            if data.0.count < 3 { status += "\n"+NSLocalizedString("error.track",comment:"") }
            if data.5 { status += "\n"+NSLocalizedString("editor.personFailed",comment:"") }
        } catch { status=error.localizedDescription } }
    }
    @discardableResult func previewPopup(_ index: Int) -> Bool {
        guard !busy,hasVideo,scene.shot.popups.indices.contains(index) else { return false }
        pause(); commitEdits()
        guard let frame=popupFrame(scene.shot,scene.shot.popups[index],times,frameIndex) else {
            status=NSLocalizedString("editor.popupOutside",comment:""); return false
        }
        seek(frame); status=NSLocalizedString("editor.popupPreviewing",comment:""); return true
    }
    func popupIsReachable(_ index: Int) -> Bool {
        scene.shot.popups.indices.contains(index) && popupFrame(scene.shot,scene.shot.popups[index],times,frameIndex) != nil
    }
    func saveToAlbum() {
        guard !busy,!editingTimeline,let output else { return }
        pause(); busy=true
        task=Task { defer { busy=false }; await storeInAlbum(output) }
    }
    private func storeInAlbum(_ url: URL) async {
        status=NSLocalizedString("editor.albumSaving",comment:"")
        do { try await PhotoAlbum.shared.save(url); status=NSLocalizedString("editor.albumSaved",comment:"") }
        catch { status=(error as? PhotoAlbumError)?.localizedDescription ?? NSLocalizedString("editor.albumFailed",comment:"") }
    }
    func export() {
        guard !busy,!editingTimeline, let source else { return }; pause(); persist(); busy = true; output = nil
        status = NSLocalizedString("status.exporting", comment: "")
        let snapshot = scene, seconds = duration
        task = Task { defer { busy = false }; do {
            output = try await Self.renderProject(source, snapshot, seconds)
            if let output { await storeInAlbum(output) }
        } catch { status = error.localizedDescription } }
    }
    func exportAll() {
        guard !busy, !projects.isEmpty else { return }; pause(); persist(); busy = true
        let queue = projects
        task = Task { defer { busy = false }; do {
            for (i, file) in queue.enumerated() {
                try await prepare(file)
                status = String(format: NSLocalizedString("editor.batchProgress", comment: ""), i + 1, queue.count)
                output = try await Self.renderProject(file, scene, duration)
                if let output { await storeInAlbum(output) }
            }
        } catch { status = error.localizedDescription } }
    }
    nonisolated private static func renderProject(_ source: URL, _ snapshot: EditorScene, _ duration: Double) async throws -> URL {
        let draw = EditorRenderer(snapshot)
        let destination = source.deletingLastPathComponent().appendingPathComponent("traced-\(UUID().uuidString).mp4")
        do {
        let original = AVURLAsset(url: source)
        let asset: AVAsset
        let intervals = slowIntervals(snapshot, duration)
        if snapshot.slowmo, let track = try await original.loadTracks(withMediaType: .video).first {
            let composition = AVMutableComposition()
            guard let video = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else { throw MobileError.exportFailed }
            try video.insertTimeRange(CMTimeRange(start: .zero, duration: CMTime(seconds: duration, preferredTimescale: 60000)), of: track, at: .zero)
            video.preferredTransform = try await track.load(.preferredTransform)
            for (a, b) in intervals.reversed() { video.scaleTimeRange(CMTimeRange(start: CMTime(seconds: a, preferredTimescale: 60000), duration: CMTime(seconds: b - a, preferredTimescale: 60000)), toDuration: CMTime(seconds: (b - a) * snapshot.slowFactor, preferredTimescale: 60000)) }
            asset = composition
        } else { asset = original }
        let composition = try await AVVideoComposition.videoComposition(with: asset, applyingCIFiltersWithHandler: { request in
            let time = snapshot.slowmo ? sourceTime(request.compositionTime.seconds, intervals, snapshot.slowFactor) : request.compositionTime.seconds
            let overlay = draw.overlay(time), extent = request.sourceImage.extent
            let image = CIImage(cgImage: overlay.cgImage!)
            let scaled = image.transformed(by: CGAffineTransform(scaleX: extent.width / image.extent.width, y: extent.height / image.extent.height)).transformed(by: CGAffineTransform(translationX: extent.minX, y: extent.minY))
            request.finish(with: scaled.composited(over: request.sourceImage), context: nil)
        })
        guard let exporter = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetHighestQuality) else { throw MobileError.exportFailed }
        exporter.videoComposition = composition; try await exporter.export(to: destination, as: .mp4)

            return destination
        } catch { try? FileManager.default.removeItem(at: destination); throw error }
    }
    nonisolated private static func generator(_ url: URL) -> AVAssetImageGenerator {
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url)); generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 640, height: 640); generator.requestedTimeToleranceBefore = .zero; generator.requestedTimeToleranceAfter = .zero; return generator
    }
    nonisolated private static func frame(_ url: URL, at seconds: Double) async throws -> (CGImage, Double) {
        let result = try await generator(url).image(at: CMTime(seconds: seconds, preferredTimescale: 60000)); return (result.image, result.actualTime.seconds)
    }
    nonisolated private static func frameTimes(_ url: URL) async throws -> [Double] {
        let asset = AVURLAsset(url: url)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else { throw MobileError.missingVideo }
        let reader = try AVAssetReader(asset: asset), output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
        output.alwaysCopiesSampleData = false; reader.add(output); guard reader.startReading() else { throw MobileError.invalidFrame }
        var times: [Double] = []
        while let sample = output.copyNextSampleBuffer() { let time = CMSampleBufferGetPresentationTimeStamp(sample).seconds; if time.isFinite { times.append(time) } }
        guard reader.status == .completed, !times.isEmpty else { throw reader.error ?? MobileError.invalidFrame }
        return Array(Set(times)).sorted()
    }
}
func slowIntervals(_ scene: EditorScene, _ duration: Double) -> [(Double, Double)] {
    var result: [(Double, Double)] = []
    let windows = scene.shots.filter { $0.ball != nil }.map { (max(0, $0.start - scene.slowBefore), min(duration, $0.start + scene.slowAfter)) }.filter { $0.1 > $0.0 }.sorted { $0.0 < $1.0 }
    for (a, b) in windows { if let last = result.last, a <= last.1 { result[result.count - 1] = (last.0, max(last.1, b)) } else { result.append((a, b)) } }
    return result
}
func sourceTime(_ output: Double, _ windows: [(Double, Double)], _ factor: Double) -> Double {
    var source = 0.0, clock = 0.0
    for (a, b) in windows { if output <= clock + a - source { return source + output - clock }; clock += a - source
        if output <= clock + (b - a) * factor { return a + (output - clock) / factor }; clock += (b - a) * factor; source = b }
    return source + output - clock
}

struct ShotEvent: Identifiable {
    let key: String, time: Double, frame: Int, inClip: Bool, estimated: Bool
    var id: String { key }
}

/// Match event markers to the source-frame slider, without changing the physical
/// event clock when tracer drawing speed or export slow motion changes.
func makeShotEvents(_ shot: EditShot, _ times: [Double], _ duration: Double) -> [ShotEvent] {
    guard shot.ball != nil,!times.isEmpty else { return [] }
    func event(_ key: String,_ time: Double,_ estimated: Bool = false) -> ShotEvent {
        var low=0,high=times.count
        while low < high { let mid=(low+high)/2; if times[mid] < time { low=mid+1 } else { high=mid } }
        var frame=min(low,times.count-1)
        if frame>0 && time-times[frame-1] < times[frame]-time { frame-=1 }
        return ShotEvent(key:key,time:time,frame:frame,inClip:time >= times[0]-0.000001 && time <= duration+0.000001,estimated:estimated)
    }
    var result=[event("ball",shot.start)]
    if shot.landing != nil || shot.manual || shot.baseModel != nil || (shot.trackingMode == "putt" && shot.base.count >= 2) {
        result.append(event("landing",shot.start+shot.flight,shot.timingLandingEstimated ?? (shot.landing == nil && shot.trackingMode != "putt")))
    }
    return result
}

/// Apply timing edits without changing automatic observations or base-model time.
func retimeShot(_ shot: inout EditShot,_ key: String,_ frame: Int,_ times: [Double]) -> Int? {
    guard shot.ball != nil,!times.isEmpty,["ball","landing"].contains(key) else { return nil }
    let known=shot.landing != nil || shot.manual || shot.baseModel != nil || shot.base.count>=2
    if key == "landing" && !known { return nil }
    let observed=shot.baseModel == nil && !shot.manual && shot.landing == nil && shot.base.count>=2
    let end=shot.start + (observed ? shot.base.last!.time-shot.base.first!.time : shot.flight)
    let lo=key == "ball" ? (known ? max(times[0],end-15) : times[0]) : shot.start+0.2
    let hi=key == "ball" ? (known ? min(times.last!,end-0.2) : times.last!) : min(times.last!,shot.start+15)
    func bound(_ target: Double,_ after: Bool) -> Int {
        var left=0,right=times.count
        while left<right { let mid=(left+right)/2; if times[mid]<target || (after && times[mid]==target) { left=mid+1 } else { right=mid } }
        return left
    }
    let first=bound(lo-0.000001,false),last=bound(hi+0.000001,true)-1
    guard first<times.count,last>=first else { return nil }
    let selected=min(max(frame,first),last), time=times[selected]
    if known {
        let estimated=shot.timingLandingEstimated ?? (shot.landing == nil && shot.trackingMode != "putt")
        let point=shot.endpoint; shot.landing=point; shot.timingLandingEstimated=estimated
    }
    if key == "ball" { shot.start=time; if known { shot.flight=min(15,max(0.2,end-time)) } }
    else { shot.flight=min(15,max(0.2,time-shot.start)) }
    return selected
}

private final class PhotoCreationState: @unchecked Sendable {
    private let lock=NSLock()
    private var made=false
    func set(_ value: Bool) { lock.lock(); made=value; lock.unlock() }
    func get() -> Bool { lock.lock(); defer { lock.unlock() }; return made }
}

enum PhotoAlbumError: LocalizedError {
    case denied, failed
    var errorDescription: String? { NSLocalizedString(self == .denied ? "editor.albumDenied" : "editor.albumFailed",comment:"") }
}

/// Add-only authorization never grants this app access to existing library items.
/// Remember successful output URLs to avoid repeated saves of the same completed file.
actor PhotoAlbum {
    static let shared=PhotoAlbum()
    private var pending: [URL:Task<Void,Error>]=[:]
    func save(_ url: URL) async throws {
        if let task=pending[url] { return try await task.value }
        if UserDefaults.standard.bool(forKey:"album:"+url.path) { return }
        let task=Task<Void,Error> {
            let authorization=await PHPhotoLibrary.requestAuthorization(for:.addOnly)
            guard authorization == .authorized || authorization == .limited else { throw PhotoAlbumError.denied }
            guard FileManager.default.fileExists(atPath:url.path) else { throw PhotoAlbumError.failed }
            let state=PhotoCreationState()
            try await PHPhotoLibrary.shared().performChanges {
                state.set(PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL:url) != nil)
            }
            guard state.get() else { throw PhotoAlbumError.failed }
            UserDefaults.standard.set(true,forKey:"album:"+url.path)
        }
        pending[url]=task
        do { try await task.value; pending[url]=nil }
        catch { pending[url]=nil; throw error }
    }
}
