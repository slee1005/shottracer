import AVFoundation
import Combine
import CoreImage
import CoreTransferable
import PhotosUI
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
    @Published var ball: CGPoint?
    @Published var time = 0.0
    @Published var duration = 0.0
    @Published var busy = false
    @Published var status = NSLocalizedString("hint.import", comment: "")
    @Published var output: URL?
    @Published private(set) var canExport = false
    private var source: URL?
    private var session: TrackingSession?
    private var task: Task<Void, Never>?
    private var previewTask: Task<Void, Never>?
    private var previewGeneration = 0
    private var seedTime = 0.0
    private var displayedTime = 0.0

    func importVideo(_ incoming: URL) {
        guard beginImport() else { return }
        task = Task {
            defer { busy = false }
            do { try await loadVideo(incoming) }
            catch { status = error.localizedDescription }
        }
    }

    func importPhoto(_ item: PhotosPickerItem) {
        guard beginImport() else { return }
        task = Task {
            defer { busy = false }
            do {
                guard let movie = try await item.loadTransferable(type: PickedMovie.self) else {
                    throw MobileError.missingVideo
                }
                defer { try? FileManager.default.removeItem(at: movie.url.deletingLastPathComponent()) }
                try await loadVideo(movie.url)
            } catch { status = error.localizedDescription }
        }
    }

    private func beginImport() -> Bool {
        guard !busy else { return false }
        previewTask?.cancel(); previewGeneration += 1
        busy = true
        status = NSLocalizedString("status.importing", comment: "")
        return true
    }

    private func loadVideo(_ incoming: URL) async throws {
        let accessed = incoming.startAccessingSecurityScopedResource()
        defer { if accessed { incoming.stopAccessingSecurityScopedResource() } }
        let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var imported = false
        defer { if !imported { try? FileManager.default.removeItem(at: folder) } }
        // Imported originals and derived videos stay out of automatic cloud backups.
        var privateFolder = folder
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try privateFolder.setResourceValues(values)
        let local = folder.appendingPathComponent("source.\(incoming.pathExtension.isEmpty ? "mov" : incoming.pathExtension)")
        // File providers may return temporary URLs. Own a durable local copy.
        try await Task.detached { try FileManager.default.copyItem(at: incoming, to: local) }.value
        let asset = AVURLAsset(url: local)
        let seconds = try await asset.load(.duration).seconds
        guard seconds.isFinite, seconds > 0 else { throw MobileError.missingVideo }
        imported = true
        source = local; duration = seconds; time = 0
        session = nil; ball = nil; output = nil; overlay = nil; image = nil; canExport = false
        status = NSLocalizedString("hint.ball", comment: "")
        busy = false
        refreshFrame()
    }

    func refreshFrame() {
        guard let source, !busy else { return }
        previewGeneration += 1
        let generation = previewGeneration, requested = time
        previewTask?.cancel()
        previewTask = Task {
            do {
                let (frame, actual) = try await Self.frame(source, at: requested)
                guard !Task.isCancelled, generation == previewGeneration else { return }
                image = UIImage(cgImage: frame)
                displayedTime = actual
                overlay = session?.overlay(at: actual) ?? TrackingSession.watermarkOnly(width: frame.width, height: frame.height)
            } catch { if generation == previewGeneration { status = error.localizedDescription } }
        }
    }

    func selectBall(_ normalized: CGPoint) {
        guard !busy, let image, let cg = image.cgImage else { return }
        let x = normalized.x * CGFloat(cg.width), y = normalized.y * CGFloat(cg.height)
        guard x >= 3, y >= 3, x < CGFloat(cg.width - 3), y < CGFloat(cg.height - 3) else { return }
        ball = CGPoint(x: x, y: y); seedTime = displayedTime
        session = nil; overlay = TrackingSession.watermarkOnly(width: cg.width, height: cg.height); output = nil; canExport = false
        status = NSLocalizedString("hint.analyze", comment: "")
    }

    func analyze() {
        guard !busy, let source, let ball, let cg = image?.cgImage else { return }
        busy = true; output = nil
        let start = seedTime, end = min(duration, seedTime + 15)
        let width = cg.width, height = cg.height
        status = NSLocalizedString("status.analyzing", comment: "")
        task = Task {
            defer { busy = false }
            do {
                let result = try await Task.detached {
                    let session = try TrackingSession(width: width, height: height, ball: ball)
                    let generator = Self.generator(source)
                    var last = -1.0
                    for index in 0...Int((end - start) * 30) {
                        try Task.checkCancellation()
                        let requested = CMTime(seconds: start + Double(index) / 30, preferredTimescale: 60000)
                        let result = try await generator.image(at: requested)
                        if result.actualTime.seconds > last {
                            try session.push(result.image, time: result.actualTime.seconds); last = result.actualTime.seconds
                        }
                        if index % 30 == 0 {
                            await self.progress(Double(index) / max(1, (end - start) * 30))
                        }
                    }
                    guard session.count >= 4 else { throw MobileError.noTrack }
                    return session
                }.value
                try Task.checkCancellation()
                session = result
                canExport = true
                status = String(format: NSLocalizedString("status.tracked", comment: ""), result.count)
                overlay = result.overlay(at: time)
            } catch { status = error.localizedDescription }
        }
    }

    private func progress(_ value: Double) {
        status = String(format: NSLocalizedString("status.progress", comment: ""), Int(value * 100))
    }

    func export() {
        guard !busy, let source, let session else { return }
        busy = true; output = nil
        status = NSLocalizedString("status.exporting", comment: "")
        task = Task {
            defer { busy = false }
            let destination = source.deletingLastPathComponent().appendingPathComponent("traced-\(UUID().uuidString).mp4")
            do {
                let asset = AVURLAsset(url: source)
                let composition = try await AVVideoComposition.videoComposition(with: asset, applyingCIFiltersWithHandler: { request in
                    let overlay = session.overlay(at: request.compositionTime.seconds)
                    let extent = request.sourceImage.extent
                    let image = CIImage(cgImage: overlay.cgImage!)
                    let scaled = image.transformed(by: CGAffineTransform(scaleX: extent.width / image.extent.width,
                                                                        y: extent.height / image.extent.height))
                        .transformed(by: CGAffineTransform(translationX: extent.minX, y: extent.minY))
                    request.finish(with: scaled.composited(over: request.sourceImage), context: nil)
                })
                guard let exporter = AVAssetExportSession(asset: asset, presetName: AVAssetExportPreset1920x1080) else {
                    throw MobileError.exportFailed
                }
                exporter.videoComposition = composition
                try await exporter.export(to: destination, as: .mp4)
                output = destination
                status = NSLocalizedString("status.saved", comment: "")
            } catch {
                try? FileManager.default.removeItem(at: destination)
                status = error.localizedDescription
            }
        }
    }

    nonisolated private static func generator(_ url: URL) -> AVAssetImageGenerator {
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 640, height: 640)
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero
        return generator
    }

    nonisolated private static func frame(_ url: URL, at seconds: Double) async throws -> (CGImage, Double) {
        let generator = generator(url)
        let result = try await generator.image(at: CMTime(seconds: seconds, preferredTimescale: 60000))
        return (result.image, result.actualTime.seconds)
    }
}
