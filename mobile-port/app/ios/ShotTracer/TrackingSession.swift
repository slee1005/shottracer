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

    init(width: Int, height: Int, ball: CGPoint, putt: Bool = false, flight: Double = 0) throws {
        guard let handle = st_create(Int32(width), Int32(height), ball.x, ball.y) else {
            throw MobileError.invalidBall
        }
        guard st_set_mode(handle, putt ? 1 : 0) == 0, st_use_desktop_tracking(handle, flight) == 0 else {
            st_destroy(handle)
            throw MobileError.invalidFrame
        }
        self.width = width
        self.height = height
        self.handle = handle
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
            defer { lock.unlock() }
            if st_camera_count(handle) == 0 {
                var rgba = [UInt8](repeating: 0, count: width * height * 4)
                try rgba.withUnsafeMutableBytes { color in
                    guard let reference = CGContext(data: color.baseAddress, width: width, height: height,
                        bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue) else {
                        throw MobileError.invalidFrame
                    }
                    reference.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
                    guard st_reference_rgba(handle, color.baseAddress?.assumingMemoryBound(to: UInt8.self), Int32(width * 4)) == 0 else { throw MobileError.invalidFrame }
                }
            }
            let status = st_push(handle, raw.baseAddress?.assumingMemoryBound(to: UInt8.self), Int32(width), time)
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
struct EditCameraTransform: Codable, Sendable {
    var time: Double, m00: Double, m01: Double, m02: Double, m10: Double, m11: Double, m12: Double
    init(_ time: Double, _ m00: Double, _ m01: Double, _ m02: Double, _ m10: Double, _ m11: Double, _ m12: Double) {
        self.time=time; self.m00=m00; self.m01=m01; self.m02=m02; self.m10=m10; self.m11=m11; self.m12=m12
    }
    init(from decoder: Decoder) throws {
        var c=try decoder.unkeyedContainer()
        let v=try (0..<7).map { _ in try c.decode(Double.self) }
        self.init(v[0],v[1],v[2],v[3],v[4],v[5],v[6])
    }
    func encode(to encoder: Encoder) throws {
        var c=encoder.unkeyedContainer()
        for value in [time,m00,m01,m02,m10,m11,m12] { try c.encode(value) }
    }
    func frame(_ p: EditPoint) -> EditPoint { EditPoint(m00*p.x+m01*p.y+m02,m10*p.x+m11*p.y+m12) }
    func reference(_ p: EditPoint) -> EditPoint {
        let determinant=m00*m11-m01*m10
        guard abs(determinant)>1e-10 else { return p }
        let x=p.x-m02,y=p.y-m12
        return EditPoint((m11*x-m01*y)/determinant,(-m10*x+m00*y)/determinant)
    }
}
func cameraAt(_ rows: [EditCameraTransform], _ time: Double) -> EditCameraTransform? {
    guard let first=rows.first,let last=rows.last else { return nil }
    if time<=first.time { return first }; if time>=last.time { return last }
    var low=0,high=rows.count-1
    while high-low>1 { let mid=(low+high)/2; if rows[mid].time<time { low=mid } else { high=mid } }
    let a=rows[low],b=rows[high],f=(time-a.time)/(b.time-a.time)
    return EditCameraTransform(time,a.m00+f*(b.m00-a.m00),a.m01+f*(b.m01-a.m01),a.m02+f*(b.m02-a.m02),
                               a.m10+f*(b.m10-a.m10),a.m11+f*(b.m11-a.m11),a.m12+f*(b.m12-a.m12))
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
let shotInfoKeys = ["total", "carry", "height"]
struct ShotInfo: Codable, Sendable {
    var value = ""
    var visible = true
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
    var cameraTransforms: [EditCameraTransform]?
    var timingLandingEstimated: Bool?
    var shotInfo: [String: ShotInfo]?
    var landingConfirmed: Bool?
    var hasTrace: Bool { ball != nil && (edited || !base.isEmpty) && (landingConfirmed != true || manual || !base.isEmpty) }
    var readyToAnalyze: Bool { ball != nil && landing != nil && landingConfirmed == true && (0.2...15).contains(flight) }
    var trackingMode: String?
    var autoHands: [EditSample]?, autoClub: [EditSample]?, masks: [PersonMask]?
    var baseModel: [Double]?
    var popups: [EditPopup] = []
    var edited: Bool { baseModel != nil || manual || landing != nil || apex != nil }
    func toFrame(_ p: EditPoint, at time: Double) -> EditPoint {
        if let transform=cameraAt(cameraTransforms ?? [],time) { return transform.frame(p) }
        let offset=interpolated(camera,time); return EditPoint(p.x+offset.x,p.y+offset.y)
    }
    func toReference(_ p: EditPoint, at time: Double) -> EditPoint {
        if let transform=cameraAt(cameraTransforms ?? [],time) { return transform.reference(p) }
        let offset=interpolated(camera,time); return EditPoint(p.x-offset.x,p.y-offset.y)
    }
    var model: [Double]? {
        guard trackingMode != "putt", let baseModel, baseModel.count == 16, !manual, apex == nil else { return nil }
        guard let result = landing == nil && abs(flight - baseModel[10]) < 1e-8 ? baseModel : NativeFlight.refit(baseModel, base, landing, flight) else { return nil }
        return abs(flight * apexFraction - NativeFlight.apex(result)) > 1e-5 ? NativeFlight.reshape(result, base, flight * apexFraction) ?? result : result
    }
    var apexSeconds: Double { model.map(NativeFlight.apex) ?? flight * apexFraction }
    var endpoint: EditPoint { landing ?? model.flatMap { NativeFlight.at($0, flight) } ?? base.last.map { EditPoint($0.x, $0.y) } ?? EditPoint(0.75, 0.65) }
    var peak: EditPoint { if trackingMode == "putt" { return apex ?? ground(flight*0.5) }; let a = ball ?? EditPoint(0.2, 0.8), b = endpoint; return apex ?? model.flatMap { NativeFlight.at($0, NativeFlight.apex($0)) } ?? EditPoint((a.x + b.x) / 2, min(a.y, b.y) - 0.25) }
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
    var autoWristsEnabled: Bool { autoSwing == nil || autoSwing == "both" || autoSwing == "hands" }
    var logo: String?, logoSize = 0.12, logoOpacity = 0.85, logoCorner = "bottom-right"
    var slowmo = false, slowFactor = 3.0, slowBefore = 0.4, slowAfter = 1.2
    var shot: EditShot { get { shots[min(max(0, selected), shots.count - 1)] } set { shots[min(max(0, selected), shots.count - 1)] = newValue } }
}

extension TrackingSession {
    func finish() throws {
        lock.lock(); defer { lock.unlock() }
        if st_finish(handle)<0 { throw MobileError.invalidFrame }
    }
    func transforms() -> [EditCameraTransform] {
        lock.lock(); defer { lock.unlock() }
        return (0..<st_camera_count(handle)).compactMap { index in
            var p=STCameraTransform()
            guard st_camera_transform(handle,index,&p)==0 else { return nil }
            return EditCameraTransform(p.time,p.m00,p.m01,p.m02,p.m10,p.m11,p.m12)
        }
    }
    func flightModel() -> [Double]? {
        lock.lock(); defer { lock.unlock() }
        var model = STModel()
        return st_tracker_model(handle, &model) == 0 ? NativeFlight.values(model) : nil
    }

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
    private let trajectories: [String: ShotTrajectory]
    init(_ scene: EditorScene) {
        self.scene = scene
        logo = scene.logo.flatMap { UIImage(contentsOfFile: $0) }
        trajectories = Dictionary(uniqueKeysWithValues: scene.shots.map {
            ($0.id, ShotTrajectory($0, width: scene.width, height: scene.height))
        })
    }
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
                guard let trajectory = trajectories[s.id] else { continue }
                let airTime = trajectory.airTime
                let duration = trajectory.duration
                let landing = trajectory.landing
                func frame(_ p: EditPoint) -> CGPoint { pos(s.toFrame(p,at:time)) }
                let elapsed = (time - s.start) * s.speed
                var points: [EditPoint] = []
                if s.hasTrace && (s.trackingMode != "putt" || s.hasGroundPath) && elapsed >= 0 &&
                    (scene.hold < 0 || time <= s.start + duration / s.speed + scene.hold) {
                    let to = min(duration, elapsed)
                    let from = scene.preset == "comet" ? min(to, max(0, elapsed - 0.7)) : 0
                    points = (0...128).map { trajectory.point(s, at: from + (to - from) * Double($0) / 128) }
                }
                ctx.beginTransparencyLayer(auxiliaryInfo: nil)
                if points.count >= 2 {
                    let width = max(1.5, h / 240) * scene.lineWidth
                    let path = ribbon(points.map(frame), width: width)
                    ctx.saveGState()
                    if scene.preset != "minimal" && scene.glow > 0 {
                        ctx.setShadow(offset: .zero, blur: width * scene.glow, color: color(s.color).cgColor)
                    }
                    ctx.setFillColor(color(s.color).cgColor)
                    ctx.addPath(path)
                    ctx.fillPath()
                    ctx.restoreGState()
                    let p = frame(points.last!)
                    if scene.head { ctx.setFillColor(UIColor.white.cgColor); ctx.fillEllipse(in: CGRect(x: p.x - width, y: p.y - width, width: 2 * width, height: 2 * width)) }
                    if scene.marker, elapsed >= airTime, let landing {
                        let contact = frame(landing)
                        let r = h * 0.02
                        ctx.setStrokeColor(color(s.color).cgColor)
                        ctx.setLineWidth(2)
                        ctx.strokeEllipse(in: CGRect(x: contact.x - r, y: contact.y - r, width: 2 * r, height: 2 * r))
                    }
                }
                let splashAge = time - (s.start + airTime / s.speed)
                if scene.marker && s.hasTrace && splashAge >= -0.000001 && splashAge < 0.65 {
                    drawLandingSplash(ctx, at: frame(s.endpoint), size: min(w, h), progress: max(0, splashAge / 0.65))
                }
                if scene.personOcclusion != false, let m = s.masks?.min(by: { abs($0.time-time) < abs($1.time-time) }), abs(m.time-time) <= 0.11 {
                    if let mask = maskCache.object(forKey:m.path as NSString) ?? UIImage(contentsOfFile:m.path) {
                        maskCache.setObject(mask,forKey:m.path as NSString); maskCache.countLimit=6
                        ctx.saveGState(); mask.draw(in:CGRect(x:0,y:0,width:w,height:h),blendMode:.destinationOut,alpha:1); ctx.restoreGState()
                    }
                }
                ctx.endTransparencyLayer()
                if let tip = points.last, let label = trajectory.distance(at: min(elapsed, duration)) {
                    // Symmetric source-time sampling keeps seeks and exports deterministic.
                    let before = s.toFrame(tip,at:time-0.045)
                    let after = s.toFrame(tip,at:time+0.045)
                    let now = s.toFrame(tip,at:time)
                    let stable = EditPoint((before.x + 2 * now.x + after.x) / 4, (before.y + 2 * now.y + after.y) / 4)
                    drawDistance(label, at: pos(stable), width: w, height: h)
                }
                let infoCards: [EditPopup] = s.hasTrace ? shotInfoKeys.compactMap { key in
                    guard key != "total", let info = s.shotInfo?[key], info.visible, !info.value.isEmpty else { return nil }
                    let value = key == "carry" ? ShotMeasurement(info.value).map {
                        distanceInput(info.value, unit: $0.unit) + " " + $0.unit
                    } ?? info.value : info.value
                    return EditPopup(label: NSLocalizedString("editor.info." + key, comment: ""), value: value, anchor: "top-right")
                } : []
                for p in s.popups + infoCards {
                    guard let window=popupWindow(s,p),window.contains(time) else { continue }
                    let at: CGPoint
                    switch p.anchor {
                    case "start": at = frame(s.ball ?? EditPoint(0.1, 0.8))
                    case "apex": at = frame(s.peak)
                    case "landing": at = frame(s.endpoint)
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

struct ShotMeasurement {
    let amount: Double
    let unit: String
    var metres: Double {
        switch unit {
        case "yds": amount * 0.9144
        case "ft": amount * 0.3048
        default: amount
        }
    }

    init?(_ value: String?) {
        let text = (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let pattern = #/^([0-9]+(?:[.,][0-9]+)?)\s*(m|yd|yds|ft|미터|야드|피트)?$/#
        guard let match = text.wholeMatch(of: pattern),
              let amount = Double(match.1.replacingOccurrences(of: ",", with: ".")), amount.isFinite, amount > 0 else { return nil }
        self.amount = amount
        switch match.2 {
        case "yd", "yds", "야드": unit = "yds"
        case "ft", "피트": unit = "ft"
        default: unit = "m"
        }
    }
}

func distanceInput(_ value: String, unit: String) -> String {
    guard let measurement = ShotMeasurement(value) else { return value }
    let amount = measurement.unit == unit ? measurement.amount : measurement.metres / (unit == "yds" ? 0.9144 : 1)
    return amount.formatted(.number.locale(Locale(identifier: "en_US_POSIX")).grouping(.never).precision(.fractionLength(0...6)))
}

private struct PathProgress {
    let lengths: [Double]

    init(_ points: [EditPoint], width: Int, height: Int) {
        var lengths = [0.0]
        for (a, b) in zip(points, points.dropFirst()) {
            lengths.append(lengths[lengths.count - 1] + hypot((b.x - a.x) * Double(width), (b.y - a.y) * Double(height)))
        }
        self.lengths = lengths
    }

    func fraction(_ phase: Double) -> Double {
        let last = lengths.count - 1
        guard lengths[last] > 1e-9 else { return phase >= 1 ? 1 : 0 }
        let index = min(1, max(0, phase)) * Double(last)
        let lower = Int(index)
        let upper = min(lower + 1, last)
        return (lengths[lower] + (lengths[upper] - lengths[lower]) * (index - Double(lower))) / lengths[last]
    }
}

private struct ShotTrajectory {
    let airTime: Double
    let duration: Double
    let landing: EditPoint?
    private let model: [Double]?
    private let riseScale: Double
    private let rollRatio: Double
    private let rollTime: Double
    private let total: ShotMeasurement?
    private let carryShare: Double
    private var airProgress: PathProgress?

    init(_ shot: EditShot, width: Int, height: Int) {
        model = shot.model
        let info = shot.shotInfo
        let total = ShotMeasurement(info?["total"]?.value)
        self.total = total
        let carry = ShotMeasurement(info?["carry"]?.value)?.metres ?? 0
        var airTime = shot.flight, riseScale = 1.0, rollRatio = 0.0
        if shot.trackingMode != "putt", info != nil {
            st_shot_info_profile(carry, total?.metres ?? 0, ShotMeasurement(info?["height"]?.value)?.metres ?? 0,
                                 shot.flight, &airTime, &riseScale, &rollRatio)
        }
        self.airTime = airTime
        self.riseScale = riseScale
        self.rollRatio = rollRatio
        rollTime = airTime * rollRatio
        duration = airTime + rollTime
        carryShare = total.map { rollRatio > 0 ? carry / $0.metres : 1 } ?? 1
        landing = shot.hasTrace ? shot.endpoint : nil
        if total != nil, info?["total"]?.visible == true, landing != nil {
            airProgress = PathProgress((0...128).map { point(shot, at: airTime * Double($0) / 128) }, width: width, height: height)
        }
    }

    func point(_ shot: EditShot, at time: Double) -> EditPoint {
        let fraction = min(1, max(0, time / airTime))
        let sourceTime = fraction * shot.flight
        if let start = shot.ball, let landing, time > airTime, rollTime > 0 {
            let progress = rollProgress(at: time)
            return EditPoint(landing.x + (landing.x - start.x) * rollRatio * progress,
                             landing.y + (landing.y - start.y) * rollRatio * progress)
        }
        let point = shot.trackingMode == "putt" ? shot.ground(sourceTime)
            : model.flatMap { NativeFlight.at($0, sourceTime) } ?? shot.curve(sourceTime / shot.speed)
        guard let start = shot.ball, let landing, riseScale != 1 else { return point }
        let groundY = start.y + (landing.y - start.y) * fraction
        return EditPoint(point.x, groundY + (point.y - groundY) * riseScale)
    }

    private func rollProgress(at time: Double) -> Double {
        let remaining = 1 - min(1, max(0, (time - airTime) / rollTime))
        return 1 - remaining * remaining
    }

    func distance(at time: Double) -> String? {
        guard let total, let airProgress else { return nil }
        let fraction = time > airTime && rollTime > 0
            ? carryShare + (1 - carryShare) * rollProgress(at: time)
            : carryShare * airProgress.fraction(time / airTime)
        let finished = time >= duration
        let amount = finished ? total.amount : total.amount * fraction
        let style = FloatingPointFormatStyle<Double>.number.grouping(.never)
            .precision(.fractionLength(0...(total.amount < 10 || finished ? 2 : 0)))
            .rounded(rule: finished ? .toNearestOrAwayFromZero : .towardZero)
        return amount.formatted(style) + " " + total.unit
    }
}

private func drawDistance(_ label: String, at point: CGPoint, width: CGFloat, height: CGFloat) {
    let size = min(width, height)
    let text = label as NSString
    var font = UIFont.boldSystemFont(ofSize: max(1, size * 0.052))
    let labelWidth = text.size(withAttributes: [.font: font]).width
    if labelWidth > width * 0.85 { font = font.withSize(font.pointSize * width * 0.85 / labelWidth) }
    let bounds = text.size(withAttributes: [.font: font])
    let margin = size * 0.018
    let origin = CGPoint(x: max(margin, min(width - bounds.width - margin, point.x - bounds.width / 2)),
                         y: max(margin, min(height - bounds.height - margin, point.y - size * 0.045 - font.ascender)))
    text.draw(at: origin, withAttributes: [
        .font: font, .foregroundColor: UIColor.white, .strokeColor: UIColor.black, .strokeWidth: -6.0
    ])
}

private func ribbon(_ points: [CGPoint], width: CGFloat) -> CGPath {
    var distinct: [CGPoint] = []
    for point in points where distinct.last != point { distinct.append(point) }
    let path = CGMutablePath()
    guard distinct.count >= 2 else { return path }
    let edges = distinct.enumerated().map { index, point in
        let a = distinct[max(0, index - 1)]
        let b = distinct[min(distinct.count - 1, index + 1)]
        let dx = b.x - a.x
        let dy = b.y - a.y
        let length = max(0.001, hypot(dx, dy))
        let half = width * (0.14 + 0.70 * CGFloat(index) / CGFloat(distinct.count - 1))
        let x = -dy * half / length
        let y = dx * half / length
        return (CGPoint(x: point.x + x, y: point.y + y), CGPoint(x: point.x - x, y: point.y - y))
    }
    path.move(to: edges[0].0)
    for edge in edges.dropFirst() { path.addLine(to: edge.0) }
    for edge in edges.reversed() { path.addLine(to: edge.1) }
    path.closeSubpath()
    return path
}

private func drawLandingSplash(_ ctx: CGContext, at point: CGPoint, size: CGFloat, progress: CGFloat) {
    let spread = size * 0.09
    ctx.saveGState()
    defer { ctx.restoreGState() }
    ctx.setLineWidth(max(1, size * 0.006))
    for delay in [CGFloat(0), 0.16] where progress >= delay {
        let phase = (progress - delay) / (1 - delay)
        let radius = spread * (0.15 + phase)
        ctx.setStrokeColor(UIColor(red: 0.34, green: 0.88, blue: 1, alpha: (1 - phase) * 0.86).cgColor)
        ctx.strokeEllipse(in: CGRect(x: point.x - radius, y: point.y - radius * 0.35,
                                     width: radius * 2, height: radius * 0.7))
    }
    ctx.setFillColor(UIColor(red: 0.67, green: 0.96, blue: 1, alpha: (1 - progress) * 0.96).cgColor)
    let radius = max(1, size * 0.007) * (1 - progress * 0.6)
    for index in -2...2 {
        let x = point.x + CGFloat(index) * spread * progress * 0.55
        let y = point.y - spread * (1.15 - CGFloat(abs(index)) * 0.15) * 4 * progress * (1 - progress)
        ctx.fillEllipse(in: CGRect(x: x - radius, y: y - radius, width: 2 * radius, height: 2 * radius))
    }
}

enum NativeFlight {
    static func values(_ model: STModel) -> [Double] { withUnsafeBytes(of: model) { Array($0.bindMemory(to: Double.self)) } }
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

/// Vision supplies person masks for on-device editing.
final class PersonAnalysis {
    private let segmentation = VNGeneratePersonSegmentationRequest()
    private let ci = CIContext(options: [.cacheIntermediates:false])
    private let folder: URL
    var masks: [PersonMask] = []
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
    func push(_ image: CGImage, _ time: Double) throws {
        let w=image.width, h=image.height
        try VNImageRequestHandler(cgImage:image,orientation:.up,options:[:]).perform([segmentation])
        if let buffer=segmentation.results?.first?.pixelBuffer,
           let mask=ci.createCGImage(CIImage(cvPixelBuffer:buffer),from:CIImage(cvPixelBuffer:buffer).extent,format:.L8,colorSpace:CGColorSpaceCreateDeviceGray()) {
            let mw=min(256,w), mh=max(1,h*mw/w), values=gray(mask,mw,mh)
            var rgba=[UInt8](repeating:0,count:mw*mh*4)
            for i in values.indices { for j in 0..<4 { rgba[i*4+j]=values[i] } }
            let provider=CGDataProvider(data:Data(rgba) as CFData)!
            if let cg=CGImage(width:mw,height:mh,bitsPerComponent:8,bitsPerPixel:32,bytesPerRow:mw*4,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGBitmapInfo(rawValue:CGImageAlphaInfo.premultipliedLast.rawValue),provider:provider,decode:nil,shouldInterpolate:true,intent:.defaultIntent), let data=UIImage(cgImage:cg).pngData() {
                let file=folder.appendingPathComponent("\(Int64((time*1_000_000).rounded())).png")
                try data.write(to:file,options:.atomic); masks.append(PersonMask(time:time,path:file.path))
            }
        }
    }
}

struct PopupWindow {
    let begins: Double, ends: Double
    func contains(_ time: Double) -> Bool { time+0.000001>=begins && time<=ends+0.000001 }
}
func popupWindow(_ shot: EditShot,_ popup: EditPopup) -> PopupWindow? {
    let event=popup.anchor == "landing" ? shot.flight : popup.anchor == "apex" ? shot.apexSeconds : 0
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
