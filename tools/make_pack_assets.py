"""Builds GhastBreath's resource pack files into its pack/ folder (resource-pack/tools/build_pack.py puts every
plugin's pack/ folder into the one pack the server sends).

- textures/entity/equipment/happy_ghast_body/ghast_face.png: the regular ghast's body, laid out for the
  happy ghast harness model. That model's 16x16x16 box (texture offset 0,0, 64x64 map, drawn here at 2x
  like the vanilla textures) sits exactly around the happy ghast's body, and both mobs use the same box
  layout, so the ghast body's six faces copy straight across. The goggles part of the map stays clear.
- equipment/ghast_face.json: that body alone (a changed ghast with no harness).
- equipment/ghast_<colour>_harness.json: that body with the vanilla harness drawn over it.

Run: python make_pack_assets.py [pack-folder]   (needs Pillow and the 26.3 client jar in the Fabric Loom cache)
pack-folder is the folder holding assets/; by default this project's pack/.
"""
import io
import json
import pathlib
import sys
import zipfile

from PIL import Image

CLIENT_JAR = pathlib.Path.home() / ".gradle/caches/fabric-loom/26.3/minecraft-client.jar"
DEFAULT_PACK = pathlib.Path(__file__).resolve().parents[1] / "pack"
PACK = (pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PACK) / "assets/ghastbreath"
COLOURS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
           "cyan", "purple", "blue", "brown", "green", "red", "black"]
SCALE = 2  # vanilla ghast textures are 2x their 64-wide UV map
# The box's faces on the map, in UV pixels (x, y, w, h): top, bottom, then the four sides
FACES = [(16, 0, 16, 16), (32, 0, 16, 16), (0, 16, 64, 16)]


def main():
    with zipfile.ZipFile(CLIENT_JAR) as jar:
        ghast = Image.open(io.BytesIO(jar.read("assets/minecraft/textures/entity/ghast/ghast.png"))).convert("RGBA")
        harness = Image.open(io.BytesIO(jar.read(
            "assets/minecraft/textures/entity/equipment/happy_ghast_body/red_harness.png"))).convert("RGBA")
    assert ghast.size == (64 * SCALE, 32 * SCALE), ghast.size
    assert harness.size == (64 * SCALE, 64 * SCALE), harness.size

    out = Image.new("RGBA", harness.size, (0, 0, 0, 0))
    for x, y, w, h in FACES:
        box = (x * SCALE, y * SCALE, (x + w) * SCALE, (y + h) * SCALE)
        out.paste(ghast.crop(box), box[:2])

    # Check: every pixel of the box's faces is opaque (so the happy face can't show through), and nothing
    # outside them is drawn (the goggles must stay invisible)
    inside = set()
    for x, y, w, h in FACES:
        inside.update((px, py) for px in range(x * SCALE, (x + w) * SCALE) for py in range(y * SCALE, (y + h) * SCALE))
    for py in range(out.size[1]):
        for px in range(out.size[0]):
            alpha = out.getpixel((px, py))[3]
            assert alpha == (255 if (px, py) in inside else 0), (px, py, alpha)

    texture = PACK / "textures/entity/equipment/happy_ghast_body/ghast_face.png"
    texture.parent.mkdir(parents=True, exist_ok=True)
    out.save(texture, optimize=True)
    assert list(Image.open(texture).convert("RGBA").getdata()) == list(out.getdata()), "PNG didn't read back the same"

    equipment = PACK / "equipment"
    equipment.mkdir(parents=True, exist_ok=True)
    body = {"texture": "ghastbreath:ghast_face"}
    write(equipment / "ghast_face.json", {"layers": {"happy_ghast_body": [body]}})
    for colour in COLOURS:
        write(equipment / f"ghast_{colour}_harness.json",
              {"layers": {"happy_ghast_body": [body, {"texture": f"minecraft:{colour}_harness"}]}})

    # The face, as text: '#' dark, '+' grey, '-' light grey, '.' white
    for py in range(16 * SCALE, 32 * SCALE):
        print("".join(shade(out.getpixel((px, py))) for px in range(16 * SCALE, 32 * SCALE)))
    print("wrote", texture, "and", 1 + len(COLOURS), "equipment files")


def shade(pixel):
    r = pixel[0]
    return "#" if r < 130 else "+" if r < 200 else "-" if r < 225 else "."


def write(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
