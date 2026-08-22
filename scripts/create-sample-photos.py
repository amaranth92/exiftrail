from pathlib import Path
from PIL import Image, ImageDraw
from PIL.TiffImagePlugin import IFDRational

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "public" / "samples"
OUT.mkdir(parents=True, exist_ok=True)

no_gps = Image.new("RGB", (640, 420), "#64748b")
draw = ImageDraw.Draw(no_gps)
draw.text((42, 182), "No GPS Test Photo", fill="white")
no_gps_exif = Image.Exif()
no_gps_exif[0x9003] = "2024:01:31 18:00:00"
no_gps.save(OUT / "00-no-gps.jpg", exif=no_gps_exif)

points = [
    ("01-perth", -31.9523, 115.8613, "2024:02:01 09:00:00", "#0ea5e9"),
    ("02-fremantle", -32.0569, 115.7439, "2024:02:01 13:30:00", "#14b8a6"),
    ("03-rottnest", -32.0060, 115.5120, "2024:02:02 10:15:00", "#f59e0b"),
    ("04-margaret-river", -33.9536, 115.0739, "2024:02:03 16:10:00", "#ef4444"),
    ("05-albany", -35.0269, 117.8837, "2024:02:05 11:20:00", "#8b5cf6"),
]


def dms(value):
    value = abs(value)
    degrees = int(value)
    minutes_float = (value - degrees) * 60
    minutes = int(minutes_float)
    seconds = round((minutes_float - minutes) * 60 * 10000)
    return (IFDRational(degrees, 1), IFDRational(minutes, 1), IFDRational(seconds, 10000))


for name, lat, lng, taken_at, color in points:
    image = Image.new("RGB", (640, 420), color)
    draw = ImageDraw.Draw(image)
    draw.text((42, 182), name.replace("-", " ").title(), fill="white")

    exif = Image.Exif()
    exif[0x9003] = taken_at
    exif[0x8825] = {
        1: "S" if lat < 0 else "N",
        2: dms(lat),
        3: "W" if lng < 0 else "E",
        4: dms(lng),
    }
    image.save(OUT / f"{name}.jpg", exif=exif)

print(f"wrote {len(points) + 1} sample photos to {OUT}")
