"""The extended pictogram set (LCA-T22): 81 more glyphs beside the 28 core ones.

Each glyph is a 192x192 viewBox of line art drawn with strokes (the renderer sets the stroke
width) plus a few solid marks (class "f"). Every drawing here is original. glyph_render.py
renders them as a bold "trekui" stroke set into the glyphs/ assets folder; the plain TrekUI set
for the core 28 comes from the older Pillow script (tools/legacy/make_glyphs.py).

The app's keyword table (Pictograms.java) maps package names and labels onto these names, and
Pictograms.ALL must list every name defined here.
"""

E = {}

# ---- money and shopping ------------------------------------------------------------------------

# Columned bank front with a pediment.
E["bank"] = """
<path d="M28 70 L96 32 L164 70z" class="f"/>
<path d="M36 80 h120"/>
<path d="M52 92 v50 M80 92 v50 M112 92 v50 M140 92 v50"/>
<path d="M30 154 h132 M24 170 h144"/>
"""

# Wallet with a card peeking out and a clasp.
E["wallet"] = """
<path d="M34 64 l88 -28 l10 28"/>
<rect x="28" y="64" width="136" height="96" rx="12"/>
<path d="M164 94 h-34 a16 16 0 0 0 0 32 h34"/>
<circle cx="132" cy="110" r="6" class="f"/>
"""

# Shopping cart.
E["cart"] = """
<path d="M18 40 h24 l22 88 h84 l18 -64 h-114"/>
<path d="M58 100 h96"/>
<circle cx="74" cy="156" r="12" class="f"/><circle cx="140" cy="156" r="12" class="f"/>
"""

# Shopping bag with a looped handle.
E["bag"] = """
<path d="M40 66 h112 l10 102 h-132z"/>
<path d="M70 84 v-22 q0 -32 26 -32 q26 0 26 32 v22"/>
"""

# Wrapped present with a bow.
E["gift"] = """
<rect x="30" y="74" width="132" height="30" rx="3"/>
<rect x="42" y="104" width="108" height="64"/>
<path d="M96 74 v94"/>
<path d="M96 72 q-40 -44 -50 -14 q-4 14 50 14 q54 0 50 -14 q-10 -30 -50 14"/>
"""

# Admission ticket with a perforated stub.
E["ticket"] = """
<path d="M22 58 h148 v24 a14 14 0 0 0 0 28 v24 h-148 v-24 a14 14 0 0 0 0 -28z"/>
<path d="M126 66 v62" stroke-dasharray="2 12"/>
<path d="M48 86 h50 M48 108 h34"/>
"""

# Coin with a currency mark.
E["dollar"] = """
<circle cx="96" cy="96" r="70"/>
<path d="M120 70 q-6 -14 -24 -14 q-24 0 -24 20 q0 18 24 20 q24 2 24 20 q0 20 -24 20 q-18 0 -24 -14 M96 42 v108"/>
"""

# Rising stock chart.
E["chart"] = """
<path d="M28 24 v140 h140"/>
<path d="M48 136 l32 -40 l24 22 l48 -64"/>
<path d="M126 54 h26 v26"/>
"""

# Parcel box.
E["package"] = """
<path d="M96 24 l68 32 v80 l-68 32 l-68 -32 v-80z"/>
<path d="M28 56 l68 32 l68 -32 M96 88 v80"/>
<path d="M62 40 l68 32 v26"/>
"""

# ---- travel and transport ----------------------------------------------------------------------

# Airliner seen from above.
E["plane"] = """
<path d="M96 18 q10 0 10 20 v34 l60 36 v16 l-60 -18 v36 l18 14 v12 l-28 -8 l-28 8 v-12 l18 -14 v-36 l-60 18 v-16 l60 -36 v-34 q0 -20 10 -20z" class="f"/>
"""

# Car, front view.
E["car"] = """
<path d="M40 96 l14 -40 q4 -10 16 -10 h52 q12 0 16 10 l14 40"/>
<rect x="28" y="96" width="136" height="44" rx="12"/>
<path d="M44 140 v20 h22 v-20 M126 140 v20 h22 v-20"/>
<circle cx="56" cy="118" r="8" class="f"/><circle cx="136" cy="118" r="8" class="f"/>
<path d="M80 124 h32"/>
"""

# Train, front view.
E["train"] = """
<rect x="44" y="22" width="104" height="124" rx="22"/>
<rect x="60" y="42" width="72" height="40" rx="6"/>
<circle cx="70" cy="114" r="8" class="f"/><circle cx="122" cy="114" r="8" class="f"/>
<path d="M66 146 l-20 26 M126 146 l20 26 M54 162 h84"/>
"""

# Bicycle.
E["bike"] = """
<circle cx="46" cy="124" r="30"/><circle cx="146" cy="124" r="30"/>
<path d="M46 124 L80 72 L96 124 Z M80 72 H130 L96 124 M130 72 L146 124"/>
<path d="M68 62 h24 M122 54 l8 18"/>
"""

# Rocket with fins and exhaust.
E["rocket"] = """
<path d="M96 18 q38 30 38 92 v26 h-76 v-26 q0 -62 38 -92z"/>
<circle cx="96" cy="78" r="14"/>
<path d="M58 106 l-22 32 v20 l22 -12 M134 106 l22 32 v20 l-22 -12"/>
<path d="M80 146 l16 30 l16 -30z" class="f"/>
"""

# Fuel pump with a hose.
E["fuel"] = """
<rect x="34" y="28" width="76" height="140" rx="8"/>
<rect x="50" y="46" width="44" height="34" class="f"/>
<path d="M22 168 h100"/>
<path d="M110 80 h18 q10 0 10 10 v52 q0 12 12 12 q12 0 12 -12 v-72 l-22 -26"/>
"""

# Bed with a pillow (hotels, stays).
E["bed"] = """
<path d="M24 50 v118 M24 132 h144 v36 M24 108 h144 q0 -28 -28 -28 h-56 v28"/>
<circle cx="54" cy="92" r="14" class="f"/>
"""

# Compass rose with needle.
E["compass"] = """
<circle cx="96" cy="96" r="70"/>
<path d="M96 40 l18 56 h-36z" class="f"/>
<path d="M78 96 l18 56 l18 -56"/>
<circle cx="96" cy="96" r="6" class="f"/>
"""

# Waving pennant on a pole.
E["flag"] = """
<path d="M44 172 v-148"/>
<path d="M44 32 q30 -16 60 0 q30 16 60 0 v72 q-30 16 -60 0 q-30 -16 -60 0z" class="f"/>
"""

# ---- reading, media and play -------------------------------------------------------------------

# Open book.
E["book"] = """
<path d="M96 52 q-30 -18 -72 -12 v112 q42 -6 72 12 q30 -18 72 -12 v-112 q-42 -6 -72 12z"/>
<path d="M96 52 v112"/>
<path d="M44 70 q20 -2 36 6 M44 96 q20 -2 36 6 M112 76 q16 -8 36 -6 M112 102 q16 -8 36 -6"/>
"""

# Newspaper with a picture block and columns.
E["news"] = """
<rect x="34" y="30" width="124" height="132" rx="8"/>
<rect x="52" y="50" width="42" height="38" class="f"/>
<path d="M110 54 h30 M110 70 h30 M110 86 h30 M52 108 h88 M52 126 h88 M52 144 h60"/>
"""

# Studio microphone (voice, podcasts, recorder).
E["mic"] = """
<rect x="72" y="20" width="48" height="88" rx="24"/>
<path d="M48 88 q0 48 48 48 q48 0 48 -48"/>
<path d="M96 136 v28 M68 168 h56"/>
"""

# Headphones.
E["headphones"] = """
<path d="M36 120 v-20 q0 -64 60 -64 q60 0 60 64 v20"/>
<rect x="28" y="110" width="32" height="52" rx="12" class="f"/>
<rect x="132" y="110" width="32" height="52" rx="12" class="f"/>
"""

# Game controller.
E["gamepad"] = """
<path d="M56 60 h80 q32 0 36 40 l6 44 q2 22 -18 22 q-12 0 -22 -16 l-12 -18 h-60 l-12 18 q-10 16 -22 16 q-20 0 -18 -22 l6 -44 q4 -40 36 -40z"/>
<path d="M60 92 v28 M46 106 h28"/>
<circle cx="128" cy="96" r="7" class="f"/><circle cx="144" cy="114" r="7" class="f"/>
"""

# Jigsaw piece (puzzles, casual games).
E["puzzle"] = """
<path d="M40 52 h30 q-8 -26 18 -26 q26 0 18 26 h30 v30 q26 -8 26 18 q0 26 -26 18 v30 h-30 q8 26 -18 26 q-26 0 -18 -26 h-30z"/>
"""

# Television set with antenna.
E["tv"] = """
<rect x="24" y="48" width="144" height="100" rx="12"/>
<path d="M72 20 l24 26 l24 -26"/>
<path d="M64 168 h64 M96 148 v20"/>
"""

# Film clapperboard (streaming, movies).
E["clapper"] = """
<rect x="28" y="80" width="136" height="84" rx="6"/>
<path d="M24 56 l132 -24 l6 30 l-132 24z"/>
<path d="M54 50 l16 26 M86 44 l16 26 M118 38 l16 26"/>
<path d="M28 108 h136"/>
"""

# Trophy cup.
E["trophy"] = """
<path d="M56 28 h80 v40 q0 44 -40 44 q-40 0 -40 -44z"/>
<path d="M56 44 h-24 q0 40 32 40 M136 44 h24 q0 40 -32 40"/>
<path d="M96 112 v28 M64 168 h64 v-28 h-64z"/>
"""

# Ball with seams (sports).
E["ball"] = """
<circle cx="96" cy="96" r="68"/>
<path d="M28 96 h136 M96 28 v136"/>
<path d="M50 46 q30 50 0 100 M142 46 q-30 50 0 100"/>
"""

# Star (favourites, ratings).
E["star"] = """
<path d="M96 20 l22 48 l52 6 l-38 36 l10 52 l-46 -26 l-46 26 l10 -52 l-38 -36 l52 -6z"/>
"""

# ---- people and communication ------------------------------------------------------------------

# Two overlapping speech bubbles (social, chat).
E["chat"] = """
<path d="M24 38 h96 q10 0 10 10 v52 q0 10 -10 10 h-56 l-24 22 v-22 h-16 q-10 0 -10 -10 v-52 q0 -10 10 -10z"/>
<path d="M144 74 h14 q10 0 10 10 v48 q0 10 -10 10 h-12 v20 l-22 -20 h-46 q-10 0 -10 -10 v-10"/>
"""

# Two people (groups, social networks).
E["people"] = """
<circle cx="70" cy="64" r="22"/>
<path d="M28 156 v-14 q0 -36 42 -36 q42 0 42 36 v14"/>
<circle cx="130" cy="56" r="18"/>
<path d="M122 94 q4 -2 8 -2 q36 0 36 32 v12 h-38"/>
"""

# Video camera (video calls).
E["videocall"] = """
<rect x="20" y="56" width="112" height="80" rx="14"/>
<path d="M132 84 l40 -24 v72 l-40 -24"/>
<circle cx="76" cy="96" r="16" class="f"/>
"""

# Share graph: three linked nodes.
E["share"] = """
<circle cx="136" cy="48" r="20"/><circle cx="56" cy="96" r="20"/><circle cx="136" cy="144" r="20"/>
<path d="M74 86 l44 -28 M74 106 l44 28"/>
"""

# Notification bell.
E["bell"] = """
<path d="M44 140 q14 -12 14 -36 v-20 q0 -38 38 -42 q38 4 38 42 v20 q0 24 14 36z"/>
<path d="M32 140 h128"/>
<path d="M80 152 q16 24 32 0"/>
<circle cx="96" cy="30" r="8" class="f"/>
"""

# Translation: a lettered card and a second-script card.
E["translate"] = """
<rect x="20" y="28" width="96" height="84" rx="10"/>
<path d="M44 96 l24 -52 l24 52 M52 80 h32"/>
<path d="M124 76 h40 q8 0 8 8 v72 q0 8 -8 8 h-64 q-8 0 -8 -8 v-28"/>
<path d="M112 100 h48 M136 90 v10 M152 100 q-6 30 -36 44 M126 114 q12 20 30 30"/>
"""

# ---- work and study ----------------------------------------------------------------------------

# Mortarboard with tassel.
E["school"] = """
<path d="M96 40 l76 32 l-76 32 l-76 -32z" class="f"/>
<path d="M52 88 v40 q44 32 88 0 v-40"/>
<path d="M164 76 v52"/>
<circle cx="164" cy="136" r="8" class="f"/>
"""

# Briefcase.
E["briefcase"] = """
<rect x="24" y="60" width="144" height="100" rx="12"/>
<path d="M72 60 v-16 q0 -8 8 -8 h32 q8 0 8 8 v16"/>
<path d="M24 100 h144"/>
<rect x="84" y="90" width="24" height="20" rx="3" class="f"/>
"""

# Text document with a folded corner.
E["document"] = """
<path d="M44 20 h72 l36 36 v116 h-108z"/>
<path d="M116 20 v36 h36"/>
<path d="M64 88 h64 M64 112 h64 M64 136 h44"/>
"""

# Spreadsheet grid with a header row.
E["sheet"] = """
<rect x="32" y="28" width="128" height="36" rx="6" class="f"/>
<rect x="32" y="28" width="128" height="136" rx="8"/>
<path d="M32 98 h128 M32 132 h128 M72 64 v100 M116 64 v100"/>
"""

# Presentation easel with a bar chart.
E["slides"] = """
<path d="M20 36 h152"/>
<rect x="32" y="36" width="128" height="88"/>
<path d="M96 124 v20 M70 172 l26 -28 l26 28"/>
<path d="M56 104 v-20 M80 104 v-36 M104 104 v-28 M128 104 v-48"/>
"""

# Pencil (writing, drawing).
E["pen"] = """
<path d="M136 24 l32 32 l-100 100 l-44 12 l12 -44z"/>
<path d="M116 44 l32 32"/>
<path d="M24 168 l12 -44 l32 32z" class="f"/>
"""

# Painter's palette.
E["palette"] = """
<path d="M96 24 q72 0 72 64 q0 30 -30 30 h-20 q-16 0 -16 16 q0 14 8 20 q4 18 -18 18 q-68 0 -68 -74 q0 -74 72 -74z"/>
<circle cx="66" cy="70" r="11" class="f"/><circle cx="100" cy="54" r="11" class="f"/>
<circle cx="134" cy="72" r="11" class="f"/><circle cx="58" cy="108" r="11" class="f"/>
"""

# Scissors (editing, cutting).
E["scissors"] = """
<circle cx="52" cy="140" r="20"/><circle cx="140" cy="140" r="20"/>
<path d="M66 126 l70 -102 M126 126 l-70 -102"/>
"""

# Matrix code (scanners).
E["qr"] = """
<rect x="28" y="28" width="52" height="52" rx="4"/><rect x="112" y="28" width="52" height="52" rx="4"/><rect x="28" y="112" width="52" height="52" rx="4"/>
<rect x="44" y="44" width="20" height="20" class="f"/><rect x="128" y="44" width="20" height="20" class="f"/><rect x="44" y="128" width="20" height="20" class="f"/>
<rect x="112" y="112" width="18" height="18" class="f"/><rect x="146" y="112" width="18" height="18" class="f"/>
<rect x="129" y="129" width="18" height="18" class="f"/>
<rect x="112" y="146" width="18" height="18" class="f"/><rect x="146" y="146" width="18" height="18" class="f"/>
"""

# Printer with a page coming out.
E["printer"] = """
<path d="M56 64 v-40 h80 v40"/>
<rect x="24" y="64" width="144" height="68" rx="10"/>
<path d="M56 112 h80 v56 h-80z M72 132 h48 M72 150 h32"/>
<circle cx="146" cy="88" r="7" class="f"/>
"""

# Code brackets (developer tools).
E["code"] = """
<path d="M62 52 l-42 44 l42 44 M130 52 l42 44 l-42 44 M110 36 l-28 120"/>
"""

# Keyboard.
E["keyboard"] = """
<rect x="16" y="52" width="160" height="96" rx="10"/>
""" + "".join(f'<path d="M{x} {y} h0.1"/>'
              for y in (78, 102) for x in (40, 68, 96, 124, 152)) + """
<path d="M64 126 h64"/>
"""

# Robot head (assistants, AI).
E["robot"] = """
<rect x="36" y="56" width="120" height="96" rx="20"/>
<circle cx="72" cy="98" r="12" class="f"/><circle cx="120" cy="98" r="12" class="f"/>
<path d="M76 128 h40"/>
<path d="M96 56 v-24"/><circle cx="96" cy="26" r="8" class="f"/>
<path d="M36 96 h-12 v28 M156 96 h12 v28"/>
"""

# ---- devices, connectivity and tools -----------------------------------------------------------

# Cloud (storage, sync).
E["cloud"] = """
<path d="M52 148 q-32 0 -32 -30 q0 -28 30 -30 q4 -40 46 -40 q36 0 44 34 q32 0 32 34 q0 32 -34 32z"/>
"""

# Download arrow into a tray.
E["download"] = """
<path d="M96 24 v96 M60 86 l36 36 l36 -36"/>
<path d="M32 128 v28 q0 10 10 10 h108 q10 0 10 -10 v-28"/>
"""

# Wireless arcs.
E["wifi"] = """
<path d="M24 76 q72 -64 144 0 M48 102 q48 -40 96 0 M72 128 q24 -18 48 0"/>
<circle cx="96" cy="154" r="11" class="f"/>
"""

# Short-range radio rune.
E["bluetooth"] = """
<path d="M56 64 l80 64 l-40 36 v-136 l40 36 l-80 64"/>
"""

# Battery with charge.
E["battery"] = """
<rect x="56" y="36" width="80" height="132" rx="10"/>
<rect x="78" y="22" width="36" height="14" rx="3" class="f"/>
<rect x="72" y="112" width="48" height="40" rx="4" class="f"/>
<path d="M104 52 l-16 24 h18 l-16 24"/>
"""

# Hand torch with beam.
E["flashlight"] = """
<path d="M68 72 h56 l-12 28 v64 q0 6 -6 6 h-20 q-6 0 -6 -6 v-64z"/>
<path d="M64 52 h64 v20 h-64z"/>
<path d="M96 116 v16"/>
<path d="M96 18 v18 M56 28 l10 12 M136 28 l-10 12"/>
"""

# Key.
E["key"] = """
<circle cx="56" cy="96" r="30"/>
<circle cx="56" cy="96" r="9" class="f"/>
<path d="M86 96 h82 M144 96 v24 M164 96 v18"/>
"""

# Shield with a check (security, VPN, antivirus).
E["shield"] = """
<path d="M96 20 l64 24 v44 q0 56 -64 84 q-64 -28 -64 -84 v-44z"/>
<path d="M66 98 l22 22 l40 -44"/>
"""

# Light bulb (smart lighting, ideas).
E["bulb"] = """
<path d="M72 128 q-36 -22 -36 -60 q0 -48 60 -48 q60 0 60 48 q0 38 -36 60z"/>
<path d="M74 144 h44 M78 160 h36 M88 174 h16"/>
<path d="M84 128 v-30 l12 -14 l12 14 v30"/>
"""

# Thermometer with a scale.
E["thermostat"] = """
<path d="M80 120 v-84 q0 -16 16 -16 q16 0 16 16 v84 a30 30 0 1 1 -32 0z"/>
<circle cx="96" cy="144" r="16" class="f"/>
<path d="M96 132 v-72" style="stroke-width:calc(var(--w)*1.4)"/>
<path d="M124 48 h20 M124 72 h14 M124 96 h20"/>
"""

# Waste bin (cleaners).
E["trash"] = """
<path d="M28 52 h136"/>
<path d="M74 52 v-16 q0 -8 8 -8 h28 q8 0 8 8 v16"/>
<path d="M42 52 l10 112 q1 8 9 8 h70 q8 0 9 -8 l10 -112"/>
<path d="M76 80 v64 M116 80 v64"/>
"""

# Speed gauge (performance, boosters).
E["gauge"] = """
<path d="M28 136 a68 68 0 1 1 136 0"/>
<path d="M96 132 l40 -44"/>
<circle cx="96" cy="132" r="12" class="f"/>
<path d="M46 96 l12 6 M96 72 v12 M146 96 l-12 6"/>
<path d="M40 160 h112"/>
"""

# Wrench (tools, utilities).
E["wrench"] = """
<path d="M150 30 a36 36 0 0 0 -44 46 l-78 78 a14 14 0 0 0 20 20 l78 -78 a36 36 0 0 0 46 -44 l-22 22 l-20 -4 l-4 -20z"/>
"""

# Wristwatch (wearables).
E["watch"] = """
<rect x="56" y="56" width="80" height="80" rx="18"/>
<path d="M70 56 l6 -36 h40 l6 36 M70 136 l6 36 h40 l6 -36"/>
<path d="M96 96 v-22 M96 96 l16 10"/>
"""

# Screen with broadcast arcs (casting).
E["cast"] = """
<path d="M20 64 v-20 q0 -12 12 -12 h128 q12 0 12 12 v104 q0 12 -12 12 h-52"/>
<circle cx="26" cy="154" r="8" class="f"/>
<path d="M20 124 q36 0 36 36 M20 92 q68 0 68 68"/>
"""

# Remote control.
E["remote"] = """
<rect x="60" y="20" width="72" height="152" rx="20"/>
<circle cx="96" cy="60" r="16"/>
<circle cx="80" cy="104" r="6" class="f"/><circle cx="112" cy="104" r="6" class="f"/>
<circle cx="80" cy="128" r="6" class="f"/><circle cx="112" cy="128" r="6" class="f"/>
<circle cx="96" cy="150" r="6" class="f"/>
"""

# Satellite with solar wings.
E["satellite"] = """
<rect x="80" y="74" width="32" height="44" rx="4" class="f"/>
<rect x="20" y="80" width="44" height="32"/><rect x="128" y="80" width="44" height="32"/>
<path d="M64 96 h16 M112 96 h16 M42 80 v32 M150 80 v32"/>
<path d="M96 118 v20 M72 160 q24 -30 48 0z"/>
<path d="M96 74 v-20 M84 40 q12 -12 24 0"/>
"""

# Ringed planet.
E["planet"] = """
<ellipse cx="96" cy="96" rx="84" ry="24" transform="rotate(-20 96 96)"/>
<circle cx="96" cy="96" r="44" class="f"/>
<path d="M54 118 q42 -6 92 -34" transform="rotate(0 96 96)"/>
"""

# ---- time, health and home ---------------------------------------------------------------------

# Twin-bell alarm clock.
E["alarm"] = """
<circle cx="96" cy="104" r="56"/>
<path d="M96 104 v-30 M96 104 l22 14"/>
<path d="M22 60 a30 30 0 0 1 42 -40z" class="f"/><path d="M170 60 a30 30 0 0 0 -42 -40z" class="f"/>
<path d="M58 150 l-14 20 M134 150 l14 20"/>
"""

# Stopwatch.
E["timer"] = """
<circle cx="96" cy="108" r="60"/>
<path d="M80 24 h32 M96 24 v24 M146 52 l12 -12"/>
<path d="M96 108 l28 -28"/>
<circle cx="96" cy="108" r="7" class="f"/>
"""

# Heart with a pulse line (health).
E["heart"] = """
<path d="M96 164 q-76 -50 -76 -96 q0 -36 36 -40 q28 -2 40 24 q12 -26 40 -24 q36 4 36 40 q0 46 -76 96z"/>
<path d="M44 96 h30 l10 -18 l16 36 l10 -18 h38"/>
"""

# Two-tone capsule (pharmacy, medicine).
E["pill"] = """
<g transform="rotate(-45 96 96)">
<rect x="30" y="68" width="132" height="56" rx="28"/>
<path d="M96 68 h38 a28 28 0 0 1 0 56 h-38z" class="f"/>
</g>
"""

# Crescent moon with stars (sleep).
E["moon"] = """
<path d="M118 26 a72 72 0 1 0 50 108 a58 58 0 1 1 -50 -108z"/>
<circle cx="146" cy="50" r="6" class="f"/><circle cx="166" cy="82" r="4" class="f"/>
"""

# Fork and knife (food, recipes, delivery).
E["food"] = """
<path d="M60 24 v44 q0 16 14 16 v84 M88 24 v44 q0 16 -14 16 M74 24 v52"/>
<path d="M128 168 v-144 q28 18 28 64 q0 16 -14 20 h-14"/>
"""

# Steaming cup (coffee, cafes).
E["coffee"] = """
<path d="M34 76 h104 v40 q0 44 -52 44 q-52 0 -52 -44z"/>
<path d="M138 88 h10 q18 0 18 18 q0 18 -18 18 h-12"/>
<path d="M22 172 h128"/>
<path d="M66 22 q-8 12 0 22 q8 12 0 22 M100 22 q-8 12 0 22 q8 12 0 22"/>
"""

# Leaf (nature, plants, eco).
E["leaf"] = """
<path d="M36 156 q-8 -116 128 -128 q8 128 -128 128z"/>
<path d="M36 156 q50 -54 96 -96"/>
<path d="M22 172 l16 -18"/>
"""

# Paw print (pets).
E["paw"] = """
<path d="M96 100 q-40 0 -48 44 q-4 24 18 24 q14 0 30 -8 q16 8 30 8 q22 0 18 -24 q-8 -44 -48 -44z" class="f"/>
<ellipse cx="44" cy="92" rx="14" ry="18" class="f"/><ellipse cx="74" cy="52" rx="14" ry="20" class="f"/>
<ellipse cx="118" cy="52" rx="14" ry="20" class="f"/><ellipse cx="148" cy="92" rx="14" ry="18" class="f"/>
"""

# Umbrella (forecasts, insurance).
E["umbrella"] = """
<path d="M20 100 q4 -68 76 -72 q72 4 76 72 q-12 -14 -25 0 q-13 -14 -25 0 q-13 -14 -26 0 q-13 -14 -26 0 q-12 -14 -25 0 q-13 -14 -25 0z"/>
<path d="M96 28 v-8 M96 100 v56 q0 16 -16 16 q-14 0 -14 -14"/>
"""

# Shopping/delivery truck.
E["truck"] = """
<rect x="16" y="56" width="104" height="80" rx="6"/>
<path d="M120 80 h32 l24 28 v28 h-56z"/>
<circle cx="52" cy="148" r="16" class="f"/><circle cx="144" cy="148" r="16" class="f"/>
"""

# Camera lens aperture (lens and scanner apps).
E["lens"] = """
<circle cx="96" cy="96" r="70"/>
<circle cx="96" cy="96" r="26" class="f"/>
<path d="M96 26 l30 46 M162 76 l-52 6 M138 150 l-22 -48 M54 150 l22 -48 M30 76 l52 6" />
"""

# Globe with meridians (travel, world, languages).
E["globe"] = """
<circle cx="96" cy="96" r="70"/>
<ellipse cx="96" cy="96" rx="30" ry="70"/>
<path d="M26 96 h140 M36 62 h120 M36 130 h120"/>
"""

# Drop of water (utilities, hydration).
E["drop"] = """
<path d="M96 20 q56 64 56 100 a56 56 0 0 1 -112 0 q0 -36 56 -100z"/>
<path d="M68 122 q0 22 22 28"/>
"""

# Dumbbell is "fitness"; this is a running shoe (activity trackers).
E["shoe"] = """
<path d="M22 138 v-34 q0 -8 8 -8 h16 q16 0 28 -24 q4 -8 12 -4 l8 4 q14 30 44 34 q32 4 34 26 v6z"/>
<path d="M22 138 h148 v10 q0 10 -10 10 h-128 q-10 0 -10 -10z" class="f"/>
<path d="M86 86 l14 8 M78 100 l14 8"/>
"""
