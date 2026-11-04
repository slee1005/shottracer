import Foundation
import UIKit
import Vision
import CoreImage

/// Owns the C handle. Export callbacks and preview can run on different queues.
final class TrackingSession: @unchecked Sendable {
    let width: Int
    let height: Int
    private let handle: OpaquePointer
    private let lock = NSLock()

    init(width: Int, height: Int, ball: CGPoint, putt: Bool = false) throws {
        guard let handle = st_create(Int32(width), Int32(height), ball.x, ball.y) else {
            throw MobileError.invalidBall
        }
        self.width = width
        self.height = height
        self.handle = handle
        _ = st_set_mode(handle, putt ? 1 : 0)
    }

    deinit { st_destroy(handle) }

    func push(_ image: CGImage, time: Double) throws {
        guard image.width == width, image.height == height else { throw MobileError.invalidFrame }
        var bytes = [UInt8](repeating: 0, count: width * height)
        try bytes.withUnsafeMutableBytes { raw in
            guard let context = CGContext(data: raw.baseAddress, width: width, height: height,
                                          bitsPerComponent: 8, bytesPerRow: width,
                                          space: CGColorSpaceCreateDeviceGray(), bitmapInfo: 0) else {
                throw MobileError.invalidFrame
            }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            lock.lock()
            let status = st_push(handle, raw.baseAddress?.assumingMemoryBound(to: UInt8.self), Int32(width), time)
            lock.unlock()
            if status < 0 { throw MobileError.invalidFrame }
        }
    }

    var count: Int {
        lock.lock(); defer { lock.unlock() }
        return Int(st_count(handle))
    }

    /// Preview and AVFoundation export both consume this exact raster, watermark included.
    func overlay(at time: Double) -> UIImage {
        lock.lock()
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        pixels.withUnsafeMutableBufferPointer { buffer in
            _ = st_render(handle, time, buffer.baseAddress, Int32(width * 4))
        }
        lock.unlock()
        return Self.raster(width: width, height: height, pixels: pixels)
    }

    static func watermarkOnly(width: Int, height: Int) -> UIImage {
        raster(width: width, height: height, pixels: [UInt8](repeating: 0, count: width * height * 4))
    }

    private static func raster(width: Int, height: Int, pixels: [UInt8]) -> UIImage {
        let data = Data(pixels) as CFData
        let provider = CGDataProvider(data: data)!
        let cg = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
                         bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                         bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                         provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)!
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { _ in
            UIImage(cgImage: cg).draw(at: .zero)
            let font = UIFont.boldSystemFont(ofSize: max(10, CGFloat(height) * 0.032))
            let text = NSString(string: "Create by AJC_shottracer")
            let attributes: [NSAttributedString.Key: Any] = [
                .font: font, .foregroundColor: UIColor.white,
                .strokeColor: UIColor.black, .strokeWidth: -2
            ]
            let size = text.size(withAttributes: attributes)
            text.draw(at: CGPoint(x: (CGFloat(width) - size.width) / 2,
                                  y: CGFloat(height) * 0.975 - size.height), withAttributes: attributes)
        }
    }
}

enum MobileError: Error, LocalizedError {
    case invalidBall, invalidFrame, missingVideo, noTrack, exportFailed
    var errorDescription: String? {
        let key: String
        switch self {
        case .invalidBall: key = "error.ball"
        case .invalidFrame: key = "error.frame"
        case .missingVideo: key = "error.video"
        case .noTrack: key = "error.track"
        case .exportFailed: key = "error.export"
        }
        return NSLocalizedString(key, comment: "")
    }
}

struct EditPoint: Codable, Equatable, Sendable {
    var x: Double; var y: Double
    init(_ x: Double, _ y: Double) { self.x = x; self.y = y }
    init(from decoder: Decoder) throws { var c = try decoder.unkeyedContainer(); x = try c.decode(Double.self); y = try c.decode(Double.self) }
    func encode(to encoder: Encoder) throws { var c = encoder.unkeyedContainer(); try c.encode(x); try c.encode(y) }
}
struct EditSample: Codable, Sendable {
    var time: Double; var x: Double; var y: Double
    init(_ time: Double, _ x: Double, _ y: Double) { self.time = time; self.x = x; self.y = y }
    init(from decoder: Decoder) throws { var c = try decoder.unkeyedContainer(); time = try c.decode(Double.self); x = try c.decode(Double.self); y = try c.decode(Double.self) }
    func encode(to encoder: Encoder) throws { var c = encoder.unkeyedContainer(); try c.encode(time); try c.encode(x); try c.encode(y) }
}
func interpolated(_ rows: [EditSample], _ time: Double) -> EditPoint {
    guard let first = rows.first, let last = rows.last else { return EditPoint(0, 0) }
    if time <= first.time { return EditPoint(first.x, first.y) }
    if time >= last.time { return EditPoint(last.x, last.y) }
    var low = 0, high = rows.count - 1
    while high - low > 1 { let mid = (low + high) / 2; if rows[mid].time < time { low = mid } else { high = mid } }
    let a = rows[low], b = rows[high], f = (time - a.time) / max(1e-9, b.time - a.time)
    return EditPoint(a.x + f * (b.x - a.x), a.y + f * (b.y - a.y))
}
struct EditPopup: Codable, Identifiable, Sendable {
    var kind = "text", label = "", value = "", anchor = "landing", hide = "after_shot"
    var delay = 0.0, duration = 3.0
    var id: String { label + kind + value }
}
struct EditShape: Codable, Sendable { var kind: String; var a: EditPoint; var b: EditPoint; var color = "#FFD400"; var width = 1.0 }
struct EditShot: Codable, Identifiable, Sendable {
    var id = String(UUID().uuidString.prefix(6)), start = 0.0
    var ball: EditPoint?, landing: EditPoint?, apex: EditPoint?
    var flight = 4.0, apexFraction = 0.5, speed = 1.0, color = "#FF2D2D", manual = false
    var base: [EditSample] = [], camera: [EditSample] = [], swing: [EditSample] = []
    var timingLandingEstimated: Bool?
    var trackingMode: String?
    var autoHands: [EditSample]?, autoClub: [EditSample]?, masks: [PersonMask]?
    var baseModel: [Double]?
    var popups: [EditPopup] = []
    var edited: Bool { baseModel != nil || manual || landing != nil || apex != nil }
    var model: [Double]? {
        guard trackingMode != "putt", let baseModel, baseModel.count == 16, !manual, apex == nil else { return nil }
        guard let result = landing == nil && abs(flight - baseModel[10]) < 1e-8 ? baseModel : NativeFlight.refit(baseModel, base, landing, flight) else { return nil }
        return abs(apexFraction - NativeFlight.apex(baseModel) / baseModel[10]) > 0.001 ? NativeFlight.reshape(result, base, flight * apexFraction) ?? result : result
    }
    var endpoint: EditPoint { landing ?? model.flatMap { NativeFlight.at($0, flight) } ?? base.last.map { EditPoint($0.x, $0.y) } ?? EditPoint(0.75, 0.65) }
    var peak: EditPoint { if trackingMode == "putt" { return apex ?? ground(flight*0.5) }; let a = ball ?? EditPoint(0.2, 0.8), b = endpoint; return apex ?? model.flatMap { NativeFlight.at($0, flight * apexFraction) } ?? EditPoint((a.x + b.x) / 2, min(a.y, b.y) - 0.25) }
    var hasGroundPath: Bool { ball != nil && (base.count >= 2 || landing != nil) }
    func ground(_ time: Double) -> EditPoint {
        let p=unbentGround(time)
        guard let middle=apex else { return p }
        let reference=unbentGround(flight*0.5), f=min(1,max(0,time/max(0.01,flight))), weight=4*f*(1-f)
        // Ground-plane bend only: never change the endpoints or automatic observations.
        return EditPoint(p.x+(middle.x-reference.x)*weight,p.y+(middle.y-reference.y)*weight)
    }
    private func unbentGround(_ time: Double) -> EditPoint {
        let f = min(1,max(0,time/max(0.01,flight)))
        // Manual putts need only a start and destination, not tracker samples.
        if base.count < 2 {
            let a = ball ?? EditPoint(0,0), b = landing ?? a
            return EditPoint(a.x+(b.x-a.x)*f,a.y+(b.y-a.y)*f)
        }
        let origin=base.first?.time ?? start
        let span = max(0.01, (base.last?.time ?? origin)-origin)
        let p = interpolated(base,origin+f*span), end = base.last
        let ease = pow(min(1,max(0,(f-0.6)/0.4)),2)
        return EditPoint(p.x + ((landing?.x ?? end?.x ?? p.x)-(end?.x ?? p.x))*ease,
                         p.y + ((landing?.y ?? end?.y ?? p.y)-(end?.y ?? p.y))*ease)
    }
    func curve(_ seconds: Double) -> EditPoint {
        let a = ball ?? EditPoint(0.2, 0.8), b = peak, c = endpoint
        let p = [a.x, a.y, b.x, b.y, c.x, c.y]; var x = 0.0, y = 0.0
        _ = st_curve_at(p, flight, apexFraction, speed, seconds, &x, &y)
        return EditPoint(x, y)
    }
}
struct EditorScene: Codable, Sendable {
    var version = 1, width = 640, height = 480, selected = 0
    var shots = [EditShot()], shapes: [EditShape] = []
    var preset = "classic", lineWidth = 1.0, glow = 1.0, hold = 1.0
    var head = true, marker = true, swingEnabled = true
    var personOcclusion: Bool?, autoSwing: String?
    var logo: String?, logoSize = 0.12, logoOpacity = 0.85, logoCorner = "bottom-right"
    var slowmo = false, slowFactor = 3.0, slowBefore = 0.4, slowAfter = 1.2
    var shot: EditShot { get { shots[min(max(0, selected), shots.count - 1)] } set { shots[min(max(0, selected), shots.count - 1)] = newValue } }
}

extension TrackingSession {
    func samples(camera: Bool = false) -> [EditSample] {
        lock.lock(); defer { lock.unlock() }
        let n = camera ? st_camera_count(handle) : st_count(handle)
        return (0..<n).map { index in
            var p = STPoint()
            if camera { _ = st_camera_point(handle, index, &p) } else { _ = st_point(handle, index, &p) }
            return EditSample(p.time, p.x / Double(width), p.y / Double(height))
        }
    }
}

/// Native text composition differs between platforms; preview and export on each
/// platform always consume this exact scene raster, never editor-only drawing.
final class EditorRenderer: @unchecked Sendable {
    let scene: EditorScene
    private let logo: UIImage?
    private let maskCache = NSCache<NSString, UIImage>()
    private let models: [String: [Double]]
    init(_ scene: EditorScene) { self.scene = scene; logo = scene.logo.flatMap { UIImage(contentsOfFile: $0) }; models = Dictionary(uniqueKeysWithValues: scene.shots.compactMap { s in s.model.map { (s.id, $0) } }) }
    func overlay(_ time: Double) -> UIImage {
        let w = CGFloat(scene.width), h = CGFloat(scene.height)
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: format).image { output in
            let ctx = output.cgContext
            func color(_ hex: String) -> UIColor {
                let raw = UInt32(hex.replacingOccurrences(of: "#", with: ""), radix: 16) ?? 0xff2d2d
                return UIColor(red: CGFloat(raw >> 16 & 255) / 255, green: CGFloat(raw >> 8 & 255) / 255, blue: CGFloat(raw & 255) / 255, alpha: 1)
            }
            func pos(_ p: EditPoint, _ motion: EditPoint = EditPoint(0, 0)) -> CGPoint { CGPoint(x: (p.x + motion.x) * w, y: (p.y + motion.y) * h) }
            ctx.setLineCap(.round); ctx.setLineJoin(.round)
            var occupied: [CGRect]=[]
            for s in scene.shots {
                func curve(_ t: Double) -> EditPoint { if s.trackingMode == "putt" || (models[s.id] == nil && !s.manual && s.apex == nil && !s.base.isEmpty) { return s.ground(t) }; return models[s.id].flatMap { NativeFlight.at($0, t) } ?? s.curve(t / s.speed) }
                let motion = interpolated(s.camera, time)
                let duration = (s.edited || s.trackingMode == "putt") ? s.flight : max(0.01, (s.base.last?.time ?? s.start) - s.start)
                let elapsed = (time - s.start) * s.speed
                var points: [EditPoint] = []
                if s.ball != nil && (s.trackingMode != "putt" || s.hasGroundPath) && time >= s.start && (scene.hold < 0 || time <= s.start + duration / s.speed + scene.hold) {
                    let from = scene.preset == "comet" ? max(0, elapsed - 0.7) : 0
                    if s.edited || s.trackingMode == "putt" {
                        for i in 0...128 { let t = duration * Double(i) / 128; if t >= from && t <= min(duration, elapsed) { points.append(curve(t)) } }
                        if elapsed >= 0 { points.append(curve(min(duration, elapsed))) }
                    } else if !s.base.isEmpty {
                        points = s.base.filter { $0.time - s.start >= from && $0.time - s.start <= elapsed }.map { EditPoint($0.x, $0.y) }
                        if elapsed >= 0 { points.append(interpolated(s.base, s.start + min(duration, elapsed))) }
                    }
                }
                ctx.beginTransparencyLayer(auxiliaryInfo: nil)
                if points.count >= 2 {
                    let path = CGMutablePath(); path.move(to: pos(points[0], motion)); points.dropFirst().forEach { path.addLine(to: pos($0, motion)) }
                    let width = max(1.5, h / 240) * scene.lineWidth
                    if scene.preset != "minimal" && scene.glow > 0 {
                        ctx.saveGState(); ctx.setShadow(offset: .zero, blur: width * scene.glow * (scene.preset == "neon" ? 3 : 1.5), color: color(s.color).cgColor)
                        ctx.addPath(path); ctx.setStrokeColor(color(s.color).cgColor); ctx.setLineWidth(width * 1.5); ctx.strokePath(); ctx.restoreGState()
                    }
                    ctx.addPath(path); ctx.setStrokeColor(UIColor.black.cgColor); ctx.setLineWidth(width + 2); ctx.strokePath()
                    ctx.addPath(path); ctx.setStrokeColor(color(s.color).cgColor); ctx.setLineWidth(width); ctx.strokePath()
                    if scene.preset != "minimal" { ctx.addPath(path); ctx.setStrokeColor((scene.preset == "neon" ? color(s.color) : .white).cgColor); ctx.setLineWidth(width * 0.45); ctx.strokePath() }
                    let p = pos(points.last!, motion)
                    if scene.head { ctx.setFillColor(UIColor.white.cgColor); ctx.fillEllipse(in: CGRect(x: p.x - width, y: p.y - width, width: 2 * width, height: 2 * width)) }
                    if scene.marker && elapsed >= duration { ctx.setStrokeColor(color(s.color).cgColor); ctx.setLineWidth(2); let r = h * 0.02; ctx.strokeEllipse(in: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r)) }
                }
                let swing = s.swing.filter { $0.time <= time }
                if scene.swingEnabled && swing.count > 1 {
                    let path = CGMutablePath(); path.move(to: pos(EditPoint(swing[0].x, swing[0].y), motion))
                    swing.dropFirst().forEach { path.addLine(to: pos(EditPoint($0.x, $0.y), motion)) }
                    ctx.addPath(path); ctx.setStrokeColor(UIColor.cyan.cgColor); ctx.setLineWidth(max(2, h / 240)); ctx.strokePath()
                }
                if scene.swingEnabled {
                    for (rows,key,ink) in [(s.autoHands ?? [],"hands",UIColor(red: 0.18,green: 0.83,blue: 0.75,alpha: 1)),(s.autoClub ?? [],"club",UIColor(red: 0.98,green: 0.75,blue: 0.14,alpha: 1))] {
                        guard (scene.autoSwing ?? "both") == "both" || scene.autoSwing == key else { continue }
                        let path = CGMutablePath(); var last: EditSample?
                        for p in rows where p.time <= time && p.time >= time-2 {
                            let q = pos(EditPoint(p.x,p.y),motion)
                            if last == nil || p.time-last!.time > 0.18 { path.move(to:q) } else { path.addLine(to:q) }; last=p
                        }
                        ctx.addPath(path); ctx.setStrokeColor(ink.cgColor); ctx.setLineWidth(max(1.5,h/240)); ctx.strokePath()
                    }
                }
                if scene.personOcclusion != false, let m = s.masks?.min(by: { abs($0.time-time) < abs($1.time-time) }), abs(m.time-time) <= 0.11 {
                    if let mask = maskCache.object(forKey:m.path as NSString) ?? UIImage(contentsOfFile:m.path) {
                        maskCache.setObject(mask,forKey:m.path as NSString); maskCache.countLimit=6
                        ctx.saveGState(); mask.draw(in:CGRect(x:0,y:0,width:w,height:h),blendMode:.destinationOut,alpha:1); ctx.restoreGState()
                    }
                }
                ctx.endTransparencyLayer()
                for p in s.popups {
                    guard let window=popupWindow(s,p),window.contains(time) else { continue }
                    let at: CGPoint
                    switch p.anchor {
                    case "start": at = pos(s.ball ?? EditPoint(0.1, 0.8), motion)
                    case "apex": at = pos(s.peak, motion)
                    case "landing": at = pos(s.endpoint, motion)
                    case "top-right": at = CGPoint(x: w * 0.72, y: h * 0.08)
                    case "bottom-left": at = CGPoint(x: w * 0.04, y: h * 0.8)
                    case "bottom-right": at = CGPoint(x: w * 0.72, y: h * 0.8)
                    default: at = CGPoint(x: w * 0.04, y: h * 0.08)
                    }
                    let value = p.kind == "hangtime" && p.value.isEmpty ? String(format: "%.2f s", s.flight) : p.value
                    if p.label.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty && value.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty { continue }
                    let labelFont=UIFont.boldSystemFont(ofSize:max(10,h*0.025)),valueFont=UIFont.boldSystemFont(ofSize:max(15,h*0.044))
                    let labelWidth=(p.label as NSString).size(withAttributes:[.font:labelFont]).width
                    let valueWidth=(value as NSString).size(withAttributes:[.font:valueFont]).width
                    let bw=min(w*0.9,max(labelWidth,valueWidth)+24),bh=max(40,h*0.12)
                    let box=placePopup(at.x,at.y,bw,bh,w,h,occupied,p.anchor.hasPrefix("bottom")); occupied.append(box)
                    UIColor(white:0.06,alpha:0.92).setFill(); UIBezierPath(roundedRect:box,cornerRadius:6).fill()
                    ctx.setFillColor(color(s.color).cgColor); ctx.fill(CGRect(x:box.minX,y:box.minY,width:3,height:bh))
                    func text(_ line: String,_ font: UIFont,_ top: CGFloat,_ ink: UIColor) {
                        let paragraph=NSMutableParagraphStyle(); paragraph.lineBreakMode = .byTruncatingTail
                        (line as NSString).draw(in:CGRect(x:box.minX+10,y:box.minY+top,width:max(1,bw-20),height:font.lineHeight),withAttributes:[.font:font,.foregroundColor:ink,.paragraphStyle:paragraph])
                    }
                    if value.isEmpty { text(p.label,valueFont,(bh-valueFont.lineHeight)/2,.white) }
                    else { text(p.label,labelFont,bh*0.08,.lightGray); text(value,valueFont,bh*0.43,.white) }
                }
            }
            for s in scene.shapes {
                let a = pos(s.a), b = pos(s.b); ctx.setStrokeColor(color(s.color).cgColor); ctx.setLineWidth(max(1.5, h / 240) * s.width)
                if s.kind == "circle" { let r = hypot(b.x - a.x, b.y - a.y); ctx.strokeEllipse(in: CGRect(x: a.x - r, y: a.y - r, width: 2 * r, height: 2 * r)) }
                else { ctx.move(to: a); ctx.addLine(to: b); ctx.strokePath() }
            }
            if let logo {
                let lw = w * scene.logoSize, lh = lw * logo.size.height / logo.size.width
                let x = scene.logoCorner.hasSuffix("right") ? w - lw - w * 0.03 : w * 0.03
                let y = scene.logoCorner.hasPrefix("bottom") ? h - lh - h * 0.05 : h * 0.03
                logo.draw(in: CGRect(x: x, y: y, width: lw, height: lh), blendMode: .normal, alpha: scene.logoOpacity)
            }
            TrackingSession.watermarkOnly(width: scene.width, height: scene.height).draw(at: .zero)
        }
    }
}

enum NativeFlight {
    private static func values(_ model: STModel) -> [Double] { withUnsafeBytes(of: model) { Array($0.bindMemory(to: Double.self)) } }
    private static func model(_ values: [Double]) -> STModel? {
        guard values.count == 16 else { return nil }
        var model = STModel(); withUnsafeMutableBytes(of: &model) { dest in values.withUnsafeBytes { dest.copyMemory(from: $0) } }; return model
    }
    static func at(_ values: [Double], _ time: Double) -> EditPoint? {
        guard var model = model(values) else { return nil }; var x = 0.0, y = 0.0
        guard st_model_at(&model, time, &x, &y) == 0 else { return nil }; return EditPoint(x, y)
    }
    static func apex(_ values: [Double]) -> Double { guard var m = model(values) else { return 0 }; return st_model_apex(&m) }
    static func reshape(_ values: [Double], _ samples: [EditSample], _ time: Double) -> [Double]? {
        guard var m = model(values) else { return nil }; var out = STModel()
        let p = samples.dropFirst().flatMap { [$0.time, $0.x, $0.y] }
        guard st_with_apex(&m, p, Int32(samples.count - 1), time, &out) == 0 else { return nil }; return self.values(out)
    }
    static func fit(_ samples: [EditSample], _ ball: EditPoint) -> [Double]? {
        guard samples.count >= 4 else { return nil }
        let p = samples.dropFirst().flatMap { [$0.time, $0.x, $0.y] }; var model = STModel()
        guard st_fit_model(p, Int32(samples.count - 1), ball.x, ball.y, 0.15, samples[0].time, samples[1].time - 0.00001, 3.5, 7.5, 5.5, &model) == 0 else { return nil }
        return values(model)
    }
    static func refit(_ base: [Double], _ samples: [EditSample], _ landing: EditPoint?, _ flight: Double) -> [Double]? {
        guard var original = model(base) else { return nil }
        let p = samples.dropFirst().flatMap { [$0.time, $0.x, $0.y] }; var result = STModel()
        let status: Int32
        if let landing { status = [landing.x, landing.y].withUnsafeBufferPointer { st_refit_model(&original, p, Int32(samples.count - 1), $0.baseAddress, flight, &result) } }
        else { status = st_refit_model(&original, p, Int32(samples.count - 1), nil, flight, &result) }
        return status == 0 ? values(result) : nil
    }
}

struct PersonMask: Codable, Sendable { var time: Double; var path: String }
private struct PersonSample {
    let time: Double, gray: [UInt8], body: [UInt8], hands: EditPoint?, height: Double, motion: EditPoint
}

/// Vision supplies on-device masks/landmarks. The shared detector observes club motion;
/// missing or low-confidence wrists do not produce invented swing points.
final class PersonAnalysis {
    private let segmentation = VNGeneratePersonSegmentationRequest()
    private let pose = VNDetectHumanBodyPoseRequest()
    private let ci = CIContext(options: [.cacheIntermediates:false])
    private let folder: URL
    private var before: PersonSample?, previous: PersonSample?
    var masks: [PersonMask] = [], hands: [EditSample] = [], clubs: [EditSample] = []
    init(_ folder: URL) throws {
        self.folder=folder; try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true)
        segmentation.qualityLevel = .balanced; segmentation.outputPixelFormat=kCVPixelFormatType_OneComponent8
    }
    private func gray(_ image: CGImage, _ w: Int, _ h: Int) -> [UInt8] {
        var bytes=[UInt8](repeating:0,count:w*h)
        bytes.withUnsafeMutableBytes { data in
            if let c=CGContext(data:data.baseAddress,width:w,height:h,bitsPerComponent:8,bytesPerRow:w,space:CGColorSpaceCreateDeviceGray(),bitmapInfo:0) {
                c.interpolationQuality = .medium; c.draw(image,in:CGRect(x:0,y:0,width:w,height:h))
            }
        }; return bytes
    }
    func push(_ image: CGImage, _ time: Double, _ motion: EditPoint) throws {
        let w=image.width, h=image.height
        try VNImageRequestHandler(cgImage:image,orientation:.up,options:[:]).perform([segmentation,pose])
        var body=[UInt8](repeating:0,count:w*h)
        if let buffer=segmentation.results?.first?.pixelBuffer,
           let mask=ci.createCGImage(CIImage(cvPixelBuffer:buffer),from:CIImage(cvPixelBuffer:buffer).extent,format:.L8,colorSpace:CGColorSpaceCreateDeviceGray()) {
            body=gray(mask,w,h)
            let mw=min(256,w), mh=max(1,h*mw/w), values=gray(mask,mw,mh)
            var rgba=[UInt8](repeating:0,count:mw*mh*4)
            for i in values.indices { for j in 0..<4 { rgba[i*4+j]=values[i] } }
            let provider=CGDataProvider(data:Data(rgba) as CFData)!
            if let cg=CGImage(width:mw,height:mh,bitsPerComponent:8,bitsPerPixel:32,bytesPerRow:mw*4,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGBitmapInfo(rawValue:CGImageAlphaInfo.premultipliedLast.rawValue),provider:provider,decode:nil,shouldInterpolate:true,intent:.defaultIntent), let data=UIImage(cgImage:cg).pngData() {
                let file=folder.appendingPathComponent("\(Int64((time*1_000_000).rounded())).png")
                try data.write(to:file,options:.atomic); masks.append(PersonMask(time:time,path:file.path))
            }
        }
        let observation=pose.results?.first
        func joint(_ key: VNHumanBodyPoseObservation.JointName) -> EditPoint? {
            guard let p=try? observation?.recognizedPoint(key),p.confidence >= 0.4 else { return nil }
            return EditPoint(p.location.x,1-p.location.y)
        }
        let hand: EditPoint?
        if let a=joint(.leftWrist),let b=joint(.rightWrist) { hand=EditPoint((a.x+b.x)/2,(a.y+b.y)/2) } else { hand=nil }
        var height=0.0
        if let a=joint(.leftShoulder),let b=joint(.rightShoulder),let c=joint(.leftAnkle),let d=joint(.rightAnkle) { height=max(8,(c.y+d.y-a.y-b.y)/2*Double(h)) }
        if let hand { hands.append(EditSample(time,hand.x-motion.x,hand.y-motion.y)) }
        let current=PersonSample(time:time,gray:gray(image,w,h),body:body,hands:hand,height:height,motion:motion)
        if let a=before,let b=previous,let hand=b.hands,b.height >= 8,time-a.time < 0.3 {
            var tip=STPoint()
            if st_club_point(a.gray,b.gray,current.gray,b.body,Int32(w),Int32(h),hand.x*Double(w),hand.y*Double(h),b.height,
                (a.motion.x-b.motion.x)*Double(w),(a.motion.y-b.motion.y)*Double(h),(motion.x-b.motion.x)*Double(w),(motion.y-b.motion.y)*Double(h),&tip) == 1 {
                clubs.append(EditSample(b.time,tip.x/Double(w)-b.motion.x,tip.y/Double(h)-b.motion.y))
            }
        }
        before=previous; previous=current
    }
}

struct PopupWindow {
    let begins: Double, ends: Double
    func contains(_ time: Double) -> Bool { time+0.000001>=begins && time<=ends+0.000001 }
}
func popupWindow(_ shot: EditShot,_ popup: EditPopup) -> PopupWindow? {
    let event=popup.anchor == "landing" ? shot.flight : popup.anchor == "apex" ? shot.flight*shot.apexFraction : 0
    let begins=shot.start+event+popup.delay
    guard begins.isFinite else { return nil }
    let end: Double
    if popup.hide == "end" { end = .infinity }
    else if popup.hide == "time" { end=begins+max(0.1,popup.duration.isFinite ? popup.duration : 3) }
    else { end=max(shot.start+shot.flight+1,begins+1) }
    return PopupWindow(begins:begins,ends:end)
}
func popupFrame(_ shot: EditShot,_ popup: EditPopup,_ times: [Double],_ current: Int) -> Int? {
    guard !times.isEmpty,let window=popupWindow(shot,popup) else { return nil }
    if times.indices.contains(current),window.contains(times[current]) { return current }
    var lo=0,hi=times.count
    while lo<hi { let mid=(lo+hi)/2; if times[mid]+0.000001<window.begins { lo=mid+1 } else { hi=mid } }
    return lo<times.count && window.contains(times[lo]) ? lo : nil
}
func placePopup(_ x: CGFloat,_ y: CGFloat,_ width: CGFloat,_ height: CGFloat,_ w: CGFloat,_ h: CGFloat,_ used: [CGRect],_ bottom: Bool) -> CGRect {
    let right=max(4,w-width-4),end=max(4,h-height-4),sx=min(max(4,x),right),sy=min(max(4,y),end),step=height+4
    let count=Int(ceil(h/step))+1, direction: CGFloat=bottom ? -1 : 1
    let ys=([sy]+(1...count).map { sy+direction*CGFloat($0)*step }+(1...count).map { sy-direction*CGFloat($0)*step }+(0...count).map { 4+CGFloat($0)*step }).filter { $0>=4 && $0<=end }
    for cx in [sx,4,right] { for cy in ys {
        let box=CGRect(x:cx,y:cy,width:width,height:height)
        let overlaps=used.contains { b in !(box.maxX+4<=b.minX || b.maxX+4<=box.minX || box.maxY+4<=b.minY || b.maxY+4<=box.minY) }
        if !overlaps { return box }
    } }
    return CGRect(x:sx,y:sy,width:width,height:height)
}

/// Source-frame exposure at a real-time playback clock, including variable-rate footage.
func playbackFrame(_ times: [Double], _ sourceTime: Double) -> Int {
    guard !times.isEmpty else { return 0 }
    var left=0, right=times.count
    while left<right {
        let mid=(left+right)/2
        if times[mid]<=sourceTime { left=mid+1 } else { right=mid }
    }
    return min(times.count-1,max(0,left-1))
}
