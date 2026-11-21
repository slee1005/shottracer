import Foundation
import CoreGraphics

/// The user selects impact, so a missing launch should not scan the whole clip.
/// Source time and work time are separate: slow devices also leave the probe early.
enum LaunchProbe {
    static let sourceLimit = 1.5
    static let workLimit = 4.0
    static let minimumProbe = 0.35

    static func shouldStop(sourceElapsed: Double, workElapsed: Double, hasLaunch: Bool) -> Bool {
        !hasLaunch && (sourceElapsed >= sourceLimit || workElapsed >= workLimit && sourceElapsed >= minimumProbe)
    }
}

/// Prefer the side of the tracer tip with room; keep the entire badge in frame.
func distanceBadgeRect(at tip: CGPoint, text: CGSize, canvas: CGSize) -> CGRect {
    let scale = min(canvas.width, canvas.height)
    let margin = max(2, scale * 0.018), padding = max(2, scale * 0.018)
    let width = min(max(1, canvas.width - 2 * margin), text.width + 2 * padding)
    let height = min(max(1, canvas.height - 2 * margin), text.height + 2 * padding)
    let gap = max(3, scale * 0.035)
    let right = tip.x + gap
    let x = right + width <= canvas.width - margin ? right : tip.x - gap - width
    let maxX: CGFloat = canvas.width - margin - width
    let maxY: CGFloat = canvas.height - margin - height
    let originX: CGFloat = max(margin, min(maxX, x))
    let originY: CGFloat = max(margin, min(maxY, tip.y - height / 2))
    return CGRect(x: originX, y: originY, width: width, height: height)
}
