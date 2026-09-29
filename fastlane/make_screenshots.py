"""Builds Play Store phone screenshots (1080x1920) from raw device captures.

Raw captures live in screenshots-raw/ (1260x2800, iQOO Neo 10R). Each shot is
cropped below the status bar and above the nav bar, framed with rounded
corners on the brand background, and captioned. Re-run after replacing a raw
capture: python fastlane/make_screenshots.py
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).parent
RAW = ROOT / "screenshots-raw"
OUT = ROOT / "metadata/android/en-US/images/phoneScreenshots"

W, H = 1080, 1920
BG_TOP, BG_BOTTOM = (38, 8, 12), (11, 11, 11)
WHITE, MUTED, GOLD = (255, 255, 255), (190, 190, 190), (225, 167, 48)
BOLD = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", 70)
REG = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 38)

STATUS_BAR_END, NAV_BAR_START = 100, 2750  # raw-capture pixel rows

# (raw file, crop top row, headline, highlighted word, subline[, crop bottom row])
SHOTS = [
    ("195502", STATUS_BAR_END, "Your music, remastered live", "remastered",
     "Graphic EQ, parametric EQ and effects for any player"),
    ("213401", 270, "16-band parametric EQ", "parametric",
     "8 filter types with precise frequency, gain and Q"),
    ("213409", STATUS_BAR_END, "Every band at a glance", "glance",
     "Edit type, frequency, gain and Q in one table"),
    ("195510", 770, "Analog warmth, on demand", "warmth",
     "Drive, Warmth and Drift with vintage EQ curves"),
    ("195518", STATUS_BAR_END, "Wider sound, centred vocals", "Wider",
     "Surround+ modes built from EQ, no resampling"),
    ("195530", 750, "Make it yours", "yours",
     "Brand, Material You or custom colours, and pure black", 2230),
]


def background():
    bg = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(bg)
    for y in range(H):
        t = min(1.0, y / (H * 0.6))
        d.line([(0, y), (W, y)], fill=tuple(
            round(a + (b - a) * t) for a, b in zip(BG_TOP, BG_BOTTOM)))
    return bg


def draw_caption(d, headline, highlight, sub):
    words, x_parts = headline.split(" "), []
    widths = [d.textlength(w + " ", font=BOLD) for w in words]
    x = (W - (sum(widths) - d.textlength(" ", font=BOLD))) / 2
    for w, wd in zip(words, widths):
        d.text((x, 110), w, font=BOLD, fill=GOLD if w.strip(",.") == highlight else WHITE)
        x += wd
    d.text(((W - d.textlength(sub, font=REG)) / 2, 215), sub, font=REG, fill=MUTED)


def frame(raw, top, bottom):
    shot = raw.crop((0, top, raw.width, bottom))
    width = 860
    shot = shot.resize((width, round(shot.height * width / shot.width)), Image.LANCZOS)
    y0, avail = 330, H - 330
    bleeds = shot.height > avail - 70
    if bleeds:
        shot = shot.crop((0, 0, width, avail))
    else:
        # Short shots sit centred in the space under the caption.
        y0 += (avail - shot.height - 40) // 2
    radius = 44
    mask = Image.new("L", shot.size, 0)
    md = ImageDraw.Draw(mask)
    # Rounded on all corners unless the shot runs off the bottom edge.
    md.rounded_rectangle((0, 0, shot.width - 1, shot.height - 1 + (radius if bleeds else 0)),
                         radius, fill=255)
    return shot, mask, (W - width) // 2, y0


def build(name, top, headline, highlight, sub, bottom=NAV_BAR_START):
    raw = Image.open(RAW / f"Screenshot_20260929_{name}.jpg").convert("RGB")
    img = background()
    shot, mask, x, y = frame(raw, top, bottom)
    # Soft shadow + thin outline so the dark UI separates from the background.
    shadow = Image.new("L", (W, H), 0)
    shadow.paste(mask, (x, y + 12))
    img.paste((0, 0, 0), (0, 0), shadow.filter(ImageFilter.GaussianBlur(28)).point(lambda v: v * 0.8))
    outline = Image.new("L", (W, H), 0)
    ImageDraw.Draw(outline).rounded_rectangle(
        (x - 3, y - 3, x + shot.width + 2, y + shot.height + 2 + (44 if shot.height >= H - y - 1 else 0)),
        47, fill=255)
    img.paste((70, 26, 30), (0, 0), outline)
    img.paste(shot, (x, y), mask)
    draw_caption(ImageDraw.Draw(img), headline, highlight, sub)
    return img


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*.png"):
        old.unlink()
    for i, spec in enumerate(SHOTS, 1):
        build(*spec).save(OUT / f"{i}_en-US.png", optimize=True)
        print(f"{i}_en-US.png")
