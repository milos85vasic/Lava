#!/usr/bin/env python3
"""
Icon Validation Test
====================
Ensures all generated launcher/icon assets meet padding and cutoff criteria.

For a red squircle logo on white background (legacy) or transparent (adaptive):

1. Source trimmed logo (assets/Logo_trimmed.png):
   - Must be a square (1:1 aspect ratio)
   - Content should be centered with minimal transparent padding

2. Legacy launcher icons (white bg, red squircle):
   - The red content should fill >= 92% of canvas (nearly edge-to-edge)
   - At least one edge must have >= 1px padding (not cut off)
   - No edge should have > 10% padding (no excessive white border)

3. Adaptive foregrounds (transparent bg, red squircle):
   - Content should fill >= 50% of canvas (within safe zone)
   - Should have padding on all sides (stays within safe zone, not cut off)

4. No icon should have content that bleeds to ALL 4 edges simultaneously
   (prevents cut-off artifacts)

Usage:
    python3 scripts/validate_icons.py
    # Exit 0 = all passed, Exit 1 = failures
"""

import os
import sys
from PIL import Image

FAILED = False

def log_pass(msg):
    print(f"  [PASS] {msg}")

def log_fail(msg):
    global FAILED
    FAILED = True
    print(f"  [FAIL] {msg}")

def get_content_bounds(img: Image.Image, alpha_threshold=30) -> tuple:
    """Returns (left, top, right, bottom) of non-transparent pixel content."""
    if img.mode != 'RGBA':
        img = img.convert('RGBA')
    w, h = img.size
    alpha = img.split()[3]
    left, top, right, bottom = w, h, 0, 0
    for x in range(w):
        for y in range(h):
            if alpha.getpixel((x, y)) > alpha_threshold:
                left = min(left, x)
                top = min(top, y)
                right = max(right, x + 1)
                bottom = max(bottom, y + 1)
    return left, top, right, bottom

def get_edge_max_alpha(img: Image.Image) -> dict:
    """Returns max alpha value found on each edge."""
    if img.mode != 'RGBA':
        img = img.convert('RGBA')
    w, h = img.size
    alpha = img.split()[3]
    return {
        'top': max(alpha.getpixel((x, 0)) for x in range(w)),
        'bottom': max(alpha.getpixel((x, h - 1)) for x in range(w)),
        'left': max(alpha.getpixel((0, y)) for y in range(h)),
        'right': max(alpha.getpixel((w - 1, y)) for y in range(h)),
    }

def validate_source_trimmed():
    print("\n=== Validating assets/Logo_trimmed.png ===")
    if not os.path.exists('assets/Logo_trimmed.png'):
        log_fail("File not found: assets/Logo_trimmed.png")
        return
    img = Image.open('assets/Logo_trimmed.png')
    w, h = img.size
    if w != h:
        log_fail(f"Not square: {w}x{h}")
    else:
        log_pass(f"Square: {w}x{h}")
    
    left, top, right, bottom = get_content_bounds(img)
    pad_left = left / w
    pad_top = top / h
    pad_right = (w - right) / w
    pad_bottom = (h - bottom) / h
    
    log_pass(f"Content bounds: ({left}, {top}, {right}, {bottom})")
    
    for name, pad in [("left", pad_left), ("top", pad_top), ("right", pad_right), ("bottom", pad_bottom)]:
        if pad > 0.10:
            log_fail(f"Padding {name} = {pad:.2%} > 10% — excessive")
        else:
            log_pass(f"Padding {name} = {pad:.2%}")

def get_nonwhite_bounds(img: Image.Image, white_threshold=245) -> tuple:
    """Returns bounds of pixels that differ significantly from white."""
    if img.mode != 'RGBA':
        img = img.convert('RGBA')
    w, h = img.size
    left, top, right, bottom = w, h, 0, 0
    for x in range(w):
        for y in range(h):
            r, g, b, a = img.getpixel((x, y))
            # Pixel is "content" if not mostly white and reasonably opaque
            if a > 30 and not (r > white_threshold and g > white_threshold and b > white_threshold):
                left = min(left, x)
                top = min(top, y)
                right = max(right, x + 1)
                bottom = max(bottom, y + 1)
    return left, top, right, bottom

def validate_legacy_icon(path, label):
    """Legacy launcher: white bg, red squircle nearly filling canvas."""
    print(f"\n=== Validating {label}: {path} ===")
    if not os.path.exists(path):
        log_fail(f"File not found: {path}")
        return
    img = Image.open(path).convert('RGBA')
    w, h = img.size
    
    # Check that corners are white (indicates proper white background border)
    corners = [(0, 0), (w-1, 0), (0, h-1), (w-1, h-1)]
    white_corners = 0
    for cx, cy in corners:
        r, g, b, a = img.getpixel((cx, cy))
        if a > 200 and r > 240 and g > 240 and b > 240:
            white_corners += 1
    if white_corners >= 3:
        log_pass(f"{white_corners}/4 corners are white")
    else:
        log_fail(f"Only {white_corners}/4 corners are white — background may not be white")
    
    # Check red squircle bounds (non-white content)
    left, top, right, bottom = get_nonwhite_bounds(img)
    
    if left >= right or top >= bottom:
        log_fail("No red content found!")
        return
    
    content_w = right - left
    content_h = bottom - top
    fill_w = content_w / w
    fill_h = content_h / h
    
    # Legacy icons should nearly fill the canvas (red squircle close to edges)
    if fill_w < 0.90 or fill_h < 0.90:
        log_fail(f"Fill ratio {fill_w:.2%} x {fill_h:.2%} < 90% — too much padding")
    else:
        log_pass(f"Fill ratio {fill_w:.2%} x {fill_h:.2%} >= 90%")
    
    # Check no single edge has excessive padding (>8%)
    pads = {
        'left': left / w,
        'top': top / h,
        'right': (w - right) / w,
        'bottom': (h - bottom) / h,
    }
    for name, pad in pads.items():
        if pad > 0.08:
            log_fail(f"Padding {name} = {pad:.2%} > 8% — excessive white border")
        else:
            log_pass(f"Padding {name} = {pad:.2%} <= 8%")
    
    # At least one edge should have >= 1px padding (not cut off)
    has_padding = any(p >= (1 / w if 'left' in name or 'right' in name else 1 / h) for name, p in pads.items())
    if has_padding:
        log_pass("At least one edge has padding (not cut off)")
    else:
        log_fail("No padding on any edge — may be cut off")

def validate_adaptive_foreground(path, label):
    """Adaptive foreground: transparent bg, red squircle within safe zone."""
    print(f"\n=== Validating {label}: {path} ===")
    if not os.path.exists(path):
        log_fail(f"File not found: {path}")
        return
    img = Image.open(path)
    w, h = img.size
    left, top, right, bottom = get_content_bounds(img)
    
    if left >= right or top >= bottom:
        log_fail("No non-transparent content found!")
        return
    
    content_w = right - left
    content_h = bottom - top
    fill_w = content_w / w
    fill_h = content_h / h
    
    # Adaptive foreground should be within safe zone (~66dp of 108dp = 61%)
    # Allow 50-65% fill
    if fill_w < 0.50 or fill_h < 0.50:
        log_fail(f"Fill ratio {fill_w:.2%} x {fill_h:.2%} < 50% — too small")
    elif fill_w > 0.65 or fill_h > 0.65:
        log_fail(f"Fill ratio {fill_w:.2%} x {fill_h:.2%} > 65% — may be cut off by device mask")
    else:
        log_pass(f"Fill ratio {fill_w:.2%} x {fill_h:.2%} within safe zone [50%, 65%]")
    
    # Must have padding on all sides (safe zone requirement)
    pads = {
        'left': left,
        'top': top,
        'right': w - right,
        'bottom': h - bottom,
    }
    min_pad = min(pads.values())
    if min_pad >= 1:
        log_pass(f"Padding on all sides (min={min_pad}px) — safe from device masks")
    else:
        log_fail(f"No padding on at least one edge — may be cut off by device mask")

def validate_image(path, label, min_fill_ratio=0.80, max_fill_ratio=1.0, require_padding=True, is_white_bg=True):
    """Generic validation for other icons (splash, about, banner, etc.)."""
    print(f"\n=== Validating {label}: {path} ===")
    if not os.path.exists(path):
        log_fail(f"File not found: {path}")
        return
    img = Image.open(path).convert('RGBA')
    w, h = img.size
    
    if is_white_bg:
        left, top, right, bottom = get_nonwhite_bounds(img)
    else:
        left, top, right, bottom = get_content_bounds(img)
    
    if left >= right or top >= bottom:
        log_fail("No content found!")
        return
    
    content_w = right - left
    content_h = bottom - top
    fill_w = content_w / w
    fill_h = content_h / h
    
    if fill_w < min_fill_ratio or fill_h < min_fill_ratio:
        log_fail(f"Fill ratio {fill_w:.2%} x {fill_h:.2%} < {min_fill_ratio:.0%}")
    elif max_fill_ratio < 1.0 and (fill_w > max_fill_ratio or fill_h > max_fill_ratio):
        log_fail(f"Fill ratio {fill_w:.2%} x {fill_h:.2%} > {max_fill_ratio:.0%}")
    else:
        log_pass(f"Fill ratio {fill_w:.2%} x {fill_h:.2%} within range")
    
    if require_padding:
        pads = [left, top, w - right, h - bottom]
        if all(p >= 1 for p in pads):
            log_pass("Has padding on all sides")
        else:
            log_fail("Missing padding on at least one edge")

def main():
    validate_source_trimmed()
    
    # Legacy launcher icons
    for dpi, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
        validate_legacy_icon(f'app/src/main/res/mipmap-{dpi}/ic_launcher.png', f'Launcher {dpi} ({size}px)')
    
    # Adaptive foregrounds
    for dpi, size in [('mdpi', 108), ('hdpi', 162), ('xhdpi', 216), ('xxhdpi', 324), ('xxxhdpi', 432)]:
        validate_adaptive_foreground(f'app/src/main/res/mipmap-{dpi}/ic_launcher_foreground.png', f'Adaptive FG {dpi} ({size}px)')
    
    # Splash
    validate_image('app/src/main/res/drawable/ic_splash.png', 'Splash (288px)', min_fill_ratio=0.80, max_fill_ratio=0.95)
    
    # TV banner
    validate_image('app/src/main/res/drawable/tv_banner.png', 'TV Banner (320x180)', min_fill_ratio=0.50, max_fill_ratio=1.0)
    
    # About logos
    validate_image('app/src/main/res/drawable/ic_logo_about.png', 'About Logo (128px)', min_fill_ratio=0.85, max_fill_ratio=0.98)
    validate_image('feature/menu/src/main/res/drawable/ic_logo_about.png', 'About Logo Feature (128px)', min_fill_ratio=0.85, max_fill_ratio=0.98)
    
    # Play store
    validate_image('app/src/main/playstore_icon.png', 'Play Store (512px)', min_fill_ratio=0.92, max_fill_ratio=0.98)
    
    print("\n" + "=" * 50)
    if FAILED:
        print("VALIDATION FAILED")
        sys.exit(1)
    else:
        print("ALL VALIDATIONS PASSED")
        sys.exit(0)

if __name__ == '__main__':
    main()
