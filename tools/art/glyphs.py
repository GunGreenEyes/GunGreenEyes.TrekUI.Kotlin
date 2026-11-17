"""Pipe-lounge pictograms: one drawing per launcher glyph name, 192x192 viewBox.

Every glyph is line art built from strokes (stroke width W) plus a few solid marks,
so it can be rendered three ways: engraved (Tobacconist), deco (Ember Lounge) and
rubber stamp (Blend Journal).
"""

G = {}

# Candlestick telephone: base, stem, mouthpiece, earpiece on its hook.
G["phone"] = """
<path d="M64 168 h64 a10 10 0 0 0 -10 -16 h-44 a10 10 0 0 0 -10 16z" class="f"/>
<path d="M88 152 V70 M104 152 V70"/>
<path d="M80 70 h32"/>
<path d="M78 46 q18 -20 36 0 l-4 24 h-28z" class="f"/>
<path d="M112 92 h18 v10"/>
<rect x="122" y="100" width="18" height="40" rx="7"/>
<path d="M131 140 q-6 22 -34 26"/>
"""

# Smoke-cloud speech bubble.
G["messages"] = """
<path d="M44 120 q-22 -2 -20 -26 q2 -22 26 -22 q4 -26 32 -24 q16 -18 38 -4 q28 -4 32 22 q24 6 18 32 q-6 22 -30 20 q-12 14 -34 8 h-38 l-22 22 l4 -26 q-4 0 -6 -2z"/>
<circle cx="70" cy="96" r="6" class="f"/><circle cx="96" cy="96" r="6" class="f"/><circle cx="122" cy="96" r="6" class="f"/>
"""

# Envelope sealed with wax.
G["mail"] = """
<rect x="28" y="52" width="136" height="92" rx="6"/>
<path d="M30 56 L96 108 L162 56"/>
<path d="M30 140 L80 96 M162 140 L112 96"/>
<circle cx="96" cy="108" r="16" class="f"/>
"""

# Envelope with a postage stamp.
G["officemail"] = """
<rect x="24" y="56" width="128" height="88" rx="6"/>
<path d="M26 60 L88 106 L150 60"/>
<rect x="132" y="38" width="38" height="44" rx="2" class="f"/>
"""

# Old-world globe on its stand.
G["browser"] = """
<circle cx="96" cy="84" r="50"/>
<ellipse cx="96" cy="84" rx="22" ry="50"/>
<path d="M46 84 h100 M54 58 h84 M54 110 h84"/>
<path d="M150 50 a64 64 0 0 1 -92 92"/>
<path d="M96 148 v18 M70 170 h52"/>
"""

# Folding bellows camera.
G["camera"] = """
<rect x="26" y="62" width="44" height="80" rx="4"/>
<path d="M70 70 l18 8 v48 l-18 8 M88 78 l18 8 v32 l-18 8 M106 86 l16 6 v20 l-16 6"/>
<circle cx="138" cy="102" r="18"/>
<circle cx="138" cy="102" r="7" class="f"/>
<rect x="34" y="46" width="22" height="16" rx="2"/>
"""

# Tear-off almanac.
G["calendar"] = """
<rect x="34" y="44" width="124" height="120" rx="6"/>
<path d="M34 76 h124"/>
<path d="M62 30 v26 M130 30 v26"/>
<rect x="68" y="92" width="18" height="18" class="f"/><rect x="106" y="92" width="18" height="18"/>
<rect x="68" y="124" width="18" height="18"/><rect x="106" y="124" width="18" height="18"/>
"""

# Pocket watch with crown and chain.
G["clock"] = """
<circle cx="96" cy="104" r="56"/>
<circle cx="96" cy="104" r="44"/>
<path d="M96 104 V74 M96 104 L118 116"/>
<rect x="88" y="34" width="16" height="14" rx="3" class="f"/>
<circle cx="96" cy="26" r="7"/>
<path d="M103 26 q36 -6 50 16"/>
"""

# Typewriter.
G["computer"] = """
<path d="M40 50 h112" />
<rect x="30" y="56" width="132" height="28" rx="12"/>
<path d="M60 56 V32 h72 v24"/>
<path d="M24 160 L42 92 h108 l18 68z"/>
<circle cx="60" cy="118" r="5" class="f"/><circle cx="80" cy="118" r="5" class="f"/><circle cx="100" cy="118" r="5" class="f"/><circle cx="120" cy="118" r="5" class="f"/><circle cx="140" cy="118" r="5" class="f"/>
<path d="M60 140 h72"/>
"""

# Cameo: a gentleman's profile with his pipe.
G["contacts"] = """
<ellipse cx="96" cy="96" rx="58" ry="72"/>
<path d="M86 152 q-6 -18 4 -30 q-14 -6 -14 -20 l-8 -4 l8 -8 q-4 -30 22 -38 q30 -6 38 22 q4 18 -6 30 q2 16 -4 30" class="f"/>
<path d="M78 108 h-22 v14 q0 10 10 10 h4 q10 0 10 -10z" class="f"/>
"""

# Ornate picture frame with hills and sun.
G["photos"] = """
<rect x="26" y="38" width="140" height="116" rx="4"/>
<rect x="40" y="52" width="112" height="88"/>
<path d="M40 132 l32 -36 l22 22 l18 -16 l40 30"/>
<circle cx="124" cy="78" r="10" class="f"/>
"""

# Gramophone.
G["music"] = """
<path d="M84 104 L150 32 q20 30 8 62 z"/>
<path d="M84 104 l-14 8"/>
<rect x="30" y="122" width="96" height="40" rx="4"/>
<path d="M30 136 h96"/>
<circle cx="70" cy="114" r="6" class="f"/>
"""

# Compass rose.
G["maps"] = """
<circle cx="96" cy="96" r="64"/>
<path d="M96 22 L110 96 L96 170 L82 96 Z"/>
<path d="M96 22 L110 96 H82 Z" class="f"/>
<path d="M22 96 L96 82 L170 96 L96 110 Z"/>
<circle cx="96" cy="96" r="8" class="f"/>
"""

# Sun breaking through a drift of smoke.
G["weather"] = """
<circle cx="74" cy="72" r="26"/>
<path d="M74 28 v10 M74 106 v8 M30 72 h10 M42 40 l8 8 M106 40 l-8 8 M42 104 l8 -8"/>
<path d="M60 152 q-26 0 -24 -22 q2 -18 22 -18 q6 -24 34 -20 q14 -16 36 -4 q24 0 26 24 q20 6 14 26 q-4 14 -22 14z"/>
"""

# Pipe tools: tamper crossed with a pick and spoon (a Czech tool).
G["settings"] = """
<path d="M48 156 L148 40"/>
<circle cx="150" cy="38" r="12" class="f"/>
<path d="M44 40 L144 156"/>
<path d="M34 30 q-10 18 8 22 q14 2 10 -16" class="f"/>
<circle cx="96" cy="98" r="9"/>
"""

# Brass gear.
G["system"] = """
<path d="M88 26 h16 l4 18 l14 6 l16 -10 l12 12 l-10 16 l6 14 l18 4 v16 l-18 4 l-6 14 l10 16 l-12 12 l-16 -10 l-14 6 l-4 18 h-16 l-4 -18 l-14 -6 l-16 10 l-12 -12 l10 -16 l-6 -14 l-18 -4 v-16 l18 -4 l6 -14 l-10 -16 l12 -12 l16 10 l14 -6z"/>
<circle cx="96" cy="96" r="22"/>
"""

# Tobacconist's cabinet of drawers.
G["apps"] = """
<rect x="30" y="30" width="132" height="132" rx="4"/>
<path d="M30 74 h132 M30 118 h132 M96 30 v132"/>
<circle cx="63" cy="52" r="5" class="f"/><circle cx="129" cy="52" r="5" class="f"/>
<circle cx="63" cy="96" r="5" class="f"/><circle cx="129" cy="96" r="5" class="f"/>
<circle cx="63" cy="140" r="5" class="f"/><circle cx="129" cy="140" r="5" class="f"/>
"""

# Cottage with smoke from the chimney.
G["home"] = """
<path d="M28 98 L96 40 L164 98"/>
<path d="M44 86 V160 H148 V86"/>
<rect x="82" y="116" width="28" height="44"/>
<path d="M126 72 V42 h16 v44"/>
<path d="M134 34 q-10 -10 2 -18 q10 -8 2 -16"/>
"""

# Magnifying glass.
G["search"] = """
<circle cx="80" cy="80" r="44"/>
<path d="M80 54 a26 26 0 0 0 -26 26"/>
<path d="M112 112 L160 160" style="stroke-width:calc(var(--w)*2)"/>
"""

# Abacus.
G["calculator"] = """
<rect x="30" y="34" width="132" height="124" rx="4"/>
<path d="M30 62 h132 M30 96 h132 M30 130 h132"/>
<circle cx="56" cy="62" r="8" class="f"/><circle cx="74" cy="62" r="8" class="f"/><circle cx="138" cy="62" r="8" class="f"/>
<circle cx="56" cy="96" r="8" class="f"/><circle cx="120" cy="96" r="8" class="f"/><circle cx="138" cy="96" r="8" class="f"/>
<circle cx="74" cy="130" r="8" class="f"/><circle cx="92" cy="130" r="8" class="f"/><circle cx="138" cy="130" r="8" class="f"/>
"""

# Round tobacco tin, side view.
G["files"] = """
<ellipse cx="96" cy="58" rx="58" ry="16"/>
<path d="M38 58 V140 a58 16 0 0 0 116 0 V58"/>
<path d="M38 72 a58 16 0 0 0 116 0"/>
<rect x="66" y="94" width="60" height="32" rx="4"/>
"""

# Drawstring tobacco pouch.
G["store"] = """
<path d="M70 58 q-46 34 -38 74 q8 30 64 30 q56 0 64 -30 q8 -40 -38 -74z"/>
<path d="M70 58 h52"/>
<path d="M78 46 q18 12 36 0"/>
<path d="M114 52 q20 -2 26 -18"/>
<path d="M72 112 q24 14 48 0"/>
"""

# Quill and inkwell.
G["notes"] = """
<path d="M60 168 h72 l-8 -40 h-56z" class="f"/>
<path d="M76 128 v-10 h40 v10"/>
<path d="M100 120 L150 28 q12 26 -6 52 q-14 20 -36 34z"/>
<path d="M150 28 L104 114"/>
"""

# Cathedral radio.
G["radio"] = """
<path d="M40 166 V92 q0 -62 56 -62 q56 0 56 62 v74z"/>
<path d="M62 120 V96 q0 -36 34 -36 q34 0 34 36 v24z"/>
<path d="M80 120 V84 M96 120 V76 M112 120 V84"/>
<circle cx="72" cy="144" r="7" class="f"/><circle cx="120" cy="144" r="7" class="f"/>
"""

# Film reel.
G["video"] = """
<circle cx="96" cy="96" r="62"/>
<circle cx="96" cy="96" r="10" class="f"/>
<circle cx="96" cy="58" r="14"/><circle cx="96" cy="134" r="14"/>
<circle cx="58" cy="96" r="14"/><circle cx="134" cy="96" r="14"/>
"""

# Victorian strongman's dumbbell.
G["fitness"] = """
<circle cx="46" cy="96" r="28" class="f"/>
<circle cx="146" cy="96" r="28" class="f"/>
<path d="M74 96 H118" style="stroke-width:calc(var(--w)*1.8)"/>
"""

# Padlock with a keyhole.
G["secure"] = """
<path d="M62 86 V64 q0 -34 34 -34 q34 0 34 34 v22"/>
<rect x="42" y="86" width="108" height="78" rx="10"/>
<circle cx="96" cy="116" r="10" class="f"/>
<path d="M96 120 v24" style="stroke-width:calc(var(--w)*1.6)"/>
"""

# Concierge's service bell.
G["alert"] = """
<path d="M40 136 q0 -64 56 -64 q56 0 56 64z"/>
<path d="M28 150 h136"/>
<path d="M96 72 v-14 M84 54 h24"/>
<path d="M64 112 q6 -16 22 -22"/>
"""
