package com.gungreeneyes.trekui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import java.util.Locale
import java.util.Random

/**
 * console start-up sequence shown over the home screen once after the phone restarts:
 * the frame sweeps in, "CENTRAL COMPUTER ACCESS / CONTROL SYSTEM" comes up, subsystems
 * report online one by one while the progress strip fills, then it fades to the home screen.
 * About three seconds; tap anywhere to skip. Draws only while visible, then removes itself.
 * Imperial and Collective interfaces get their own sequences ([imperial], [collective]); the
 * 23rd- and 25th-century Command eras play theirs from [EraBoot].
 *
 * Responsive: the sequence is composed on a portrait "stage" at least
 * [MIN_STAGE_W_DP] x [MIN_STAGE_H_DP] dp. On short screens (landscape phones,
 * split screen) the whole stage is scaled down to fit; on tablets it is scaled up by the screen's
 * UI scale ([stageScale]).
 */
class BootSequence(c: Context, private val pal: Palette, header: String, private val onDone: Runnable?) : View(c) {
    private val faction = Skin.faction
    private val header: String = header.uppercase(Locale.getDefault())
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val path = Path()
    private val rnd = Random()
    private val cascade: Array<IntArray>
    private val ornament: Drawable?

    /** The era sequence (23rd / 25th century), or null for every other interface. */
    private val era: EraBoot?
    private var start = 0L
    private var skipAt = -1L
    private var lastCascade = 0L
    private var onlineChirp = false
    private var finished = false

    // Imperial
    private var readyChirp = false
    private var midChirp = false

    /** Per small triangle: start x, start y (fractions of the screen), spin. */
    private var kStart: FloatArray? = null

    // Collective
    private var collectiveBack: Drawable? = null
    private var bx: FloatArray? = null
    private var by: FloatArray? = null
    private var br: FloatArray? = null
    private var bParent: IntArray? = null

    init {
        tp.typeface = Ui.font(c, true)
        ornament = Skin.headerOrnament(c, pal)
        era = if (Skin.eraFaction(faction)) EraBoot(c, pal, this.header, faction) else null
        cascade = Array(14) { intArrayOf(rnd.nextInt(9999), rnd.nextInt(99999)) }
        isClickable = true
        contentDescription = "TrekUI start-up sequence. Tap to skip."
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** Text size, scaled for the faction's (wider) typeface. */
    private fun ts(v: Float): Float = dp(v) * Skin.textScale()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start = SystemClock.uptimeMillis()
        // the ship's computer announces itself: "Accessing library computer data"
        when {
            !Skin.command() -> Stations.play(context, "boot_start")
            SoundLibrary.exists(context, "voice_accessing_library") -> Sfx.playClip(context, "voice_accessing_library", true)
            SoundLibrary.exists(context, Sfx.synthBootFor(Skin.faction)) -> Sfx.playClip(context, Sfx.synthBootFor(Skin.faction), true)
            else -> Sfx.play(context, Sfx.LAUNCH)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && skipAt < 0) {
            val t = SystemClock.uptimeMillis() - start
            skipAt = Math.max(0L, t)
            invalidate()
        }
        return true
    }

    override fun onDraw(cv: Canvas) {
        val t = SystemClock.uptimeMillis() - start
        var alpha = 1f
        val dur = era?.duration()
            ?: if (faction == Skin.IMPERIAL) K_DURATION else if (faction == Skin.COLLECTIVE) B_DURATION else DURATION
        val fadeAt = era?.fadeAt()
            ?: if (faction == Skin.IMPERIAL) K_FADE else if (faction == Skin.COLLECTIVE) B_FADE else FADE_AT
        if (skipAt >= 0) {
            alpha = 1 - clamp((t - skipAt) / SKIP_FADE_MS)
        } else if (t > fadeAt) {
            alpha = 1 - clamp((t - fadeAt) / (dur - fadeAt).toFloat())
        }
        if (alpha <= 0) {
            finish()
            return
        }

        cv.drawColor(Math.round(alpha * 255) shl 24)
        val s = stageScale(
            width.toFloat(), height.toFloat(), resources.displayMetrics.density,
            ScreenMetrics.of(context).uiScale,
        )
        val w = Math.round(width / s)
        val h = Math.round(height / s)
        cv.save()
        cv.scale(s, s)
        try {
            if (era != null) {
                if (era.draw(cv, t, w, h, alpha) && skipAt < 0) Sfx.play(context, Sfx.STATUS_OK)
            } else if (faction == Skin.IMPERIAL) {
                imperial(cv, t, w, h, alpha)
            } else if (faction == Skin.COLLECTIVE) {
                collective(cv, t, w, h, alpha)
            } else {
                console(cv, t, w, h, Math.round(alpha * 255))
            }
        } finally {
            cv.restore()
        }
        postInvalidateOnAnimation()
    }

    /**
     * Command (and fallback) sequence on a w x h stage: elbows sweep out, the title block, the
     * subsystem report, the progress strip, a number cascade and "ALL SYSTEMS ONLINE".
     */
    private fun console(cv: Canvas, t: Long, w: Int, h: Int, a: Int) {
        val top = dp(54f)
        val bottom = h - dp(40f)
        val side = dp(64f)
        val bar = dp(22f)
        val ro = dp(52f)
        val ri = dp(20f)

        if (faction == Skin.COMMAND) {
            // frame: top elbow (c0) and bottom elbow (c1) sweep out from the corner
            val g1 = step(t, 0, 520)
            val g2 = step(t, 180, 700)
            elbow(cv, top, side, bar, ro, ri, w * 0.68f * g1, dp(170f) * g1, pal.c(0), a, false)
            elbow(cv, bottom, side, bar, ro, ri, w * 0.55f * g2, dp(150f) * g2, pal.c(1), a, true)
            // sidebar segments between the elbows
            val segTop = top + dp(176f)
            val segBot = bottom - dp(156f)
            val segCols = intArrayOf(pal.c(3), pal.c(5), pal.c(2), pal.c(4))
            val segH = (segBot - segTop) / segCols.size
            for (i in segCols.indices) {
                val sh = step(t, 300 + i * 90L, 600 + i * 90L)
                if (sh <= 0) continue
                p.color = withAlpha(segCols[i], a)
                cv.drawRect(0f, segTop + i * segH + dp(3f), side, segTop + i * segH + dp(3f) + (segH - dp(6f)) * sh, p)
            }
            // small pills after the bars
            val pill = step(t, 520, 700)
            if (pill > 0) {
                p.color = withAlpha(pal.c(3), Math.round(a * pill))
                r.set(w * 0.68f + dp(8f), top, w * 0.68f + dp(60f), top + bar)
                cv.drawRoundRect(r, bar / 2, bar / 2, p)
                p.color = withAlpha(pal.c(4), Math.round(a * pill))
                r.set(w * 0.68f + dp(68f), top, w - dp(12f), top + bar)
                cv.drawRoundRect(r, bar / 2, bar / 2, p)
            }
        } else {
            factionFrame(cv, t, w, h, top, bottom, side, bar, a)
        }

        val x0 = side + dp(26f)
        val right = w - dp(18f)
        // title block
        val tf = step(t, 450, 800)
        if (tf > 0) {
            tp.textAlign = Paint.Align.RIGHT
            tp.color = withAlpha(pal.c(0), Math.round(a * tf))
            tp.textSize = ts(64f)
            cv.drawText(Skin.title(), right, top + bar + dp(70f), tp)
            tp.textSize = ts(15f)
            tp.color = withAlpha(pal.c(1), Math.round(a * tf))
            cv.drawText(Skin.library(), right, top + bar + dp(96f), tp)
            tp.color = withAlpha(pal.c(2), Math.round(a * tf))
            cv.drawText(header, right, top + bar + dp(118f), tp)
        }

        // subsystem report
        tp.textSize = ts(17f)
        val ly = top + dp(250f)
        val lines = LINES[faction]
        for (i in lines.indices) {
            val at = 800 + i * 210L
            val lf = step(t, at, at + 120)
            if (lf <= 0) break
            val ok = t > at + 170
            tp.textAlign = Paint.Align.LEFT
            tp.color = withAlpha(pal.textHi, Math.round(a * lf))
            cv.drawText(lines[i], x0, ly + i * dp(30f), tp)
            tp.textAlign = Paint.Align.RIGHT
            tp.color = withAlpha(if (ok) pal.c(2) else pal.c(4), Math.round(a * lf))
            cv.drawText(if (ok) OK[faction] else "…", right, ly + i * dp(30f), tp)
        }

        // progress strip
        val pb = clamp((t - 800) / 1500f)
        val blocks = 12
        val by = ly + lines.size * dp(30f) + dp(22f)
        val bw = (right - x0) / blocks
        for (k in 0 until blocks) {
            val lit = k < Math.round(pb * blocks)
            p.color = withAlpha(if (lit) pal.c(if (k % 3 == 2) 4 else 0) else Ui.darken(pal.c(2), 0.3f), a)
            r.set(x0 + k * bw, by, x0 + (k + 1) * bw - dp(6f), by + dp(14f))
            if (faction == Skin.IMPERIAL) {
                // slanted plates
                path.reset()
                path.moveTo(r.left + dp(6f), r.top)
                path.lineTo(r.right, r.top)
                path.lineTo(r.right - dp(6f), r.bottom)
                path.lineTo(r.left, r.bottom)
                path.close()
                cv.drawPath(path, p)
            } else if (faction == Skin.COLLECTIVE) {
                val d1 = resources.displayMetrics.density
                if (k > 0) {
                    CollectiveDraw.curveTrace(
                        cv, r.centerX() - bw + dp(6f), r.centerY(), r.centerX() - dp(6f), r.centerY(), 0f, pal.c(2), d1, a / 255f,
                    )
                }
                if (lit) {
                    CollectiveDraw.node(cv, r.centerX(), r.centerY(), dp(6f), p.color, d1, a / 255f)
                } else {
                    CollectiveDraw.via(cv, r.centerX(), r.centerY(), dp(4f), pal.c(2), d1, a / 255f)
                }
            } else {
                cv.drawRoundRect(r, dp(7f), dp(7f), p)
            }
        }

        // data cascade under the strip
        if (t - lastCascade > 90 && t < FADE_AT) {
            lastCascade = t
            System.arraycopy(cascade, 0, cascade, 1, cascade.size - 1)
            cascade[0] = intArrayOf(rnd.nextInt(9999), rnd.nextInt(99999))
        }
        val cf = step(t, 900, 1200)
        if (cf > 0) {
            tp.textSize = ts(13f)
            val cy = by + dp(48f)
            val rows = Math.max(0f, Math.min(cascade.size.toFloat(), (bottom - dp(170f) - cy) / dp(18f))).toInt()
            for (i in 0 until rows) {
                val fade = 1f - i / Math.max(1, rows).toFloat()
                tp.textAlign = Paint.Align.LEFT
                tp.color = withAlpha(pal.c(1), Math.round(a * cf * (0.25f + 0.6f * fade)))
                cv.drawText(String.format(Locale.US, "%04d", cascade[i][0]), x0, cy + i * dp(18f), tp)
                tp.color = withAlpha(pal.c(2), Math.round(a * cf * (0.2f + 0.5f * fade)))
                cv.drawText(String.format(Locale.US, "%05d", cascade[i][1]), x0 + dp(64f), cy + i * dp(18f), tp)
                tp.textAlign = Paint.Align.RIGHT
                tp.color = withAlpha(pal.c(3), Math.round(a * cf * (0.2f + 0.5f * fade)))
                cv.drawText(
                    String.format(Locale.US, "%02d-%04d", (cascade[i][0] % 90) + 10, cascade[i][1] % 10000), right, cy + i * dp(18f), tp,
                )
            }
        }

        // "ONLINE"
        val on = step(t, 2250, 2450)
        if (on > 0) {
            if (!onlineChirp && skipAt < 0) {
                onlineChirp = true
                Sfx.play(context, Sfx.STATUS_OK)
            }
            tp.textAlign = Paint.Align.RIGHT
            tp.textSize = ts(40f)
            tp.color = withAlpha(pal.c(2), Math.round(a * on))
            cv.drawText(DONE[faction], right, bottom - bar - dp(34f), tp)
        }
    }

    /**
     * Imperial: a chamfered header plate slides in and the targeting reticle opens. Collective: conduit
     * modules light from the left and the node network assembles. Neither uses an console elbow.
     */
    private fun factionFrame(cv: Canvas, t: Long, w: Int, h: Int, top: Float, bottom: Float, side: Float, bar: Float, a: Int) {
        val g1 = step(t, 0, 520)
        val g2 = step(t, 180, 700)
        if (faction == Skin.IMPERIAL) {
            val d1 = resources.displayMetrics.density
            val al = a / 255f
            // a long double-lined octagon frame opens out from the centre line
            val fw = (w - dp(12f)) * g1
            val fl = (w - fw) / 2f
            val ft = top
            val fb = bottom - bar
            val fh = (fb - ft) * g2
            if (fw > dp(40f) && fh > dp(20f)) {
                val my = (ft + fb) / 2f
                val o = ImperialDraw.octagon(Path(), fl, my - fh / 2, fl + fw, my + fh / 2, Math.min(dp(34f), fh * 0.2f), true, true)
                val inner = ImperialDraw.octagon(
                    Path(), fl + dp(5f), my - fh / 2 + dp(5f), fl + fw - dp(5f), my + fh / 2 - dp(5f),
                    Math.min(dp(30f), fh * 0.18f), true, true,
                )
                cv.save()
                cv.clipPath(inner)
                ImperialDraw.grid(cv, RectF(0f, 0f, w.toFloat(), h.toFloat()), dp(26f), ImperialDraw.RED, 0.12f * al, d1)
                cv.restore()
                ImperialDraw.frame(cv, o, inner, pal.c(0), d1, al)
            }
            // triangle rows sweep in along the bottom, clusters appear at the top corners
            val sl = step(t, 350, 750)
            if (sl > 0) {
                val rw = (w - dp(80f)) * sl
                ImperialDraw.row(
                    cv, dp(40f), bottom - bar * 0.9f, rw, bar * 0.7f,
                    intArrayOf(ImperialDraw.YELLOW, ImperialDraw.ORANGE, ImperialDraw.RED), al,
                )
                ImperialDraw.cluster(cv, dp(44f), top + dp(46f), dp(16f), al * sl)
                ImperialDraw.cluster(cv, w - dp(44f), top + dp(46f), dp(16f), al * sl)
            }
        } else {
            // hub node in the corner, a trace growing along the top through vias and nodes,
            // and another down the left side with nodes appearing as it passes
            val d1 = resources.displayMetrics.density
            val hx = dp(6f) + side * 0.5f
            val hy = top + bar * 0.5f
            val hr = dp(16f) * g1
            val lim = hx + (w - dp(18f) - hx) * g1
            var x = hx + dp(16f)
            val runs = floatArrayOf(0.18f, 0.24f, 0.16f, 0.27f, 0.15f)
            var i = 0
            while (x < lim) {
                val e = Math.min(lim, x + (w - hx) * runs[i % runs.size])
                CollectiveDraw.curveTrace(cv, x, hy, e, hy, if (i % 2 == 0) 0.07f else -0.06f, pal.c(0), d1, a / 255f)
                if (e < lim) {
                    if (i % 2 == 0) {
                        CollectiveDraw.via(cv, e, hy, dp(3f), pal.c(0), d1, a / 255f)
                    } else {
                        CollectiveDraw.node(cv, e, hy, dp(6f), pal.c(1), d1, a / 255f)
                    }
                }
                x = e
                i++
            }
            val lineB = hy + hr + (bottom - hy - hr) * g2
            if (lineB > hy + hr + 1) {
                val rail = Path()
                rail.moveTo(hx, hy + hr)
                rail.lineTo(hx, lineB)
                CollectiveDraw.trace(cv, rail, pal.c(0), d1, 0.8f * a / 255f)
                var y = hy + dp(120f)
                while (y < lineB) {
                    CollectiveDraw.curveTrace(cv, hx, y, hx + dp(40f), y + dp(30f), 0.2f, pal.c(0), d1, 0.6f * a / 255f)
                    CollectiveDraw.node(cv, hx, y, dp(7f), pal.c(1), d1, a / 255f)
                    CollectiveDraw.via(cv, hx + dp(40f), y + dp(30f), dp(3f), pal.c(0), d1, a / 255f)
                    y += dp(140f)
                }
            }
            if (hr > 1) CollectiveDraw.node(cv, hx, hy, hr, pal.c(0), d1, a / 255f)
        }
        // the faction ornament opens behind the title
        if (ornament != null) {
            val o = step(t, 250, 900)
            if (o > 0) {
                val cw = Math.round(dp(150f) * (0.6f + 0.4f * o))
                val ch = Math.round(dp(96f) * (0.6f + 0.4f * o))
                val cx = Math.round(side + dp(80f))
                val cy = Math.round(top + bar + dp(58f))
                ornament.setBounds(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
                ornament.alpha = Math.round(a * o)
                ornament.draw(cv)
            }
        }
    }

    // ==== Imperial: the triangles assemble ================================================================

    /**
     * Three blade slashes; small triangles fly in from the edges and lock into one large triangle
     * formation; a double-lined octagon traces itself around it; "TACTICAL" stamps down; a ▲▼
     * strip charges; readouts snap in; the formation flashes on ALL STATIONS READY.
     */
    private fun imperial(cv: Canvas, t: Long, w: Int, h: Int, al: Float) {
        // 1. slashes
        for (i in 0 until 3) {
            val e = step(t, i * 90L, 380 + i * 90L)
            val f = 1 - clamp((t - 450 - i * 90) / 400f)
            if (e <= 0 || f <= 0) continue
            val y = h * (0.18f + i * 0.08f)
            val len = w * 1.4f * e
            val th = dp((7 - i * 2).toFloat())
            path.reset()
            path.moveTo(-dp(20f), y + dp(40f))
            path.lineTo(-dp(20f) + len, y - len * 0.25f + dp(40f))
            path.lineTo(-dp(20f) + len, y - len * 0.25f + dp(40f) + th)
            path.lineTo(-dp(20f), y + dp(40f) + th)
            path.close()
            p.style = Paint.Style.FILL
            p.color = Ui.alpha(if (i == 1) ImperialDraw.ORANGE else ImperialDraw.RED, al * f)
            cv.drawPath(path, p)
        }
        if (!midChirp && t >= 350 && skipAt < 0) {
            midChirp = true
            Stations.play(context, "boot_mid")
        }
        // 2. formation: a big triangle tiled by small ones (up and down), flying in
        val bigS = Math.min(w * 0.72f, h * 0.36f)
        val s = bigS / K_ROWS
        val hs = s * 0.866f
        val cx = w / 2f
        val top = h * 0.2f
        val total = K_ROWS * K_ROWS
        val ks = kStart ?: FloatArray(total * 3).also { arr ->
            for (k in 0 until total) {
                val ang = rnd.nextDouble() * Math.PI * 2
                arr[k * 3] = 0.5f + Math.cos(ang).toFloat() * 0.9f
                arr[k * 3 + 1] = 0.5f + Math.sin(ang).toFloat() * 0.9f
                arr[k * 3 + 2] = (rnd.nextFloat() - 0.5f) * 540
            }
            kStart = arr
        }
        val flash = clamp(1 - Math.abs(t - 2650) / 160f)
        var k = 0
        for (row in 0 until K_ROWS) {
            val rowColor = if (row < 2) ImperialDraw.YELLOW else if (row < 4) ImperialDraw.ORANGE else ImperialDraw.RED
            for (j in 0..2 * row) {
                val idx = k++
                val up = j % 2 == 0
                val tx = cx + (j - row) * s / 2f
                val ty = top + row * hs + hs / 2f
                val a0 = 350 + idx * 17L
                val e = step(t, a0, a0 + 460)
                if (e <= 0) continue
                val sx = ks[idx * 3] * w
                val sy = ks[idx * 3 + 1] * h
                val x = sx + (tx - sx) * e
                val y = sy + (ty - sy) * e
                cv.save()
                cv.rotate(ks[idx * 3 + 2] * (1 - e), x, y)
                val c = if (up) rowColor else ImperialDraw.DEEP
                val bright = if (up) 1f else 0.85f
                ImperialDraw.tri(
                    cv, x, y, s * 0.94f, hs * 0.94f, up, if (flash > 0) Skin.mix(c, 0xFFFFE08A.toInt(), flash * 0.45f) else c, al * bright,
                )
                cv.restore()
            }
        }
        // 3. octagon frame traces around the formation
        val half = Math.min(bigS * 0.72f, w / 2f - dp(12f))
        val fl = cx - half
        val fr = cx + half
        val ft = top - dp(22f)
        val fb = top + K_ROWS * hs + dp(22f)
        val fe = step(t, 1050, 1650)
        if (fe > 0) {
            val o = ImperialDraw.octagon(Path(), fl, ft, fr, fb, dp(30f), true, true)
            val inner = ImperialDraw.octagon(Path(), fl + dp(6f), ft + dp(6f), fr - dp(6f), fb - dp(6f), dp(26f), true, true)
            partial(cv, o, fe, ImperialDraw.RED, dp(2.6f), al)
            partial(cv, inner, fe, ImperialDraw.RED, dp(1f), al * 0.5f)
        }
        // 4. title stamps down, subtitle, charging ▲▼ strip
        val ty0 = fb + dp(70f)
        val st = clamp((t - 1500) / 180f)
        if (st > 0) {
            val sc = 1.5f - 0.5f * (1 - (1 - st) * (1 - st))
            tp.textAlign = Paint.Align.CENTER
            tp.textSize = ts(60f) * sc
            tp.color = Ui.alpha(ImperialDraw.RED, al * st)
            cv.drawText("TACTICAL", cx, ty0, tp)
            tp.textSize = ts(15f)
            tp.color = Ui.alpha(ImperialDraw.ORANGE, al * st)
            cv.drawText("IMPERIAL TACTICAL / WARRIOR CONTROL", cx, ty0 + dp(26f), tp)
        }
        val rowY = ty0 + dp(44f)
        val rowW = Math.min(w * 0.8f, dp(320f))
        val tri = dp(16f)
        val n = (rowW / (tri / 2)).toInt() - 1
        val charged = clamp((t - 1700) / 700f) * n
        for (i in 0 until n) {
            val lit = i < charged
            val c = intArrayOf(ImperialDraw.YELLOW, ImperialDraw.ORANGE, ImperialDraw.RED)[i % 3]
            ImperialDraw.tri(
                cv, cx - rowW / 2 + tri / 2 + i * tri / 2, rowY + tri / 2, tri * 0.92f, tri * 0.8f, i % 2 == 0,
                if (lit) c else Ui.darken(c, 0.25f), al * (if (t > 1650) 1 else 0),
            )
        }
        // 5. readouts snap in, then ALL STATIONS READY
        val lines = arrayOf(arrayOf("WEAPONS", "ARMED"), arrayOf("SHIELDS", "READY"), arrayOf("CLOAK", "STANDING BY"))
        tp.textSize = ts(17f)
        for (i in lines.indices) {
            val at = 1950 + i * 160L
            if (t < at) break
            val y = rowY + dp(56f) + i * dp(28f)
            tp.textAlign = Paint.Align.LEFT
            tp.color = Ui.alpha(pal.textHi, al)
            cv.drawText(lines[i][0], cx - rowW / 2, y, tp)
            tp.textAlign = Paint.Align.RIGHT
            tp.color = Ui.alpha(ImperialDraw.YELLOW, al)
            cv.drawText(lines[i][1], cx + rowW / 2, y, tp)
        }
        if (t > 2550) {
            if (!readyChirp && skipAt < 0) {
                readyChirp = true
                Stations.play(context, "boot_ready")
            }
            tp.textAlign = Paint.Align.CENTER
            tp.textSize = ts(34f)
            tp.color = Ui.alpha(ImperialDraw.YELLOW, al * step(t, 2550, 2700))
            cv.drawText("ALL STATIONS READY", cx, h - dp(70f), tp)
        }
    }

    /** Draw the first fraction of a path's outline (the frame "traces itself"). */
    private fun partial(cv: Canvas, src: Path, fraction: Float, color: Int, width: Float, al: Float) {
        val pm = PathMeasure(src, true)
        val part = Path()
        pm.getSegment(0f, pm.length * clamp(fraction), part, true)
        p.style = Paint.Style.STROKE
        p.strokeWidth = width
        p.color = Ui.alpha(color, al)
        cv.drawPath(part, p)
        p.style = Paint.Style.FILL
    }

    // ==== Collective: the collective links up ==================================================================

    /**
     * A faint trace network fades in; the hub ignites with rings rippling outward; traces grow to
     * ten nodes one after another, each lighting and naming itself as its trace arrives; the
     * subunit's designation types out; every node pulses together on LINK ESTABLISHED.
     */
    private fun collective(cv: Canvas, t: Long, w: Int, h: Int, al: Float) {
        val d = resources.displayMetrics.density
        val line = pal.c(0)
        val hi = pal.c(1)
        // 1. background network
        val back = collectiveBack ?: Skin.backdrop(context).also { collectiveBack = it }
        val bf = step(t, 0, 600) * al
        if (bf > 0) {
            val save = cv.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), Math.round(bf * 170))
            back.setBounds(0, 0, w, h)
            back.draw(cv)
            cv.restoreToCount(save)
        }
        // radial geometry follows the narrower of width and (portrait-shaped) height, so the
        // network stays on screen when the stage is wider than tall
        val span = Math.min(w.toFloat(), h * 0.62f)
        val cx = w / 2f
        val cy = h * 0.4f
        val hubR = Math.min(w, h) * 0.075f
        // node layout: four inner, six outer, outer ones branching from the nearest inner
        if (bx == null) {
            val nx = FloatArray(10)
            val ny = FloatArray(10)
            val nr = FloatArray(10)
            val parent = IntArray(10)
            val ia = floatArrayOf(-100f, -10f, 80f, 170f)
            val oa = floatArrayOf(-140f, -70f, -20f, 40f, 115f, 200f)
            val rs = floatArrayOf(9f, 7f, 8f, 6f, 6f, 8f, 5f, 7f, 6f, 9f)
            for (i in 0 until 4) {
                val ang = Math.toRadians(ia[i].toDouble())
                nx[i] = cx + Math.cos(ang).toFloat() * span * 0.22f
                ny[i] = cy + Math.sin(ang).toFloat() * span * 0.26f
                parent[i] = -1
            }
            for (i in 0 until 6) {
                val ang = Math.toRadians(oa[i].toDouble())
                nx[4 + i] = cx + Math.cos(ang).toFloat() * span * 0.41f
                ny[4 + i] = cy + Math.sin(ang).toFloat() * span * 0.5f
                var best = 0
                var bd = Float.MAX_VALUE
                for (j in 0 until 4) {
                    val dd = (nx[j] - nx[4 + i]) * (nx[j] - nx[4 + i]) + (ny[j] - ny[4 + i]) * (ny[j] - ny[4 + i])
                    if (dd < bd) {
                        bd = dd
                        best = j
                    }
                }
                parent[4 + i] = best
            }
            for (i in 0 until 10) nr[i] = rs[i] * d
            bx = nx
            by = ny
            br = nr
            bParent = parent
        }
        val bx = bx!!
        val by = by!!
        val br = br!!
        val bParent = bParent!!
        // 2. hub ignites, rings ripple outward
        val ig = step(t, 250, 700)
        for (k in 0 until 3) {
            val rp = ((t - 300 - k * 260) % 1100) / 1100f
            if (t < 300 + k * 260 || t > 2900) continue
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(1.5f)
            p.color = Ui.alpha(hi, al * (1 - rp) * 0.6f)
            cv.drawCircle(cx, cy, hubR + rp * span * 0.45f, p)
        }
        p.style = Paint.Style.FILL
        // crosshair once linked
        val ch = step(t, 2600, 2950)
        if (ch > 0) {
            val cr = Path()
            cr.moveTo(cx - w * 0.47f * ch, cy)
            cr.lineTo(cx + w * 0.47f * ch, cy)
            cr.moveTo(cx, cy - h * 0.3f * ch)
            cr.lineTo(cx, cy + h * 0.3f * ch)
            CollectiveDraw.traceW(cv, cr, hi, d, 0.35f * al, 1f)
        }
        if (!midChirp && t >= 700 && skipAt < 0) {
            midChirp = true
            Stations.play(context, "boot_mid")
        }
        // 3. traces grow and nodes light, one after another
        val pulse = 1 + 0.35f * clamp(1 - Math.abs(t - 2900) / 180f)
        for (i in 0 until 10) {
            val t0 = 700 + i * 120L
            val g = step(t, t0, t0 + 340)
            if (g <= 0) continue
            val px = if (bParent[i] < 0) cx else bx[bParent[i]]
            val py = if (bParent[i] < 0) cy else by[bParent[i]]
            val ex = px + (bx[i] - px) * g
            val ey = py + (by[i] - py) * g
            CollectiveDraw.traceW(
                cv, CollectiveDraw.curve(px, py, ex, ey, (i % 3 - 1) * 0.12f), line, d, al, if (bParent[i] < 0) 2.4f else 1.5f,
            )
            if (g >= 1) {
                val lit = step(t, t0 + 340, t0 + 520)
                CollectiveDraw.node(cv, bx[i], by[i], br[i] * pulse, if (i % 2 == 0) hi else line, d, al * lit)
                tp.textAlign = if (bx[i] < cx) Paint.Align.RIGHT else Paint.Align.LEFT
                tp.textSize = ts(11f)
                tp.color = Ui.alpha(hi, al * lit * 0.85f)
                cv.drawText(
                    String.format(Locale.US, "NODE %02d", i + 1), bx[i] + (if (bx[i] < cx) -1 else 1) * (br[i] + dp(6f)), by[i] + dp(4f), tp,
                )
            } else {
                CollectiveDraw.via(cv, ex, ey, dp(2.5f), line, d, al)
            }
        }
        if (ig > 0) {
            CollectiveDraw.ringNode(cv, cx, cy, hubR * (0.6f + 0.4f * ig) * pulse, hi, d, al)
            CollectiveDraw.node(cv, cx, cy, hubR * 0.55f * ig * pulse, line, d, al)
        }
        // 4. designation types out under the network
        val ty = cy + span * 0.5f + dp(64f)
        tp.textAlign = Paint.Align.CENTER
        val l1 = "NODE CLUSTER 01"
        val l2 = "SUBUNIT LINKED TO THE COLLECTIVE"
        val c1 = Math.min(l1.length.toLong(), Math.max(0L, (t - 1500) / 45)).toInt()
        val c2 = Math.min(l2.length.toLong(), Math.max(0L, (t - 2100) / 22)).toInt()
        tp.textSize = ts(30f)
        tp.color = Ui.alpha(hi, al)
        if (c1 > 0) cv.drawText(l1.substring(0, c1) + (if (c1 < l1.length && (t / 120) % 2 == 0L) "_" else ""), cx, ty, tp)
        tp.textSize = ts(14f)
        tp.color = Ui.alpha(line, al)
        if (c2 > 0) cv.drawText(l2.substring(0, c2), cx, ty + dp(26f), tp)
        if (t > 2850) {
            if (!readyChirp && skipAt < 0) {
                readyChirp = true
                Stations.play(context, "boot_ready")
            }
            tp.textSize = ts(32f)
            tp.color = Ui.alpha(hi, al * step(t, 2850, 3000))
            cv.drawText("LINK ESTABLISHED", cx, h - dp(70f), tp)
        }
    }

    /** An console elbow: bar of height `bar` along the top, sidebar down the left, rounded joint. */
    private fun elbow(
        cv: Canvas, y: Float, side: Float, bar: Float, ro: Float, ri: Float, len0: Float, depth0: Float, color: Int, a: Int,
        flip: Boolean,
    ) {
        if (len0 <= 1 || depth0 <= 1) return
        val len = Math.max(len0, ro)
        val depth = Math.max(depth0, ro)
        val ri2 = Math.min(ri, Math.max(1f, len - side))
        path.reset()
        path.addRoundRect(RectF(0f, 0f, len + ro * 2, depth + ro * 2), ro, ro, Path.Direction.CW)
        val cut = Path()
        cut.addRoundRect(RectF(side, bar, len + ri2 * 4, depth + ri2 * 4), ri2, ri2, Path.Direction.CW)
        path.op(cut, Path.Op.DIFFERENCE)
        val clip = Path()
        clip.addRect(0f, 0f, len, depth, Path.Direction.CW)
        path.op(clip, Path.Op.INTERSECT)
        cv.save()
        if (flip) {
            cv.translate(0f, y)
            cv.scale(1f, -1f)
        } else {
            cv.translate(0f, y)
        }
        p.color = withAlpha(color, a)
        cv.drawPath(path, p)
        cv.restore()
    }

    /** Removes the overlay (once) and runs the completion callback. */
    private fun finish() {
        if (finished) return
        finished = true
        post {
            (parent as ViewGroup?)?.removeView(this@BootSequence)
            onDone?.run()
        }
    }

    companion object {
        /** Command sequence length and when it starts fading out, in ms. */
        private const val DURATION = 3150L
        private const val FADE_AT = 2700L

        /** Smallest stage the sequences are designed for, in dp. */
        const val MIN_STAGE_W_DP = 360f
        const val MIN_STAGE_H_DP = 640f

        /** Fade-out after a tap to skip, in ms. */
        private const val SKIP_FADE_MS = 250f
        private val LINES = arrayOf(
            arrayOf("MEMORY CORE", "OPTICAL DATA NETWORK", "SUBSPACE TRANSCEIVER", "SENSOR ARRAY", "COMMUNICATIONS", "PERSONAL ACCESS TERMINAL"),
            arrayOf("TACTICAL ARRAY", "TARGETING SENSORS", "SUBSPACE RADIO", "SHIELD GRID", "COMMUNICATIONS", "WARRIOR CONTROL"),
            arrayOf(
                "NODE 01 / CORE NEXUS LINK", "NODE 02 / SUBSPACE NETWORK", "NODE 03 / SENSOR MATRIX", "NODE 04 / INTERFACE CELLS",
                "NODE 05 / COMMUNICATIONS", "NODE 06 / LOCAL TERMINAL",
            ),
        )
        private val OK = arrayOf("ONLINE", "READY", "LINKED")
        private val DONE = arrayOf("ALL SYSTEMS ONLINE", "ALL STATIONS READY", "LINK ESTABLISHED")

        /** Imperial sequence length and fade start, and the rows of its triangle formation. */
        private const val K_DURATION = 3500L
        private const val K_FADE = 3050L
        private const val K_ROWS = 6

        /** Collective sequence length and fade start. */
        private const val B_DURATION = 3700L
        private const val B_FADE = 3250L

        /**
         * Scale applied to the design stage: the screen's UI scale, reduced so the stage is never
         * shorter than [MIN_STAGE_H_DP] or narrower than [MIN_STAGE_W_DP].
         *
         * @param wPx view width in pixels
         * @param hPx view height in pixels
         * @param density pixels per dp
         * @param uiScale [ScreenMetrics.uiScale]
         */
        @JvmStatic
        fun stageScale(wPx: Float, hPx: Float, density: Float, uiScale: Float): Float {
            if (wPx <= 0 || hPx <= 0 || density <= 0) return 1f
            val s = Math.min(uiScale, Math.min(hPx / (MIN_STAGE_H_DP * density), wPx / (MIN_STAGE_W_DP * density)))
            return Math.max(0.3f, s)
        }

        private fun clamp(v: Float): Float = if (v < 0) 0f else if (v > 1) 1f else v

        /** Ease-out progress of a step that runs from a to b milliseconds. */
        private fun step(t: Long, a: Long, b: Long): Float {
            val x = clamp((t - a) / (b - a).toFloat())
            return 1 - (1 - x) * (1 - x)
        }

        /** Scales a colour's own alpha by a (0..255). */
        private fun withAlpha(color: Int, a: Int): Int =
            (color and 0x00FFFFFF) or (Math.round(((color ushr 24) / 255f) * a) shl 24)
    }
}
