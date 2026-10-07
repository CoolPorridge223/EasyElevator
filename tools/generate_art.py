"""Generate the detailed model and texture assets of EasyElevator.

Run with Python 3, no third-party dependencies:

    python tools/generate_art.py

This script owns everything the player *sees*:

* ``models/block/*.json``  - rail, the nine landing-door frame models, the door item icon;
* ``models/item/*.json``   - the four cabin icons and the two block item models;
* ``textures/block/*.png`` - brushed-steel frame plate, dark anodised door leaf, the two
  cabin icon plates, the powerful cabin's red load plate, the door-header display band and the
  machined dark plate;
* ``textures/entity/cabin.png`` - the 4x4 material atlas the cabin renderer addresses;
* ``icon.png``             - the mod list icon.

Everything else (blockstates, lang, recipes, loot tables, sounds) is written by
``tools/generate_data.py``.  Both scripts are deterministic: running them twice produces
byte-identical files, so a diff after a rebuild means somebody really changed the art.

Colour vocabulary used by every material, so the whole mod reads as one machine:

===== ========== ===========================================================
name  base RGB   used for
===== ========== ===========================================================
STEEL (200,205,211) light brushed steel - door frame, rail guide, cabin walls
DARK  ( 74, 79, 85) dark anodised - door leaf, skirting, accents
PLATE ( 62, 67, 72) machined dark plate - plinth, sill, rail base, brackets
GOLD  (227,193,121) high-speed cabin plate
RED   (198, 74, 80) powerful cabin plate
GLASS (191,216,238) observation cabin pane
===== ========== ===========================================================
"""
from pathlib import Path
import json, math, random, re, struct, zlib

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources'
ASSETS = ROOT / 'assets/easyelevator'
BLOCK_TEX = ASSETS / 'textures/block'
ENTITY_TEX = ASSETS / 'textures/entity'

#: Side of every block texture, in pixels.  Block models map the whole image onto each
#: face, so a larger image simply means finer detail; 32 is the sweet spot between
#: "still reads as pixel art" and "the cabin does not look like a flat colour card".
BLOCK_SIZE = 32
#: Cabin entity atlas: 4x4 tiles of 32 px.  Entity textures are bound directly (the
#: render layer uses nearest filtering without mipmaps), so tiles never bleed into each other.
TILE = 32
ATLAS = TILE * 4

#: Outer radius of the round call-button disc drawn by :func:`tile_button`, in tile pixels.
#: ``CabinRenderer.buttonFaceUv`` maps the disc onto one face only and the *plain* left margin of
#: the same cell onto the other five, so the disc has to stay clear of that margin -- otherwise the
#: button sides grow half a circle again (the 2.2.0 report was "a circle on every face").
#: :func:`check_button_face` asserts the pair still agrees; the margin itself lives in Java.
BUTTON_DISC_PX = 9.2


# --------------------------------------------------------------------------------------
# pixel buffer
# --------------------------------------------------------------------------------------
def shade(color, delta):
    """Return ``color`` brightened by ``delta`` (clamped); 3-tuples come back opaque."""
    body = tuple(max(0, min(255, c + delta)) for c in color[:3])
    return body + ((color[3],) if len(color) > 3 else (255,))


class Canvas:
    """RGBA pixel buffer.  Every drawing helper uses inclusive coordinates."""

    def __init__(self, width, height, color=(0, 0, 0, 0)):
        self.w = width
        self.h = height
        self.px = [[list(color) for _ in range(width)] for _ in range(height)]

    def set(self, x, y, color):
        if 0 <= x < self.w and 0 <= y < self.h:
            if len(color) == 3:
                color = (color[0], color[1], color[2], 255)  # RGB shorthand is opaque
            self.px[y][x] = list(color)

    def get(self, x, y):
        return tuple(self.px[y][x])

    def shift(self, x, y, delta):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.set(x, y, shade(self.get(x, y), delta))

    def rect(self, x0, y0, x1, y1, color):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, color)

    def hline(self, y, x0, x1, color):
        for x in range(x0, x1 + 1):
            self.set(x, y, color)

    def vline(self, x, y0, y1, color):
        for y in range(y0, y1 + 1):
            self.set(x, y, color)

    def frame(self, x0, y0, x1, y1, color):
        self.hline(y0, x0, x1, color)
        self.hline(y1, x0, x1, color)
        self.vline(x0, y0, y1, color)
        self.vline(x1, y0, y1, color)

    def sub(self, x0, y0, w, h):
        """Return a view drawing into this canvas at ``(x0, y0)`` with local coordinates."""
        return View(self, x0, y0, w, h)

    def fill(self, color):
        self.rect(0, 0, self.w - 1, self.h - 1, color)

    def save(self, path):
        write_png(path, self)


class View(Canvas):
    """A rectangular window onto a parent canvas; drawing uses window-local coordinates."""

    def __init__(self, parent, x0, y0, w, h):
        self.parent = parent
        self.x0 = x0
        self.y0 = y0
        self.w = w
        self.h = h

    def set(self, x, y, color):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.parent.set(self.x0 + x, self.y0 + y, color)

    def get(self, x, y):
        return self.parent.get(self.x0 + x, self.y0 + y)


def write_png(path, canvas):
    """Write ``canvas`` as an 8-bit RGBA PNG (filter 0 on every row)."""
    rows = canvas.px  # only top level Canvases are written; Views are drawing windows
    height = len(rows)
    width = len(rows[0])
    raw = bytearray()
    for row in rows:
        raw.append(0)
        for pixel in row:
            raw += bytes(pixel)

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))

    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data + chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b''))


# --------------------------------------------------------------------------------------
# shared metal helpers
# --------------------------------------------------------------------------------------
def brushed(v, base, rnd, *, band=0, streaks=5, edges=0):
    """Fill a tile with vertically shaded, horizontally brushed metal.

    ``band``   extra brightness added by a soft cylindrical highlight in the middle of the tile;
    ``streaks`` number of brighter/darker 1 px rows that read as brush marks;
    ``edges``  how much the top and bottom rows are darkened (a joint / shadow line).
    """
    rows = v.h
    for y in range(rows):
        curve = band * math.sin(math.pi * y / max(1, rows - 1)) if band else 0
        delta = int(round(curve)) + rnd.randint(-2, 2)
        if edges:
            if y < 2:
                delta -= edges
            elif y >= rows - 2:
                delta -= edges // 2
        for x in range(v.w):
            v.set(x, y, shade(base, delta))
    for _ in range(streaks):
        y = rnd.randrange(2, rows - 2)
        v.hline(y, 0, v.w - 1, shade(base, rnd.choice((-8, -6, 6, 8)) + band // 3))


def vertical_brush(v, base, rnd, *, band=0, streaks=5):
    """Same as :func:`brushed` but the brush marks run vertically (for the door leaf)."""
    cols = v.w
    for x in range(cols):
        curve = band * math.sin(math.pi * x / max(1, cols - 1)) if band else 0
        delta = int(round(curve)) + rnd.randint(-2, 2)
        for y in range(v.h):
            v.set(x, y, shade(base, delta))
    for _ in range(streaks):
        x = rnd.randrange(1, cols - 1)
        v.vline(x, 0, v.h - 1, shade(base, rnd.choice((-7, -5, 5, 7))))


# --------------------------------------------------------------------------------------
# block textures
# --------------------------------------------------------------------------------------
def tex_frame_plate():
    """``blank.png`` - the bright brushed plate behind every door frame and the rail guide."""
    base = (200, 205, 211)
    rnd = random.Random(1506)
    v = Canvas(BLOCK_SIZE, BLOCK_SIZE)
    brushed(v, base, rnd, band=12, streaks=7, edges=16)
    # Horizontal section joint across the middle: two faint lines, as on a stacked jamb.
    mid = BLOCK_SIZE // 2
    v.hline(mid - 1, 0, BLOCK_SIZE - 1, shade(base, -26))
    v.hline(mid, 0, BLOCK_SIZE - 1, shade(base, 18 + 12))
    # Cylindrical falloff: darker at both vertical edges, one catch-light just inside the left.
    for y in range(BLOCK_SIZE):
        for x in (0, 1):
            v.shift(x, y, -14 + 4 * x)
        v.shift(BLOCK_SIZE - 1, y, -20)
        v.shift(BLOCK_SIZE - 2, y, -8)
    v.vline(2, 0, BLOCK_SIZE - 1, shade(base, 12))
    # A hairline shadow at the very top and bottom: the joint between stacked blocks.
    v.hline(0, 0, BLOCK_SIZE - 1, shade(base, -34))
    v.hline(BLOCK_SIZE - 1, 0, BLOCK_SIZE - 1, shade(base, -30))
    return v


def tex_door_leaf():
    """``blank_door.png`` - light brushed-steel sliding leaf: panel rails, mid groove, kick plate.

    Deliberately light (the user asked for "门改白一点"): a near-black leaf reads as a flat slab, so
    neither the panel edges nor the sliding motion are visible.  The bright catch-lights just inside
    both vertical edges double as the "folded edge" line that makes the motion legible on the
    landing leaves (see ``LandingDoorRenderer`` / ``LeafUv``).
    """
    base = (172, 178, 186)
    rnd = random.Random(1505)
    v = Canvas(BLOCK_SIZE, BLOCK_SIZE)
    vertical_brush(v, base, rnd, band=8, streaks=8)
    # Vertical gradient: the top of a leaf catches more light than the bottom.
    for y in range(BLOCK_SIZE):
        for x in range(BLOCK_SIZE):
            v.shift(x, y, int(round(8 - 22 * y / (BLOCK_SIZE - 1))))
    # Recessed panel: shadow border with a bright inner reveal, like a folded sheet-metal door.
    v.frame(0, 0, BLOCK_SIZE - 1, BLOCK_SIZE - 1, shade(base, -34))
    v.frame(1, 1, BLOCK_SIZE - 2, BLOCK_SIZE - 2, shade(base, 10))
    v.frame(2, 2, BLOCK_SIZE - 3, BLOCK_SIZE - 3, shade(base, -20))
    # Mid groove where the two leaves meet.
    mid = BLOCK_SIZE * 17 // 32
    v.hline(mid, 2, BLOCK_SIZE - 3, shade(base, -30))
    v.hline(mid + 1, 2, BLOCK_SIZE - 3, shade(base, 18))
    # Kick plate along the bottom (the image bottom maps to the bottom of the leaf).
    kick = BLOCK_SIZE * 24 // 32
    v.rect(2, kick, BLOCK_SIZE - 3, BLOCK_SIZE - 4, shade(base, 10))
    v.hline(kick - 1, 2, BLOCK_SIZE - 3, shade(base, 26))
    for _ in range(4):
        v.hline(rnd.randrange(kick, BLOCK_SIZE - 3), 3, BLOCK_SIZE - 4, shade(base, rnd.choice((-10, 10))))
    # Folded-edge catch-lights on both vertical edges (the motion cue, see the docstring).
    v.vline(3, 2, BLOCK_SIZE - 3, shade(base, 26))
    v.vline(BLOCK_SIZE - 4, 2, BLOCK_SIZE - 3, shade(base, 26))
    return v


def tex_machined_plate():
    """``blank_plate.png`` - dark machined plate: plinths, sills, rail base and brackets."""
    base = (62, 67, 72)
    rnd = random.Random(1600)
    v = Canvas(BLOCK_SIZE, BLOCK_SIZE)
    brushed(v, base, rnd, band=8, streaks=6, edges=10)
    # Faint linishing lines: a machined face, not a grid.
    for _ in range(3):
        y = rnd.randrange(4, BLOCK_SIZE - 4)
        v.hline(y, 0, BLOCK_SIZE - 1, shade(base, rnd.choice((-9, 9))))
    # Chamfer: bright on the top/left, shadow on the bottom/right.
    v.hline(0, 0, BLOCK_SIZE - 1, shade(base, 30))
    v.vline(0, 0, BLOCK_SIZE - 1, shade(base, 24))
    v.hline(BLOCK_SIZE - 1, 0, BLOCK_SIZE - 1, shade(base, -22))
    v.vline(BLOCK_SIZE - 1, 0, BLOCK_SIZE - 1, shade(base, -22))
    v.hline(1, 0, BLOCK_SIZE - 1, shade(base, 14))
    v.vline(1, 0, BLOCK_SIZE - 1, shade(base, 12))
    for _ in range(14):
        v.shift(rnd.randrange(2, BLOCK_SIZE - 2), rnd.randrange(2, BLOCK_SIZE - 2), rnd.choice((-7, 7)))
    return v


def tex_display_screen():
    """``blank_screen.png`` - the lit indicator screen recessed into the door lintel.

    Drawn for a face that is 16 units wide and only ~2.5 units tall, so it is compressed
    about 6x vertically: only *coarse* vertical structure survives.  Horizontally it is
    deliberately uniform - the same image is mapped onto the lintel of all three door
    columns, and a uniform pattern makes those three faces read as one continuous panel.
    The bezel around the screen is real geometry (``header_bands``), not texture.
    """
    v = Canvas(BLOCK_SIZE, BLOCK_SIZE)
    rows = BLOCK_SIZE
    for y in range(rows):
        t = y / (rows - 1)
        # Dark glass: a soft sheen across the upper third, falling off towards the bottom.
        sheen = math.exp(-((t - 0.28) ** 2) / 0.02)
        glow = 0.65 - abs(t - 0.5)          # faint warm light bleeding in from the bezel edges
        base = 10 + 14 * sheen + 6 * (glow ** 3)
        red = base + 26 * (glow ** 4)
        green = base + 5 * (glow ** 4)
        blue = base + 8 * (glow ** 4)
        for x in range(rows):
            v.set(x, y, (int(red), int(green), int(blue), 255))
    # Two broad glass reflections (1 px lines would alias away at this compression).
    for y, strength in ((7, 12), (8, 8), (22, 7), (23, 5)):
        v.hline(y, 0, rows - 1, shade(v.get(0, y), strength))
    # Crisp dark top and bottom rows: the shadow line where the screen meets its bezel.
    v.hline(0, 0, rows - 1, (6, 7, 9, 255))
    v.hline(rows - 1, 0, rows - 1, (6, 7, 9, 255))
    return v


def tex_speed_plate():
    """``blank_speed.png`` - brushed gold plate carrying three speed chevrons."""
    base = (227, 193, 121)
    rnd = random.Random(1507)
    v = Canvas(BLOCK_SIZE, BLOCK_SIZE)
    brushed(v, base, rnd, band=14, streaks=6, edges=18)
    dark = (143, 111, 40)
    light = (255, 240, 192)
    # Three ">" chevrons pointing right, 2 px strokes so they stay readable in the icon.
    for index in range(3):
        cx = 6 + index * 7
        for step in range(7):
            x = cx + step
            if x >= BLOCK_SIZE - 3:
                break
            for arm in (-1, 1):
                y = 16 + arm * step
                v.set(x, y, dark)
                v.set(x, y + arm, dark)
                v.set(x, y - arm, dark)
                v.set(x - 1, y - arm, light)
    v.frame(0, 0, BLOCK_SIZE - 1, BLOCK_SIZE - 1, shade(base, -46))
    v.frame(1, 1, BLOCK_SIZE - 2, BLOCK_SIZE - 2, shade(base, 22))
    for y in range(2, BLOCK_SIZE - 2):
        v.shift(2, y, 12)
        v.shift(BLOCK_SIZE - 3, y, -10)
    return v


def tex_powerful_plate():
    """``blank_powerful.png`` - red load plate carrying three standing figures.

    The powerful cabin icon keeps the high-speed plate's construction (brushed base, machined
    frame, one bold group of marks) and only changes what the marks say: the car is *not* faster
    than the others, it carries more people, so the three right-pointing speed chevrons become
    three standing figures instead.  Red is the "load / warning" colour of the family (gold is
    speed, glass-blue is the view), which keeps the four inventory icons readable side by side.

    The figures are drawn oversized and blocky on purpose: the plate is mapped onto the 1/3-scale
    mini cabin of ``item/cabin_body.json``, where each figure lands on a few inventory pixels.
    """
    base = (198, 74, 80)
    rnd = random.Random(1509)
    v = Canvas(BLOCK_SIZE, BLOCK_SIZE)
    brushed(v, base, rnd, band=14, streaks=6, edges=18)
    dark = (118, 22, 28)
    light = (252, 172, 178)
    # One figure = head, neck, shoulders, tapering body, two legs (8 px wide, 23 px tall).
    for cx in (6, 16, 26):
        v.rect(cx - 1, 5, cx + 2, 8, dark)      # head
        v.rect(cx, 9, cx + 1, 9, dark)          # neck
        v.rect(cx - 3, 10, cx + 4, 12, dark)    # shoulders / arms
        v.rect(cx - 2, 13, cx + 3, 19, dark)    # torso
        v.rect(cx - 2, 20, cx - 1, 27, dark)    # left leg
        v.rect(cx + 1, 20, cx + 2, 27, dark)    # right leg
        v.vline(cx - 3, 10, 12, light)          # catch-light so a figure is not a flat blob
        v.vline(cx - 2, 13, 19, light)
    v.frame(0, 0, BLOCK_SIZE - 1, BLOCK_SIZE - 1, shade(base, -46))
    v.frame(1, 1, BLOCK_SIZE - 2, BLOCK_SIZE - 2, shade(base, 22))
    for y in range(2, BLOCK_SIZE - 2):
        v.shift(2, y, 12)
        v.shift(BLOCK_SIZE - 3, y, -10)
    return v


def tex_glass_plate():
    """``blank_glass.png`` - laminated pane with a soft sheen and a plain steel rim.

    Every colour stays low-contrast and mid-dark on purpose.  The plate used to carry two hard
    diagonal reflections plus a (226,240,252) mitre line, i.e. it painted its own highlights; the
    framed glass door draws that same inner area at 20..30 % alpha, so those highlights landed on
    top of whatever was behind the door and read as white glare through the glass.  A pane should
    modulate what is behind it - brightness comes from the world, not from the texture.
    """
    base = (176, 192, 208)
    rnd = random.Random(1508)
    v = Canvas(BLOCK_SIZE, BLOCK_SIZE)
    for y in range(BLOCK_SIZE):
        for x in range(BLOCK_SIZE):
            # Soft corner-to-corner sheen: brighter top-left, cooler bottom-right.
            delta = int(round(10 - 16 * (x + y) / (2 * (BLOCK_SIZE - 1))))
            v.set(x, y, shade(base, delta + rnd.randint(-2, 2)))
    # Two wide, gentle diagonal reflections (soft: no bright band on top of the scenery).
    for offset in (6, -12):
        for step in range(BLOCK_SIZE * 2):
            x = step - BLOCK_SIZE // 2
            y = x + offset + 14
            for thick in range(3):
                v.set(x, y + thick, shade(base, 12 - thick * 4))
    # Steel rim: plain, one shade darker than the pane, no bright mitre.
    v.frame(0, 0, BLOCK_SIZE - 1, BLOCK_SIZE - 1, (142, 156, 172, 255))
    v.frame(1, 1, BLOCK_SIZE - 2, BLOCK_SIZE - 2, (158, 174, 190, 255))
    v.frame(2, 2, BLOCK_SIZE - 3, BLOCK_SIZE - 3, (164, 180, 196, 255))
    for _ in range(14):
        v.shift(rnd.randrange(4, BLOCK_SIZE - 4), rnd.randrange(4, BLOCK_SIZE - 4), rnd.choice((-8, 8)))
    return v


# --------------------------------------------------------------------------------------
# cabin entity atlas (4x4 tiles)
# --------------------------------------------------------------------------------------
def tile_wall(v, rnd):
    """Cabin wall panel: brushed steel inside a recessed panel joint."""
    base = (196, 201, 207)
    brushed(v, base, rnd, band=10, streaks=5, edges=12)
    v.frame(0, 0, TILE - 1, TILE - 1, shade(base, -30))
    v.frame(1, 1, TILE - 2, TILE - 2, shade(base, 16))
    v.frame(2, 2, TILE - 3, TILE - 3, shade(base, -12))
    v.hline(3, 3, TILE - 4, shade(base, 22))


def tile_trim(v, rnd):
    """Mid steel for posts, coves and jambs: one soft highlight along the top."""
    base = (169, 175, 182)
    brushed(v, base, rnd, band=8, streaks=4, edges=10)
    v.hline(1, 0, TILE - 1, shade(base, 24))
    v.hline(TILE - 2, 0, TILE - 1, shade(base, -20))


def tile_dark(v, rnd):
    """Dark anodised metal: skirting, coach lines, door frame accents."""
    base = (68, 72, 77)
    vertical_brush(v, base, rnd, band=6, streaks=6)
    v.hline(1, 0, TILE - 1, shade(base, 22))
    v.hline(TILE - 2, 0, TILE - 1, shade(base, -16))


def tile_floor(v, rnd):
    """Cabin floor: dark speckled vinyl with 8 px tile joints."""
    base = (90, 95, 101)
    for y in range(TILE):
        for x in range(TILE):
            v.set(x, y, shade(base, rnd.randint(-9, 9)))
    for i in range(0, TILE, 8):
        v.hline(i, 0, TILE - 1, shade(base, -26))
        v.vline(i, 0, TILE - 1, shade(base, -26))
    for _ in range(120):
        v.shift(rnd.randrange(TILE), rnd.randrange(TILE), rnd.choice((-16, 16)))
    v.frame(0, 0, TILE - 1, TILE - 1, shade(base, -30))


def tile_ceiling(v, rnd):
    """Off-white ceiling panel with a faint 8 px grid."""
    base = (228, 231, 234)
    brushed(v, base, rnd, band=4, streaks=3, edges=8)
    for i in range(0, TILE, 8):
        v.hline(i, 0, TILE - 1, shade(base, -12))
        v.vline(i, 0, TILE - 1, shade(base, -12))
    v.frame(0, 0, TILE - 1, TILE - 1, shade(base, -22))


def tile_lamp(v, rnd):
    """Warm light diffuser: bright in the middle, prismatic grid, grey vignette at the rim."""
    base = (255, 246, 226)
    for y in range(TILE):
        for x in range(TILE):
            dx = (x - TILE / 2) / (TILE / 2)
            dy = (y - TILE / 2) / (TILE / 2)
            falloff = 1 - 0.45 * (dx * dx + dy * dy)
            v.set(x, y, shade(base, int(round(-18 * (1 - falloff)))))
    for i in range(0, TILE, 6):
        v.hline(i, 0, TILE - 1, shade(base, -18))
    for i in range(0, TILE, 6):
        v.vline(i, 0, TILE - 1, shade(base, -12))
    v.frame(0, 0, TILE - 1, TILE - 1, (206, 198, 178, 255))


def tile_handrail(v, rnd):
    """Polished handrail: strong specular band through the middle."""
    base = (216, 221, 226)
    for y in range(TILE):
        for x in range(TILE):
            t = x / (TILE - 1)
            spec = 34 * math.exp(-((t - 0.32) ** 2) / 0.012)
            v.set(x, y, shade(base, int(round(spec - 16 + rnd.randint(-2, 2)))))
    v.hline(0, 0, TILE - 1, shade(base, -30))
    v.hline(TILE - 1, 0, TILE - 1, shade(base, -26))


def tile_sill(v, rnd):
    """Ribbed doorway sill / threshold."""
    base = (56, 60, 64)
    for y in range(TILE):
        for x in range(TILE):
            rib = 10 if x % 4 < 2 else -6
            v.set(x, y, shade(base, rib + rnd.randint(-2, 2)))
    v.hline(0, 0, TILE - 1, shade(base, 34))
    v.hline(TILE - 1, 0, TILE - 1, shade(base, -18))
    v.frame(0, 0, TILE - 1, TILE - 1, shade(base, -24))


def tile_panel(v, rnd):
    """Control panel face plate: light brushed metal with a fine grid."""
    base = (181, 186, 192)
    brushed(v, base, rnd, band=6, streaks=4, edges=8)
    for i in range(0, TILE, 8):
        v.hline(i, 0, TILE - 1, shade(base, -14))
        v.vline(i, 0, TILE - 1, shade(base, -10))
    v.frame(0, 0, TILE - 1, TILE - 1, shade(base, -26))


def tile_bezel(v, rnd):
    """Near-black bezel around the panel and display."""
    base = (43, 46, 50)
    vertical_brush(v, base, rnd, band=4, streaks=4)
    v.hline(0, 0, TILE - 1, shade(base, 20))


def tile_button(v, rnd):
    """Round-ish call button face."""
    base = (201, 206, 212)
    brushed(v, base, rnd, band=4, streaks=3, edges=6)
    cx = cy = TILE / 2 - 0.5
    for y in range(TILE):
        for x in range(TILE):
            d = math.hypot(x - cx, y - cy)
            if d < 9:
                v.set(x, y, shade(base, 6))
            if 8.2 <= d <= BUTTON_DISC_PX:
                v.set(x, y, shade(base, -30))
            if d < 3.4:
                v.set(x, y, shade(base, 22))
    v.hline(0, 0, TILE - 1, shade(base, 18))
    v.hline(TILE - 1, 0, TILE - 1, shade(base, -24))


def tile_glass(v, rnd):
    """Plain pane for the observation cabin: the vertex colour supplies tint and alpha.

    Deliberately neutral and only mid-dark.  The tile used to be near-white (246,250,254) and is
    drawn at a low alpha across the whole view, so under a shader pack that treats flat translucent
    surfaces as glossy (Complementary's "coated textures" / generated normals, for instance) the
    pane used to read as a white film over the scenery.  A low-albedo, low-contrast tile leaves the
    pane almost nothing to add: what you see through the glass is the world.
    """
    for y in range(TILE):
        for x in range(TILE):
            v.set(x, y, shade((182, 196, 210, 255), int(round(4 - 8 * y / (TILE - 1)))))
    for _ in range(10):
        v.shift(rnd.randrange(TILE), rnd.randrange(TILE), rnd.choice((-3, 3)))


def tile_accent(v, rnd):
    """Warm medium steel used for the back wall accent panel."""
    base = (176, 170, 162)
    brushed(v, base, rnd, band=9, streaks=4, edges=10)
    v.frame(0, 0, TILE - 1, TILE - 1, shade(base, -26))
    v.frame(1, 1, TILE - 2, TILE - 2, shade(base, 14))


def tile_door(v, rnd):
    """Cabin door panel: light brushed steel with a visible folded edge.

    Light on purpose ("门改白一点"): a near-black panel has no legible features, so the telescopic
    panels (see ``logic/SlidingDoor``) would look static even though they really translate.
    The line group near the right of the tile is the panel's folded edge: every panel is drawn
    full size and slides as a whole, so that edge is what the eye tracks as the doors part.
    """
    base = (176, 182, 190)
    vertical_brush(v, base, rnd, band=6, streaks=6)
    v.hline(1, 0, TILE - 1, shade(base, 22))
    v.hline(TILE - 2, 0, TILE - 1, shade(base, -18))
    # Folded edge: shadow, bright catch, then the panel's own edge.
    v.vline(27, 0, TILE - 1, shade(base, -30))
    v.vline(28, 0, TILE - 1, shade(base, -16))
    v.vline(29, 0, TILE - 1, shade(base, 34))
    v.vline(30, 0, TILE - 1, shade(base, 10))
    v.vline(31, 0, TILE - 1, shade(base, -34))
    v.frame(0, 0, TILE - 1, TILE - 1, shade(base, -24))


def tile_spare(v, rnd):
    """Flat spare tile: an unused atlas cell still resolves to a sensible grey."""
    base = (170, 174, 179)
    brushed(v, base, rnd, band=3, streaks=2, edges=6)


#: Atlas cell order.  ``CabinRenderer`` material ordinals must match this list.
ATLAS_TILES = [
    tile_wall,      # 0  WALL
    tile_trim,      # 1  TRIM
    tile_dark,      # 2  DARK
    tile_floor,     # 3  FLOOR
    tile_ceiling,   # 4  CEIL
    tile_lamp,      # 5  LAMP
    tile_handrail,  # 6  RAIL
    tile_sill,      # 7  SILL
    tile_panel,     # 8  PANEL
    tile_bezel,     # 9  BEZEL
    tile_button,    # 10 BUTTON
    tile_glass,     # 11 GLASS
    tile_accent,    # 12 ACCENT
    tile_door,      # 13 DOOR（轿厢门扇：带一条会随门滑动的折边）
    tile_spare,     # 14 spare
    tile_spare,     # 15 spare
]


def build_atlas():
    """Assemble the 4x4 cabin material atlas."""
    rnd = random.Random(2100)
    canvas = Canvas(ATLAS, ATLAS)
    for index, painter in enumerate(ATLAS_TILES):
        col, row = index % 4, index // 4
        painter(canvas.sub(col * TILE, row * TILE, TILE, TILE), rnd)
    return canvas


# --------------------------------------------------------------------------------------
# mod icon
# --------------------------------------------------------------------------------------
def build_icon():
    """128x128 mod list icon: a lit landing door with both call arrows."""
    size = 128
    rnd = random.Random(4242)
    v = Canvas(size, size)
    # Background: dark shaft with a soft radial glow.
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - size / 2, y - size / 2) / (size / 2)
            v.set(x, y, shade((26, 29, 34, 255), int(round(22 * max(0.0, 1 - d * d))) + rnd.randint(-2, 2)))
    # Door frame.
    frame = (176, 182, 189, 255)
    v.rect(20, 10, 107, 117, frame)
    v.rect(24, 15, 103, 117, (60, 64, 69, 255))
    # Header light box with two arrows and a floor digit.
    v.rect(24, 15, 103, 33, (34, 26, 28, 255))
    for y in range(15, 34):
        glow = 1 - abs((y - 24) / 9)
        v.hline(y, 24, 103, shade((34, 26, 28, 255), int(round(60 * glow))))
    draw_arrow(v, 38, 24, up=True, color=(255, 92, 84, 255))
    draw_arrow(v, 90, 24, up=False, color=(255, 92, 84, 255))
    v.rect(56, 17, 71, 31, (58, 20, 22, 255))
    v.frame(56, 17, 71, 31, (255, 120, 110, 255))
    draw_digit(v, 58, 18, '3', (255, 120, 110, 255))
    # Two brushed leaves with a seam.
    for x0, x1 in ((24, 62), (66, 103)):
        for y in range(34, 118):
            for x in range(x0, x1 + 1):
                t = (x - x0) / max(1, (x1 - x0))
                delta = int(round(14 * math.sin(math.pi * t) - 12 + 6 * (34 - y) / 84))
                v.set(x, y, shade((104, 110, 117, 255), delta + rnd.randint(-2, 2)))
        v.vline(x0, 34, 117, (58, 62, 67, 255))
        v.vline(x1, 34, 117, (58, 62, 67, 255))
        # Kick plate.
        v.rect(x0 + 1, 100, x1 - 1, 116, (86, 92, 99, 255))
        v.hline(99, x0 + 1, x1 - 1, (150, 157, 164, 255))
    v.rect(63, 34, 65, 117, (32, 35, 39, 255))
    # Rounded corners: punch the alpha out of the outer ring.
    radius = 18
    for y in range(size):
        for x in range(size):
            if x < radius and y < radius and math.hypot(radius - x, radius - y) > radius:
                v.set(x, y, (0, 0, 0, 0))
            elif x >= size - radius and y < radius and math.hypot(x - (size - radius - 1), radius - y) > radius:
                v.set(x, y, (0, 0, 0, 0))
            elif x < radius and y >= size - radius and math.hypot(radius - x, y - (size - radius - 1)) > radius:
                v.set(x, y, (0, 0, 0, 0))
            elif x >= size - radius and y >= size - radius and \
                    math.hypot(x - (size - radius - 1), y - (size - radius - 1)) > radius:
                v.set(x, y, (0, 0, 0, 0))
    return v


def draw_arrow(v, cx, cy, *, up, color):
    """Draw a filled triangular direction arrow centred on ``(cx, cy)``."""
    for step in range(9):
        half = 8 - step
        y = cy + step if up else cy - step
        v.hline(y, cx - half, cx + half, color)


def draw_digit(v, x, y, digit, color):
    """Draw a 9x13 seven-segment style digit."""
    segments = {
        '3': ('a', 'b', 'c', 'd', 'g'),
    }[digit]
    width, height = 9, 13
    top, mid, bottom = y, y + height // 2, y + height - 1
    if 'a' in segments:
        v.hline(top, x + 1, x + width - 2, color)
    if 'g' in segments:
        v.hline(mid, x + 1, x + width - 2, color)
    if 'd' in segments:
        v.hline(bottom, x + 1, x + width - 2, color)
    for name, sx in (('b', x + width - 1), ('c', x + width - 1), ('e', x), ('f', x)):
        if name in segments:
            v.vline(sx, top + 1 if name in 'bf' else mid + 1, mid - 1 if name in 'bf' else bottom - 1, color)


# --------------------------------------------------------------------------------------
# models
# --------------------------------------------------------------------------------------
#: Texture ids every model in this mod draws from.  Kept short on purpose: the whole mod
#: is one material family (brushed steel, dark anodised steel, machined dark plate, one
#: illuminated band), so new geometry never needs a new texture.
STEEL = 'easyelevator:block/blank'          # bright brushed steel
DARK = 'easyelevator:block/blank_door'      # dark anodised (door leaves)
PLATE = 'easyelevator:block/blank_plate'    # machined dark plate (plinths, sills, brackets)
SCREEN = 'easyelevator:block/blank_screen'   # lit indicator screen in the door lintel
GOLD = 'easyelevator:block/blank_speed'     # high-speed cabin plate
POWERFUL = 'easyelevator:block/blank_powerful'  # powerful cabin plate (red load plate)
PANE = 'easyelevator:block/blank_glass'     # observation cabin pane

#: Landing door frame metrics, in 1/16 block, mirroring block/LandingDoorGeometry.java.
#: Keeping these in lock step is what stops the frame model and the collision shape from
#: drifting apart; ``check_door_models`` below fails the build if they ever do.
FRAME = 3            # jamb width == lintel height == door thickness
DOOR_WIDTH = 48      # 3 blocks wide
LEAF_TOP = 45        # clear opening height; the lintel occupies 45..48
SEAM = 1             # visible gap between the two closed leaves
LINTEL_BOTTOM = 13            # 13: where the lintel starts inside the top row
SCREEN_BEZEL = 13.25          # bottom edge of the lit screen
SCREEN_TOP = 15.75            # top edge of the lit screen
SCREEN_INSET = 0.75           # the screen face sits this far (1/16 block) behind the door plane
MIN_SCREEN_HEIGHT = 2.4       # /16 block; below this the floor number spills over the bezel
SILL_FRONT = 0.2     # doorway sill starts this far behind the door plane (no coplanar face)
SILL_TOP = 0.6
PLINTH_TOP = 1.5     # dark plinth at the foot of each jamb

FACE_ORDER = ('north', 'south', 'east', 'west', 'up', 'down')


def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def box(a, b, texture, **per_face):
    """Build one model element.

    ``texture`` is the default for all six faces; a keyword named after a face overrides it
    (either with another texture id or with a full face spec such as ``{'texture': ..., 'uv': ...}``).
    """
    faces = {}
    for name in FACE_ORDER:
        spec = per_face.get(name, texture)
        faces[name] = {'texture': spec} if isinstance(spec, str) else dict(spec)
    return {'from': list(a), 'to': list(b), 'faces': faces}


def model(elements, *, particles=STEEL, parent='minecraft:block/block', **textures):
    """Assemble a block model: display parent, six-face texture table and elements.

    The default parent is ``minecraft:block/block``, which contributes **no geometry** and
    exactly the two things an element-only model cannot supply itself: the ``display``
    transforms every block item needs (GUI isometric tilt and 0.625 scale, third person,
    ground, item frame) and ``gui_light: side``.  Vanilla reaches it through
    ``block/cube_all -> block/cube -> block/block``; a hand-written elements-only model
    that omits it renders flat and unrotated in the inventory.  Block items therefore
    always get their transforms from here - do not drop this parent.
    """
    table = dict(textures)
    table.setdefault('particle', particles)
    out = {'parent': parent} if parent else {}
    out['textures'] = table
    out['elements'] = elements
    return out


def rail_model():
    """Elevator rail: a tiling guide-rail segment with a bolted base and two brackets.

    The column used to be a plain 6x16x6 post.  It is now a pair of guide rails with a
    machined rack between them, a bolted floor flange, and a collar bracket half way up -
    every part stays inside 3..13 in X and Z so the outline shape (and therefore the
    right-click target) still matches what the player sees.
    """
    return model([
        box([3, 0, 3], [13, 1, 13], '#plate'),            # floor flange
        box([3.6, .8, 3.6], [4.8, 1.9, 4.8], '#all'),     # four anchor bolts
        box([11.2, .8, 3.6], [12.4, 1.9, 4.8], '#all'),
        box([3.6, .8, 11.2], [4.8, 1.9, 12.4], '#all'),
        box([11.2, .8, 11.2], [12.4, 1.9, 12.4], '#all'),
        box([4, 1, 5], [6.5, 16, 11], '#all'),            # left guide rail
        box([9.5, 1, 5], [12, 16, 11], '#all'),           # right guide rail
        box([6.5, 1, 6], [9.5, 16, 10], '#plate'),        # drive rack between the guides
        box([3.2, 7, 3.6], [4.1, 9, 12.4], '#plate'),     # collar bracket (left / right)
        box([11.9, 7, 3.6], [12.8, 9, 12.4], '#plate'),
        box([4, 7, 3.2], [12, 9, 4.1], '#plate'),         # collar bracket (front / back)
        box([4, 7, 11.9], [12, 9, 12.8], '#plate'),
    ], all=STEEL, plate=PLATE)


def header_bands(x0, x1):
    """The lintel: a bezel strip, a recessed lit display screen, and a steel cap.

    The screen is **inset behind the door plane** (:data:`SCREEN_INSET`) instead of being
    flush with the jambs, so the surrounding steel reads as a bezel and the whole thing
    looks like an indicator panel rather than a painted stripe.  The renderer draws the
    floor number and the direction arrow on this very surface, so the band also has to be
    tall enough for them - ``check_door_models`` fails if it ever shrinks below
    ``MIN_SCREEN_HEIGHT``.
    """
    return [
        box([x0, LINTEL_BOTTOM, 0], [x1, SCREEN_BEZEL, FRAME], '#plate'),
        box([x0, SCREEN_BEZEL, SCREEN_INSET], [x1, SCREEN_TOP, FRAME], '#screen'),
        box([x0, SCREEN_TOP, 0], [x1, 16, FRAME], '#all'),
    ]


def jamb(x0, x1, *, plinth, collar):
    """One door jamb: optional dark plinth at the foot, optional dark collar at the head."""
    parts = []
    base = 0
    if plinth:
        parts.append(box([x0, 0, 0], [x1, PLINTH_TOP, FRAME], '#plate'))
        base = PLINTH_TOP
    if collar:
        parts.append(box([x0, base, 0], [x1, LINTEL_BOTTOM, FRAME], '#all'))
        parts.append(box([x0, LINTEL_BOTTOM, 0], [x1, 16, FRAME], '#plate'))
    else:
        parts.append(box([x0, base, 0], [x1, 16, FRAME], '#all'))
    return parts


def door_models():
    """All nine landing-door frame models, keyed by model name.

    The door is a 3x3 multi-block.  ``column`` picks the horizontal slice the model covers
    and ``level`` the vertical one, and the model is authored for ``facing=north`` (the
    corridor side is the ``north`` face, the blockstate rotates it for the other facings):

    =========== =========================================================
    model       used for
    =========== =========================================================
    left/right  level 1 - plain jamb
    middle      level 1 - the door opening, empty on purpose
    *_bottom    level 0 - jamb with plinth, plus the shared doorway sill
    top         level 2, centre column - lintel bands only
    top_left    level 2, left column - jamb collar + lintel bands
    top_right   level 2, right column - jamb collar + lintel bands
    =========== =========================================================
    """
    plain = dict(all=STEEL, plate=PLATE, screen=SCREEN)
    models = {
        # Level 1: plain jambs, nothing across the opening.
        'landing_door_frame_left': model(jamb(0, FRAME, plinth=False, collar=False), **plain),
        'landing_door_frame_right': model(jamb(16 - FRAME, 16, plinth=False, collar=False), **plain),
        'landing_door_frame_middle': model([], **plain),
        # Level 0: plinth at the foot of each jamb, sill across the whole opening.
        'landing_door_frame_left_bottom': model(
            jamb(0, FRAME, plinth=True, collar=False)
            + [box([FRAME, 0, SILL_FRONT], [16, SILL_TOP, FRAME], '#plate')], **plain),
        'landing_door_frame_middle_bottom': model(
            [box([0, 0, SILL_FRONT], [16, SILL_TOP, FRAME], '#plate')], **plain),
        'landing_door_frame_right_bottom': model(
            jamb(16 - FRAME, 16, plinth=True, collar=False)
            + [box([0, 0, SILL_FRONT], [16 - FRAME, SILL_TOP, FRAME], '#plate')], **plain),
        # Level 2: the lintel.  Its middle strip is a recessed lit screen, so the floor number
        # the renderer draws there sits *inside* an indicator panel instead of on a flat band.
        'landing_door_frame_top': model(header_bands(0, 16), **plain),
        'landing_door_frame_top_left': model(
            jamb(0, FRAME, plinth=False, collar=True) + header_bands(FRAME, 16), **plain),
        'landing_door_frame_top_right': model(
            jamb(16 - FRAME, 16, plinth=False, collar=True) + header_bands(0, 16 - FRAME), **plain),
    }
    return models


def door_item_model():
    """Item icon for the landing door: a one block wide slice of the closed door.

    A whole 3x3 door cannot be modelled literally (model coordinates stop at 32 on every
    axis), so the icon shows one bay of the assembly: both jambs, the lit lintel, the two
    leaves with their centre seam, and the sill.
    """
    leaf_inner_left = DOOR_WIDTH / 2 - SEAM / 2 - 16   # 7.5: left leaf leading edge, in this block
    leaf_inner_right = DOOR_WIDTH / 2 + SEAM / 2 - 16  # 8.5
    return model(
        jamb(0, FRAME, plinth=True, collar=True)
        + jamb(16 - FRAME, 16, plinth=True, collar=True)
        + header_bands(FRAME, 16 - FRAME)
        + [
            box([FRAME, 0, 0], [leaf_inner_left, LINTEL_BOTTOM, FRAME], '#dark'),
            box([leaf_inner_right, 0, 0], [16 - FRAME, LINTEL_BOTTOM, FRAME], '#dark'),
            box([FRAME, 0, SILL_FRONT], [16 - FRAME, SILL_TOP, FRAME], '#plate'),
        ],
        all=STEEL, plate=PLATE, screen=SCREEN, dark=DARK)


def cabin_item_model():
    """Item icon for a cabin: one third scale car, doors closed.

    A 3x3x3 cabin at scale 1/3 fits the 16 unit model box exactly.  The three cabins share
    these elements and differ only in which plate they bind to ``#shell`` / ``#wall`` / ``#door``.
    """
    return model([
        box([.5, 0, .5], [15.5, 1.2, 15.5], '#base'),          # floor
        box([.5, 14.4, .5], [15.5, 16, 15.5], '#shell'),       # roof
        box([.5, 1.2, .5], [1.9, 14.4, 15.5], '#wall'),        # left wall
        box([14.1, 1.2, .5], [15.5, 14.4, 15.5], '#wall'),     # right wall
        box([1.9, 1.2, .5], [14.1, 14.4, 1.9], '#wall'),       # back wall
        box([1.9, 1.2, 14.1], [7.3, 12.8, 15.5], '#door'),     # left leaf
        box([8.7, 1.2, 14.1], [14.1, 12.8, 15.5], '#door'),    # right leaf
        box([1.9, 12.8, 14.1], [14.1, 14.4, 15.5], '#shell', south='#lamp'),  # lit lintel
        box([1.9, 1.2, 14.1], [14.1, 2, 15.7], '#base'),       # doorway sill, proud of the leaves
    ], shell=STEEL, wall=STEEL, door=DARK, base=PLATE, lamp=SCREEN)


def cabin_icon_models():
    """Item models for the four cabins, plus the shared part model they all parent to."""
    body = cabin_item_model()
    return {
        'cabin_body': body,
        'cabin': dict(parent='easyelevator:item/cabin_body', textures={
            'shell': STEEL, 'wall': STEEL, 'door': DARK, 'base': PLATE, 'lamp': SCREEN}),
        # Only the plate changes: the high-speed car is faster, not shaped differently.
        'high_speed_cabin': dict(parent='easyelevator:item/cabin_body', textures={
            'shell': GOLD, 'wall': GOLD, 'door': DARK, 'base': PLATE, 'lamp': SCREEN}),
        # Observation car: same frame, glass panes everywhere the shell used to be solid.
        'observation_cabin': dict(parent='easyelevator:item/cabin_body', textures={
            'shell': STEEL, 'wall': PANE, 'door': PANE, 'base': PLATE, 'lamp': SCREEN}),
        # Powerful car: red load plate with the capacity pictogram.  Frame, door and sill are the
        # shared ones on purpose - the car is heavier, not bigger (its shell is identical in game).
        'powerful_cabin': dict(parent='easyelevator:item/cabin_body', textures={
            'shell': POWERFUL, 'wall': POWERFUL, 'door': DARK, 'base': PLATE, 'lamp': SCREEN}),
    }


def check_door_models(models):
    """Fail loudly if a frame model stops agreeing with ``LandingDoorGeometry``.

    Three things must hold or the mod ships a door that looks like one thing and collides
    like another:

    * the jamb footprint is exactly ``0..FRAME`` (left) / ``16-FRAME..16`` (right);
    * nothing is modelled inside the opening below the lintel, so the sliding leaves are
      never covered by static geometry;
    * the lintel starts exactly at ``LEAF_TOP = 48 - FRAME`` in the top row.
    """
    assert FRAME == 3 and DOOR_WIDTH == 48 and LEAF_TOP == 45, 'metrics drifted from LandingDoorGeometry'
    for name in ('landing_door_frame_left', 'landing_door_frame_right', 'landing_door_frame_middle',
                 'landing_door_frame_left_bottom', 'landing_door_frame_middle_bottom',
                 'landing_door_frame_right_bottom', 'landing_door_frame_top',
                 'landing_door_frame_top_left', 'landing_door_frame_top_right'):
        assert name in models, f'missing door model {name}'
    assert models['landing_door_frame_middle']['elements'] == [], 'the door opening must stay empty'
    for name in ('landing_door_frame_left', 'landing_door_frame_right'):
        want = (0, FRAME) if name.endswith('left') else (16 - FRAME, 16)
        for e in models[name]['elements']:
            span = (e['from'][0], e['to'][0])
            assert span == want, f'{name}: jamb span {span} != {want}'
    for name in ('landing_door_frame_top', 'landing_door_frame_top_left', 'landing_door_frame_top_right'):
        bottoms = {e['from'][1] for e in models[name]['elements']}
        assert LINTEL_BOTTOM in bottoms, f'{name}: lintel must start at {LINTEL_BOTTOM}'
        assert max(e['to'][1] for e in models[name]['elements']) == 16, f'{name}: lintel must reach the block top'
        # The lit screen must stay recessed (that is what makes it read as a panel rather than
        # a painted stripe) and tall enough for the floor number drawn on it.
        screens = [e for e in models[name]['elements'] if e['from'][2] > 0]
        assert len(screens) == 1, f'{name}: expected exactly one recessed screen element'
        screen = screens[0]
        assert screen['from'][2] == SCREEN_INSET, f'{name}: screen inset {screen["from"][2]} != {SCREEN_INSET}'
        height = screen['to'][1] - screen['from'][1]
        assert height >= MIN_SCREEN_HEIGHT, \
            f'{name}: screen is only {height}/16 tall; the floor number would spill over the bezel'
    # Only the bottom row may carry static geometry inside the opening, and it must be the
    # low sill: anything taller would be covered by - and z-fight with - the sliding leaves.
    for name in ('landing_door_frame_left_bottom', 'landing_door_frame_middle_bottom',
                 'landing_door_frame_right_bottom'):
        for e in models[name]['elements']:
            in_opening = e['to'][0] > FRAME and e['from'][0] < 16 - FRAME
            assert not in_opening or e['to'][1] <= SILL_TOP, \
                f'{name}: element {e["from"]}..{e["to"]} intrudes into the opening'
    # The three bottom models together must lay a continuous sill across the whole opening.
    covered = []
    for name in ('landing_door_frame_left_bottom', 'landing_door_frame_middle_bottom',
                 'landing_door_frame_right_bottom'):
        for e in models[name]['elements']:
            if e['to'][1] <= SILL_TOP and e['to'][0] > e['from'][0]:
                covered.append((e['from'][0], e['to'][0]))
    assert sorted(covered) == [(0, 13), (0, 16), (3, 16)], f'unexpected sill spans {sorted(covered)}'


def check_item_models(models):
    """Item models must stay inside the 16 unit block box and reference only real textures.

    Also enforces the ``minecraft:block/block`` parent: without it an element-only model
    has no ``display`` transforms and shows up flat and unrotated in the inventory.
    """
    known = {STEEL, DARK, PLATE, SCREEN, GOLD, POWERFUL, PANE}
    for name, spec in models.items():
        if spec.get('elements'):
            assert spec.get('parent') == 'minecraft:block/block', \
                f'{name}: element-only models need the minecraft:block/block parent for its display transforms'
        for e in spec.get('elements', []):
            for axis in range(3):
                assert 0 <= e['from'][axis] <= e['to'][axis] <= 16, f'{name}: element out of the block box'
            for face in e['faces'].values():
                ref = face['texture']
                if ref.startswith('#'):
                    continue
                assert ref in known, f'{name}: unknown texture {ref}'


# --------------------------------------------------------------------------------------
# cabin interior geometry check
# --------------------------------------------------------------------------------------
#: src/ of the project; the cabin tables live outside the resource tree.
SRC = ROOT.parents[1]
CABIN_RENDERER = SRC / 'client/java/org/DJB/easyelevator/client/CabinRenderer.java'

#: Atlas cell order; must equal the declaration order of CabinRenderer.Mat.
MAT_ORDER = ['WALL', 'TRIM', 'DARK', 'FLOOR', 'CEIL', 'LAMP', 'RAIL', 'SILL',
             'PANEL', 'BEZEL', 'BUTTON', 'GLASS', 'ACCENT', 'DOOR', 'SPARE2', 'SPARE3']

#: Shell and doorway boxes the renderer draws in code rather than from a table, mirroring
#: CabinRenderer.drawFloor / drawStandardShell / drawObservationShell / drawDoorway.  Keep in
#: step with those methods: they are what the interior parts are checked against.
#: The floor is two boxes (dark base + slightly smaller tile surface) so that the 1:15 side
#: faces do not get the tile pattern squashed into stripes.
SHELL_STANDARD = [
    ('floor base', -1.5, 0, -1.5, 1.5, .19, 1.3),
    ('floor surface', -1.49, .188, -1.49, 1.49, .2, 1.29),
    ('ceiling', -1.5, 2.8, -1.5, 1.5, 3, 1.3),
    ('left wall', -1.5, .2, -1.5, -1.3, 2.8, 1.3),
    ('right wall', 1.3, .2, -1.5, 1.5, 2.8, 1.3),
    ('back wall', -1.3, .2, -1.5, 1.3, 2.8, -1.3),
]
SHELL_OBSERVATION = [
    ('floor base', -1.5, 0, -1.5, 1.5, .19, 1.3),
    ('floor surface', -1.49, .188, -1.49, 1.49, .2, 1.29),
    ('ceiling', -1.5, 2.8, -1.5, 1.5, 3, 1.3),
    ('post back left', -1.5, .2, -1.5, -1.3, 2.8, -1.3),
    ('post back right', 1.3, .2, -1.5, 1.5, 2.8, -1.3),
    ('post front left', -1.5, .2, 1.1, -1.3, 2.8, 1.3),
    ('post front right', 1.3, .2, 1.1, 1.5, 2.8, 1.3),
]
DOORWAY = [
    ('sill', -1.302, .198, 1.11, 1.302, .23, 1.29),
    ('header', -1.302, 2.72, 1.098, 1.302, 2.802, 1.29),
]
#: Allowed extent of a cabin part, per table: standard parts stay inside the shell, the
#: observation parts are allowed out to the glass plane at +-1.4 to become window beads.
#: The bounds are the inner shell faces plus the 0.002..0.02 bite each part is allowed to
#: take inside the shell so that no two faces end up coplanar.
BOUNDS = {
    'STANDARD_PARTS': (-1.302, 1.302, .198, 2.82, -1.302, 1.09),
    'POWERFUL_PARTS': (-1.302, 1.302, .198, 2.82, -1.302, 1.09),
    'OBSERVATION_PARTS': (-1.41, 1.41, .198, 2.82, -1.41, 1.09),
}


#: The control panel is the one group of interior parts every cabin must share: the observation
#: car is glass and the powerful car is upholstered differently, but both still have to show the
#: floor number and the call buttons.  The last row-count rows of every table must therefore be
#: identical.
PANEL_ROWS = 6
#: How many parts the powerful car must add on top of the standard interior.  Enforced as ">0"
#: rather than an exact count so that adding another strengthening part stays a one-line change;
#: what must never happen is shipping a "powerful" car that looks exactly like the standard one
#: (the 2.2.1 requirement was "keep the original look, add elements that read as more capacity").
MIN_POWERFUL_EXTRAS = 1


def parse_cabin_parts():
    """Read the material order and the cabin part tables straight out of the Java source.

    Parsing the source instead of duplicating the numbers here is deliberate: the tables are
    the single source of truth for the interior, and this way a typo in Java fails the art
    build instead of silently shipping two different designs.
    """
    text = CABIN_RENDERER.read_text(encoding='utf-8')
    enum_body = text.split('private enum Mat {', 1)[1].split('}', 1)[0]
    enum_body = re.sub(r'//[^\n]*', '', enum_body)  # drop the trailing per-row comments
    names = [tok.strip() for tok in enum_body.split(';', 1)[0].split(',') if tok.strip()]
    tables = {}
    for name in ('STANDARD_PARTS', 'POWERFUL_PARTS', 'OBSERVATION_PARTS'):
        body = text.split(f'{name}={{', 1)[1].split('\n    };', 1)[0]
        rows = []
        for match in re.finditer(r'\{([^{}]*)\}', body):
            tokens = [t.strip() for t in match.group(1).split(',')]
            assert len(tokens) == 8, f'{name}: row {match.group(1)} needs 8 fields'
            rows.append([float(t.rstrip('f')) for t in tokens])
        tables[name] = rows
    return names, tables


def _face_pairs(a, b):
    """Yield (axis, plane, direction) for every face plane the two boxes share."""
    lo_a, hi_a = a[:3], a[3:]
    lo_b, hi_b = b[:3], b[3:]
    for axis in range(3):
        for side, plane in (('min', 0), ('max', 1)):
            value = (lo_a if side == 'min' else hi_a)[axis]
            for other_side in ('min', 'max'):
                other = (lo_b if other_side == 'min' else hi_b)[axis]
                if abs(value - other) < 1e-6:
                    yield axis, value, side, other_side


def _overlaps(a, b, axis):
    """True when the two boxes' extents overlap on the two axes other than ``axis``."""
    others = [i for i in range(3) if i != axis]
    for i in others:
        if min(a[3 + i], b[3 + i]) - max(a[i], b[i]) <= 1e-6:
            return False
    return True


def check_cabin_parts():
    """Validate the three cabin interior tables against the invariants of CabinRenderer.

    Raises ``AssertionError`` with the offending rows, so ``python tools/generate_art.py``
    doubles as the geometry regression test for the cabin (the Java equivalent of the
    ``geometryTest`` gradle task, which only covers the landing door).
    """
    names, tables = parse_cabin_parts()
    assert names == MAT_ORDER, f'CabinRenderer.Mat order {names} != atlas order {MAT_ORDER}'
    assert len(ATLAS_TILES) == len(MAT_ORDER), 'atlas tile count and material count differ'
    # Every cabin must carry the same control panel as the standard one: without it the red floor
    # number is drawn on thin air (the 1.5.6 report was exactly that), and the observation car is
    # glass while the powerful car is upholstered differently - neither may lose the panel.
    standard_tail = tables['STANDARD_PARTS'][-PANEL_ROWS:]
    for other in ('POWERFUL_PARTS', 'OBSERVATION_PARTS'):
        tail = tables[other][-PANEL_ROWS:]
        assert standard_tail == tail, (
            'the last %d rows of STANDARD_PARTS and %s must be the identical control panel;\n'
            '  standard: %s\n  %s: %s' % (PANEL_ROWS, other, standard_tail, other, tail))
    # The powerful car is "the standard car plus strengthening parts": it must keep every standard
    # interior row verbatim (that is the 2.2.1 requirement "keep the basic look") and then add at
    # least one extra row of its own (otherwise the model would be indistinguishable in game).
    standard_body = tables['STANDARD_PARTS'][:-PANEL_ROWS]
    extras = len(tables['POWERFUL_PARTS']) - PANEL_ROWS - len(standard_body)
    assert tables['POWERFUL_PARTS'][:len(standard_body)] == standard_body, (
        'the first %d rows of POWERFUL_PARTS must be STANDARD_PARTS verbatim (the standard '
        'interior is kept, the strengthening parts are appended after it);\n'
        '  standard: %s\n  powerful: %s'
        % (len(standard_body), standard_body, tables['POWERFUL_PARTS'][:len(standard_body)]))
    assert extras >= MIN_POWERFUL_EXTRAS, (
        'POWERFUL_PARTS adds %d part rows on top of the standard interior, but at least %d is '
        'required: the powerful car has to look like it carries more people'
        % (extras, MIN_POWERFUL_EXTRAS))
    for table, rows in tables.items():
        bounds = BOUNDS[table]
        assert rows, f'{table} is empty'
        for row in rows:
            x0, y0, z0, x1, y1, z1, mat, glow = row
            where = f'{table} {row}'
            assert x0 <= x1 and y0 <= y1 and z0 <= z1, f'{where}: inverted box'
            assert mat == int(mat) and 0 <= mat < len(MAT_ORDER), f'{where}: bad material {mat}'
            assert glow in (0, 1), f'{where}: emissive flag must be 0 or 1'
            assert bounds[0] - 1e-6 <= x0 and x1 <= bounds[1] + 1e-6, f'{where}: out of X bounds'
            assert bounds[2] - 1e-6 <= y0 and y1 <= bounds[3] + 1e-6, f'{where}: out of Y bounds'
            assert bounds[4] - 1e-6 <= z0 and z1 <= bounds[5] + 1e-6, f'{where}: out of Z bounds'
        # Coplanar face check: no two boxes may share a face plane with overlapping extents,
        # because the cabin is drawn on a layer with culling disabled.
        #
        # Shell-against-shell pairs are exempt: the five shell boxes meet exactly at the inner
        # corners (floor top Y=0.2 == wall bottom Y=0.2 and so on), and those junctions sit
        # inside the solid corner where neither face is visible.  That geometry is unchanged
        # since 1.3.0 and is verified in game; everything else - every interior part and every
        # doorway part, against the shell and against each other - must stay clear of it.
        parts = [(f'{table}[{i}]', [r[0], r[1], r[2], r[3], r[4], r[5]]) for i, r in enumerate(rows)]
        # The powerful car is opaque like the standard one - only the observation car swaps the
        # side/back walls for glass, so it is the one that gets the four-post shell.
        shell = [(name, [float(c) for c in coords]) for name, *coords in
                 (SHELL_OBSERVATION if table == 'OBSERVATION_PARTS' else SHELL_STANDARD)]
        doorway = [(name, [float(c) for c in coords]) for name, *coords in DOORWAY]
        boxes = parts + doorway + shell
        for i in range(len(boxes)):
            for j in range(i + 1, len(boxes)):
                a_name, a = boxes[i]
                b_name, b = boxes[j]
                if a in [s for _, s in shell] and b in [s for _, s in shell]:
                    continue  # both shell boxes: exempt (see above)
                for axis, plane, side_a, side_b in _face_pairs(a, b):
                    if side_a == side_b or not _overlaps(a, b, axis):
                        continue  # same-facing or non-overlapping faces never fight
                    raise AssertionError(
                        f'{a_name} and {b_name} share a coplanar face on '
                        f'{"XYZ"[axis]}={plane:g} ({side_a}/{side_b}); '
                        'offset or overlap them by more than 0.002')
    return tables


def parse_button_plain_u():
    """Read ``CabinRenderer.BUTTON_PLAIN_U`` -- the plain margin of the BUTTON cell, in cell widths.

    Read out of the Java source for the same reason the part tables are (see
    :func:`parse_cabin_parts`): the renderer owns the number, and a silent divergence between it
    and the disc drawn here is exactly the bug :func:`check_button_face` guards against.
    """
    text = CABIN_RENDERER.read_text(encoding='utf-8')
    match = re.search(r'BUTTON_PLAIN_U\s*=\s*([0-9.]+)f', text)
    assert match, 'CabinRenderer.BUTTON_PLAIN_U not found (did it get renamed?)'
    return float(match.group(1))


def check_button_face():
    """The button's five side faces take a *plain* strip of the BUTTON cell, so keep it plain.

    ``CabinRenderer.buttonFaceUv`` maps the cell's left ``BUTTON_PLAIN_U`` onto those faces.  The
    disc is centred, so its leftmost pixel sits at ``TILE/2 - 0.5 - BUTTON_DISC_PX``; if the disc
    ever grew past that -- or the margin grew -- the sides would show part of the ring, which is
    the "a circle on every face" report all over again.
    """
    plain_u = parse_button_plain_u()
    margin_px = plain_u * TILE
    disc_left_px = TILE / 2 - 0.5 - BUTTON_DISC_PX
    assert 0 < margin_px < disc_left_px, (
        f'BUTTON_PLAIN_U={plain_u} reaches x={margin_px:.1f}px of a {TILE}px cell, but the disc '
        f'starts at x={disc_left_px:.1f}px: the button sides would show part of the ring. '
        'Narrow BUTTON_PLAIN_U or shrink BUTTON_DISC_PX.')


# --------------------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------------------
def main():
    BLOCK_TEX.mkdir(parents=True, exist_ok=True)
    ENTITY_TEX.mkdir(parents=True, exist_ok=True)
    outputs = {
        BLOCK_TEX / 'blank.png': tex_frame_plate(),
        BLOCK_TEX / 'blank_door.png': tex_door_leaf(),
        BLOCK_TEX / 'blank_plate.png': tex_machined_plate(),
        BLOCK_TEX / 'blank_screen.png': tex_display_screen(),
        BLOCK_TEX / 'blank_speed.png': tex_speed_plate(),
        BLOCK_TEX / 'blank_powerful.png': tex_powerful_plate(),
        BLOCK_TEX / 'blank_glass.png': tex_glass_plate(),
        ENTITY_TEX / 'cabin.png': build_atlas(),
        ASSETS / 'icon.png': build_icon(),
    }
    for path, canvas in outputs.items():
        canvas.save(path)
        print(f'wrote {path.relative_to(ROOT.parent.parent)}')

    # Remove generator-owned textures that are no longer produced (e.g. a renamed blank_*.png).
    # Without this a stale file keeps shipping inside the jar and can even be picked up by a model.
    for stale in sorted(BLOCK_TEX.glob('blank*.png')):
        if stale not in outputs:
            stale.unlink()
            print(f'removed stale {stale.relative_to(ROOT.parent.parent)}')

    doors = door_models()
    check_door_models(doors)
    check_cabin_parts()
    check_button_face()
    blocks = {'elevator_rail': rail_model(), 'call_button': door_item_model(), **doors}
    icons = cabin_icon_models()
    check_item_models({'call_button': blocks['call_button'], **icons})
    write_json(ASSETS / 'models/item/cabin_body.json', icons.pop('cabin_body'))
    for name, spec in blocks.items():
        write_json(ASSETS / f'models/block/{name}.json', spec)
        print(f'wrote models/block/{name}.json')
    # Block item models simply parent the block model they place.
    for name in ('elevator_rail', 'call_button'):
        write_json(ASSETS / f'models/item/{name}.json', {'parent': f'easyelevator:block/{name}'})
    for name, spec in icons.items():
        write_json(ASSETS / f'models/item/{name}.json', spec)
        print(f'wrote models/item/{name}.json')


if __name__ == '__main__':
    main()

