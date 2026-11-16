// The TV launcher's pictures for Android TV and Google TV, drawn from assets/logo.png in the app's own font (Inter):
//   res/drawable-xhdpi/banner.png       640x360  (the 320x180 dp app banner: Apps, Favourites, Google TV's Your apps)
//   res/drawable-xxhdpi/banner.png      960x540  (4K launchers)
//   res/drawable-nodpi/channel_logo.png 320x320  (the logo of the app's row on the home screen; HomeRows)
//   res/mipmap-xxhdpi, xxxhdpi/ic_launcher.png 144, 192 (the icon, sharper on 4K launchers; xhdpi's 96 px stays)
// Opaque, an indigo ground that stays visible on a black launcher, the mark and name large and kept clear of the edges
// (Google TV rounds the corners and scales the focused banner up).   swift tools/androidtv/make-banner.swift
import AppKit
import CoreText

let root = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : FileManager.default.currentDirectoryPath)
let res = root.appendingPathComponent("androidtv/app/src/main/res")
CTFontManagerRegisterFontsForURL(root.appendingPathComponent("assets/fonts/InterVariable.ttf") as CFURL, .process, nil)
guard let logo = NSImage(contentsOf: root.appendingPathComponent("assets/logo.png")) else { fatalError("no assets/logo.png") }

func inter(_ size: CGFloat, _ weight: CGFloat) -> NSFont {
  let wght = 0x77676874 // 'wght'
  let d = CTFontDescriptorCreateWithAttributes([kCTFontFamilyNameAttribute: "Inter Variable", kCTFontVariationAttribute: [wght: weight]] as CFDictionary)
  return CTFontCreateWithFontDescriptor(d, size, nil) as NSFont
}
func ground(_ ctx: CGContext, _ w: CGFloat, _ h: CGFloat) {
  let cs = CGColorSpaceCreateDeviceRGB()
  let g = CGGradient(colorsSpace: cs, colors: [NSColor(srgbRed: 0.17, green: 0.13, blue: 0.40, alpha: 1).cgColor, NSColor(srgbRed: 0.075, green: 0.06, blue: 0.15, alpha: 1).cgColor] as CFArray, locations: [0, 1])!
  ctx.drawLinearGradient(g, start: CGPoint(x: 0, y: h), end: CGPoint(x: w, y: 0), options: [])
}
// the mark: logo.png's circle (centre 256,212 of 512, radius 166, top-left origin) at diameter d, centred on (cx, cy)
func mark(_ cx: CGFloat, _ cy: CGFloat, _ d: CGFloat, caption: Bool) {
  let s = d / 332, side = 512 * s
  let r = NSRect(x: cx - 256 * s, y: cy - (512 - 212) * s, width: side, height: side)
  if caption { logo.draw(in: r) }
  else { // the circle alone
    NSGraphicsContext.saveGraphicsState(); NSBezierPath(ovalIn: NSRect(x: cx - d / 2, y: cy - d / 2, width: d, height: d)).addClip(); logo.draw(in: r); NSGraphicsContext.restoreGraphicsState() }
}
func render(_ w: Int, _ h: Int, _ draw: (CGContext, CGFloat, CGFloat) -> Void) -> Data {
  let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: w, pixelsHigh: h, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
  NSGraphicsContext.saveGraphicsState(); let g = NSGraphicsContext(bitmapImageRep: rep)!; NSGraphicsContext.current = g; g.imageInterpolation = .high
  draw(g.cgContext, CGFloat(w), CGFloat(h)); g.flushGraphics(); NSGraphicsContext.restoreGraphicsState()
  return rep.representation(using: .png, properties: [:])!
}
func banner(_ k: CGFloat) -> Data {
  return render(Int(640 * k), Int(360 * k)) { ctx, w, h in
    ground(ctx, w, h)
    let d = 140 * k, gap = 34 * k
    let a = NSAttributedString(string: "Stremio", attributes: [.font: inter(86 * k, 760), .foregroundColor: NSColor.white, .kern: -1.5 * k])
    let b = NSAttributedString(string: "Origin", attributes: [.font: inter(66 * k, 520), .foregroundColor: NSColor(white: 0.80, alpha: 1), .kern: -1 * k])
    let tw = max(a.size().width, b.size().width), total = d + gap + tw, x0 = (w - total) / 2
    let cy = h / 2 + 10 * k
    mark(x0 + d / 2, cy, d, caption: true)
    let tx = x0 + d + gap
    a.draw(at: NSPoint(x: tx, y: h / 2 - 4 * k))
    b.draw(at: NSPoint(x: tx + 2 * k, y: h / 2 - 76 * k))
  }
}
let logoData = render(320, 320) { ctx, w, h in
  // the row's logo: the mark alone, full and centred on the ground's colour in a circle (launchers show it round or square)
  ground(ctx, w, h); mark(w / 2, h / 2, 236, caption: false)
}
// the app's icon at the TV densities above the one there (96 px): the mark alone on clear ground, as ic_launcher is
func icon(_ px: Int) -> Data { return render(px, px) { _, w, h in mark(w / 2, h / 2, w * 0.84, caption: false) } }
func put(_ d: Data, _ path: String) { let u = res.appendingPathComponent(path); try! FileManager.default.createDirectory(at: u.deletingLastPathComponent(), withIntermediateDirectories: true); try! d.write(to: u); print(path) }
put(banner(1), "drawable-xhdpi/banner.png")
put(banner(1.5), "drawable-xxhdpi/banner.png")
put(logoData, "drawable-nodpi/channel_logo.png")
put(icon(144), "mipmap-xxhdpi/ic_launcher.png")
put(icon(192), "mipmap-xxxhdpi/ic_launcher.png")
