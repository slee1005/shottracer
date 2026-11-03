import Foundation
import UIKit

/// Owns the C handle. Export callbacks and preview can run on different queues.
final class TrackingSession: @unchecked Sendable {
    let width: Int
    let height: Int
    private let handle: OpaquePointer
    private let lock = NSLock()

    init(width: Int, height: Int, ball: CGPoint) throws {
        guard let handle = st_create(Int32(width), Int32(height), ball.x, ball.y) else {
            throw MobileError.invalidBall
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
