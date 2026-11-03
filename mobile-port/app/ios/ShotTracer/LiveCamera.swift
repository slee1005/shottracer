import AVFoundation
import Combine
import CoreImage
import SwiftUI

/// Camera, tracker and writer share one serial queue. The displayed overlay and
/// recorded overlay come from the same source frame and TrackingSession raster.
final class LiveCamera: NSObject, ObservableObject, AVCaptureVideoDataOutputSampleBufferDelegate,
                        AVCaptureAudioDataOutputSampleBufferDelegate, @unchecked Sendable {
    @Published private(set) var image: UIImage?
    @Published private(set) var overlay: UIImage?
    @Published private(set) var selected: CGPoint?
    @Published private(set) var recording = false
    @Published private(set) var finishing = false
    @Published private(set) var soundEnabled = false
    @Published private(set) var status = NSLocalizedString("camera.preparing", comment: "")
    @Published private(set) var output: URL?

    private let queue = DispatchQueue(label: "shottracer.camera", qos: .userInitiated)
    private let session = AVCaptureSession()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let audioOutput = AVCaptureAudioDataOutput()
    private let context = CIContext()
    private let previewGate = DispatchSemaphore(value: 1)
    private var configured = false
    private var hasAudio = false
    private var active = false
    private var wantsSession = false
    @MainActor private var authorizing = false
    private var seed: CGPoint?
    private var wantsRecording = false
    private var ending = false
    private var writer: AVAssetWriter?
    private var videoInput: AVAssetWriterInput?
    private var audioInput: AVAssetWriterInput?
    private var adaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var tracker: TrackingSession?
    private var firstTime: CMTime?
    private var lastTime = -1.0
    private var destination: URL?
    private var emptyOverlay: UIImage?
    private var observers: [NSObjectProtocol] = []

    override init() {
        super.init()
        for name in [AVCaptureSession.wasInterruptedNotification, AVCaptureSession.runtimeErrorNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: session, queue: nil) { [weak self] _ in
                self?.stop()
                self?.message("camera.interrupted")
            })
        }
    }

    deinit { observers.forEach(NotificationCenter.default.removeObserver) }

    @MainActor func start() {
        guard !authorizing else { return }
        authorizing = true
        queue.async { self.wantsSession = true }
        Task {
            defer { authorizing = false }
            let camera = await AVCaptureDevice.requestAccess(for: .video)
            guard camera else { message("camera.denied"); return }
            let audio = await AVCaptureDevice.requestAccess(for: .audio)
            queue.async { [self] in
                guard wantsSession else { return }
                do {
                    if !configured { try configure(audio: audio) }
                    active = true
                    if !session.isRunning { session.startRunning() }
                    message("camera.hint")
                } catch { message("camera.unavailable") }
            }
        }
    }

    private func configure(audio: Bool) throws {
        session.beginConfiguration()
        var ready = false
        defer {
            if !ready {
                session.inputs.forEach { session.removeInput($0) }
                session.outputs.forEach { session.removeOutput($0) }
                hasAudio = false
            }
            session.commitConfiguration()
        }
        guard session.canSetSessionPreset(.vga640x480),
              let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            throw MobileError.missingVideo
        }
        session.sessionPreset = .vga640x480
        let input = try AVCaptureDeviceInput(device: camera)
        guard session.canAddInput(input), session.canAddOutput(videoOutput) else { throw MobileError.missingVideo }
        session.addInput(input)
        videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(self, queue: queue)
        session.addOutput(videoOutput)
        guard let connection = videoOutput.connection(with: .video), connection.isVideoRotationAngleSupported(90) else {
            throw MobileError.missingVideo
        }
        connection.videoRotationAngle = 90
        if connection.isVideoStabilizationSupported { connection.preferredVideoStabilizationMode = .off }
        try camera.lockForConfiguration()
        if camera.activeFormat.videoSupportedFrameRateRanges.contains(where: { $0.minFrameRate <= 30 && $0.maxFrameRate >= 30 }) {
            camera.activeVideoMinFrameDuration = CMTime(value: 1, timescale: 30)
            camera.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: 30)
        }
        camera.unlockForConfiguration()
        if audio, let microphone = AVCaptureDevice.default(for: .audio),
           let micInput = try? AVCaptureDeviceInput(device: microphone),
           session.canAddInput(micInput), session.canAddOutput(audioOutput) {
            session.addInput(micInput)
            audioOutput.setSampleBufferDelegate(self, queue: queue)
            session.addOutput(audioOutput)
            hasAudio = true
        }
        configured = true
        ready = true
        DispatchQueue.main.async { self.soundEnabled = self.hasAudio }
    }

    @MainActor func select(_ point: CGPoint) {
        guard !recording, !finishing, let image,
              point.x * image.size.width >= 3, point.y * image.size.height >= 3,
              point.x * image.size.width < image.size.width - 3,
              point.y * image.size.height < image.size.height - 3 else { return }
        selected = point
        queue.async { self.seed = point }
        message("camera.ready")
    }

    @MainActor func record() {
        guard selected != nil, image != nil, !recording, !finishing else { return }
        recording = true; output = nil
        message("camera.recording")
        queue.async {
            if self.active { self.wantsRecording = true }
            else {
                DispatchQueue.main.async { self.recording = false }
                self.message("camera.interrupted")
            }
        }
    }

    func stopRecording() { queue.async { self.finish() } }

    func stop() {
        queue.async { [self] in
            active = false
            wantsSession = false
            finish()
            if session.isRunning { session.stopRunning() }
            DispatchQueue.main.async {
                self.selected = nil; self.image = nil; self.overlay = nil
            }
        }
    }

    private func message(_ key: String) {
        DispatchQueue.main.async { self.status = NSLocalizedString(key, comment: "") }
    }

    private func begin(frame: CGImage, time: CMTime) throws {
        guard let seed else { throw MobileError.invalidBall }
        tracker = try TrackingSession(width: frame.width, height: frame.height,
                                      ball: CGPoint(x: seed.x * CGFloat(frame.width), y: seed.y * CGFloat(frame.height)))
        let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("captures", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var localFolder = folder
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try localFolder.setResourceValues(values)
        let url = folder.appendingPathComponent("shot-\(UUID().uuidString).mp4")
        destination = url
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let video = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: frame.width, AVVideoHeightKey: frame.height
        ])
        video.expectsMediaDataInRealTime = true
        guard writer.canAdd(video) else { throw MobileError.exportFailed }
        writer.add(video)
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: video, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: frame.width, kCVPixelBufferHeightKey as String: frame.height,
            kCVPixelBufferCGImageCompatibilityKey as String: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey as String: true
        ])
        if hasAudio {
            let audio = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100, AVNumberOfChannelsKey: 1
            ])
            audio.expectsMediaDataInRealTime = true
            guard writer.canAdd(audio) else { throw MobileError.exportFailed }
            writer.add(audio); audioInput = audio
        }
        guard writer.startWriting() else { throw writer.error ?? MobileError.exportFailed }
        writer.startSession(atSourceTime: time)
        self.writer = writer; videoInput = video; self.adaptor = adaptor
        firstTime = time; lastTime = -1
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard active else { return }
        let timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        if output === audioOutput {
            guard !ending, let writer, writer.status == .writing, let firstTime,
                  CMTimeCompare(timestamp, firstTime) >= 0, let input = audioInput,
                  input.isReadyForMoreMediaData else { return }
            if !input.append(sampleBuffer) { finish(failed: true) }
            return
        }
        guard let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        autoreleasepool {
            let source = CIImage(cvPixelBuffer: buffer)
            guard let frame = context.createCGImage(source, from: source.extent) else { return }
            do {
                if wantsRecording, writer == nil, !ending { try begin(frame: frame, time: timestamp) }
                let image = UIImage(cgImage: frame)
                let overlay: UIImage
                if let tracker, let firstTime, !ending, wantsRecording {
                    let seconds = CMTimeSubtract(timestamp, firstTime).seconds
                    guard seconds.isFinite, seconds > lastTime else { return }
                    lastTime = seconds
                    try tracker.push(frame, time: seconds)
                    overlay = tracker.overlay(at: seconds)
                    if let videoInput, videoInput.isReadyForMoreMediaData, let adaptor, let pool = adaptor.pixelBufferPool {
                        var destinationBuffer: CVPixelBuffer?
                        guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &destinationBuffer) == kCVReturnSuccess,
                              let destinationBuffer else { throw MobileError.exportFailed }
                        let format = UIGraphicsImageRendererFormat(); format.scale = 1
                        let composite = UIGraphicsImageRenderer(size: image.size, format: format).image { _ in
                            image.draw(at: .zero); overlay.draw(at: .zero)
                        }
                        context.render(CIImage(cgImage: composite.cgImage!), to: destinationBuffer)
                        guard adaptor.append(destinationBuffer, withPresentationTime: timestamp) else {
                            throw MobileError.exportFailed
                        }
                    }
                    if seconds >= 15 { finish() }
                } else {
                    if emptyOverlay?.size != image.size {
                        emptyOverlay = TrackingSession.watermarkOnly(width: frame.width, height: frame.height)
                    }
                    overlay = emptyOverlay!
                }
                // Never enqueue an unbounded number of UI updates if capture is faster than display.
                if previewGate.wait(timeout: .now()) == .success {
                    DispatchQueue.main.async {
                        self.image = image; self.overlay = overlay
                        self.previewGate.signal()
                    }
                }
            } catch { finish(failed: true) }
        }
    }

    private func finish(failed: Bool = false) {
        guard !ending else { return }
        wantsRecording = false
        guard let writer else {
            tracker = nil
            if let destination { try? FileManager.default.removeItem(at: destination) }
            destination = nil; audioInput = nil
            DispatchQueue.main.async { self.recording = false; self.finishing = false }
            if failed { message("camera.failed") }
            return
        }
        ending = true
        let url = destination, count = tracker?.count ?? 0
        DispatchQueue.main.async { self.recording = false; self.finishing = true }
        if failed { writer.cancelWriting() }
        else { videoInput?.markAsFinished(); audioInput?.markAsFinished() }
        let complete: @Sendable () -> Void = { [self] in
            queue.async { [self] in
                let saved = !failed && self.writer?.status == .completed
                if !saved, let url { try? FileManager.default.removeItem(at: url) }
                self.writer = nil; videoInput = nil; audioInput = nil; adaptor = nil
                tracker = nil; firstTime = nil; destination = nil; ending = false
                DispatchQueue.main.async {
                    self.finishing = false
                    self.output = saved ? url : nil
                    self.status = NSLocalizedString(saved ? (count >= 4 ? "camera.saved" : "camera.noTrack") : "camera.failed", comment: "")
                }
            }
        }
        if failed { complete() } else { writer.finishWriting(completionHandler: complete) }
    }
}

struct LiveCameraView: View {
    @StateObject private var camera = LiveCamera()
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    Text("camera.instructions").font(.footnote)
                    if let image = camera.image {
                        GeometryReader { geometry in
                            ZStack(alignment: .topLeading) {
                                Image(uiImage: image).resizable()
                                if let overlay = camera.overlay { Image(uiImage: overlay).resizable() }
                                if let selected = camera.selected {
                                    Circle().stroke(.yellow, lineWidth: 2).frame(width: 18, height: 18)
                                        .position(x: selected.x * geometry.size.width, y: selected.y * geometry.size.height)
                                }
                            }
                            .contentShape(Rectangle())
                            .gesture(SpatialTapGesture().onEnded { tap in
                                camera.select(CGPoint(x: tap.location.x / geometry.size.width,
                                                      y: tap.location.y / geometry.size.height))
                            })
                            .accessibilityLabel(Text("camera.preview"))
                        }.aspectRatio(image.size, contentMode: .fit)
                    } else { ProgressView().padding(40) }
                    Text(camera.status).font(.callout)
                    Text(camera.soundEnabled ? "camera.audioOn" : "camera.audioOff").font(.footnote).foregroundStyle(.secondary)
                    if camera.recording {
                        Button("camera.stop") { camera.stopRecording() }.buttonStyle(.borderedProminent)
                    } else {
                        Button("camera.record") { camera.record() }.buttonStyle(.borderedProminent)
                            .disabled(camera.selected == nil || camera.image == nil || camera.finishing)
                    }
                    if camera.finishing { ProgressView() }
                    if let output = camera.output { ShareLink("action.share", item: output) }
                }.padding()
            }
            .navigationTitle(Text("camera.title"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("camera.close") { dismiss() }.disabled(camera.recording || camera.finishing)
                }
            }
        }
        .interactiveDismissDisabled(camera.recording || camera.finishing)
        .onAppear { camera.start() }
        .onDisappear { camera.stop() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { camera.start() }
            else if phase == .background { camera.stop() }
        }
    }
}
