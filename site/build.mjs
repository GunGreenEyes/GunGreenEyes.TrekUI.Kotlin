#!/usr/bin/env node
// Builds the TrekUI Launcher product site into site/_out (GitHub Pages artifact).
//
//   node site/build.mjs            build with whatever media is in assets/
//   node site/build.mjs --serve    build, then serve site/_out on http://localhost:8080
//
// No npm dependencies. Thumbnails and video posters are made with ffmpeg (or ImageMagick) when one
// is on PATH; without either the gallery uses the full-size images.
//
// Inputs (all optional except the app sources):
//   assets/<device>-api<N>/        screenshots (*.png|jpg|webp), videos (*.mp4) and meta.json from CI
//                                  (.github/scripts/emulator-run.sh) or scripts/capture/ locally
//   assets/robolectric/            Robolectric (JVM) screenshots
//   assets/index.json              capture index from the testing tools; merged with the meta.json files.
//                                  Accepted shapes: {runs:[{id|device+api, orientation, tests:{total,passed,
//                                  failed,skipped}, cases:[{class,name,status}], screenshots:[...]}]} or
//                                  {devices:{<id>:{...same fields}}} or a bare array of runs.
//   PRIVACY.md, CHANGELOG.md       rendered to privacy.html, changelog.html
//   fastlane/metadata/launcher/android/en-US/changelogs/<versionCode>.txt + fastlane/release-history.json
//   version.properties, app/src/main/assets/fonts/Antonio.ttf, app/src/main/res/drawable/ic_launcher_fg.xml
//
// Output: site/_out/{index,privacy,changelog,404}.html, style.css, app.js, fonts/, media/, data/index.json
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import http from "node:http";

const SITE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(SITE, "..");
const OUT = path.join(SITE, "_out");
const CACHE = path.join(SITE, ".cache");
// SITE_ASSETS=<dir> builds from another media folder (e.g. a downloaded site-media.zip).
const ASSETS = process.env.SITE_ASSETS ? path.resolve(process.env.SITE_ASSETS) : path.join(ROOT, "assets");

const REPO = "GunGreenEyes/TrekUI.Kotlin";
const REPO_URL = `https://github.com/${REPO}`;
const PACKAGE = "com.gungreeneyes.trekui";
const PLAY_URL = `https://play.google.com/store/apps/details?id=${PACKAGE}`;
const RELEASES_URL = `${REPO_URL}/releases/latest`;

const IMAGE_EXT = new Set([".png", ".jpg", ".jpeg", ".webp"]);
const VIDEO_EXT = new Set([".mp4", ".webm"]);

// ---------------------------------------------------------------------------------------------- util
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const readText = (p) => (fs.existsSync(p) ? fs.readFileSync(p, "utf8") : null);
const readJson = (p) => { try { return JSON.parse(fs.readFileSync(p, "utf8")); } catch { return null; } };
const urlPath = (...parts) => parts.map((p) => p.split(/[\\/]/).map(encodeURIComponent).join("/")).join("/");
function rmrf(p) { fs.rmSync(p, { recursive: true, force: true }); }
function mkdirp(p) { fs.mkdirSync(p, { recursive: true }); }
function copy(src, dst) { mkdirp(path.dirname(dst)); fs.copyFileSync(src, dst); }
function have(cmd, args = ["-version"]) {
  try { return spawnSync(cmd, args, { stdio: "ignore" }).status === 0; } catch { return false; }
}
function properties(text) {
  const o = {};
  for (const line of (text || "").split(/\r?\n/)) {
    const m = /^\s*([^#=\s][^=]*?)\s*=\s*(.*)$/.exec(line);
    if (m) o[m[1]] = m[2].trim();
  }
  return o;
}
function humanize(s) {
  return s.replace(/\.[^.]+$/, "").replace(/^\d+[-_ ]+/, "").replace(/[_-]+/g, " ").replace(/\s+/g, " ").trim();
}

/** Width/height of a PNG (IHDR) or JPEG (SOFn), or null. */
function imageSize(file) {
  try {
    const fd = fs.openSync(file, "r");
    const buf = Buffer.alloc(65536);
    const n = fs.readSync(fd, buf, 0, buf.length, 0);
    fs.closeSync(fd);
    if (buf.readUInt32BE(0) === 0x89504e47) return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) };
    if (buf[0] === 0xff && buf[1] === 0xd8) {
      let i = 2;
      while (i < n - 9) {
        if (buf[i] !== 0xff) { i++; continue; }
        const marker = buf[i + 1];
        const len = buf.readUInt16BE(i + 2);
        if (marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker)) {
          return { w: buf.readUInt16BE(i + 7), h: buf.readUInt16BE(i + 5) };
        }
        i += 2 + len;
      }
    }
  } catch { /* unreadable */ }
  return null;
}

// --------------------------------------------------------------------------------------- markdown
/** Maps repository-relative Markdown links to site pages or GitHub URLs. */
function siteLink(href) {
  if (/^(https?:|mailto:|#)/.test(href)) return href;
  const clean = href.replace(/^\.\//, "");
  if (/^PRIVACY\.md(#.*)?$/i.test(clean)) return clean.replace(/^PRIVACY\.md/i, "privacy.html");
  if (/^CHANGELOG\.md(#.*)?$/i.test(clean)) return clean.replace(/^CHANGELOG\.md/i, "changelog.html");
  return `${REPO_URL}/blob/main/${clean}`;
}

/** Small Markdown renderer (headings, paragraphs, lists, quotes, code, tables, links, emphasis). */
function markdown(md) {
  const inline = (t) => {
    const codes = [];
    t = t.replace(/`([^`]+)`/g, (_, c) => `\u0000${codes.push(c) - 1}\u0000`);
    t = esc(t)
      .replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, '<img src="$2" alt="$1">')
      .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, txt, href) => `<a href="${siteLink(href)}">${txt}</a>`)
      .replace(/(^|[\s(])(https?:\/\/[^\s<)]+)/g, '$1<a href="$2">$2</a>')
      .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/(^|[^*\w])\*([^*\s][^*]*)\*/g, "$1<em>$2</em>")
      .replace(/(^|\W)_([^_\s][^_]*)_(?=\W|$)/g, "$1<em>$2</em>");
    return t.replace(/\u0000(\d+)\u0000/g, (_, i) => `<code>${esc(codes[+i])}</code>`);
  };
  const lines = md.replace(/\r\n?/g, "\n").split("\n");
  const out = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (/^\s*$/.test(line)) { i++; continue; }
    let m;
    if ((m = /^```/.exec(line))) {
      const buf = [];
      i++;
      while (i < lines.length && !/^```/.test(lines[i])) buf.push(lines[i++]);
      i++;
      out.push(`<pre><code>${esc(buf.join("\n"))}</code></pre>`);
      continue;
    }
    if ((m = /^(#{1,6})\s+(.*?)\s*#*$/.exec(line))) {
      const lvl = m[1].length;
      const id = m[2].toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
      out.push(`<h${lvl} id="${id}">${inline(m[2])}</h${lvl}>`);
      i++; continue;
    }
    if (/^(\*\s*){3,}$|^(-\s*){3,}$|^(_\s*){3,}$/.test(line.trim())) { out.push("<hr>"); i++; continue; }
    if (/^>\s?/.test(line)) {
      const buf = [];
      while (i < lines.length && /^>\s?/.test(lines[i])) buf.push(lines[i++].replace(/^>\s?/, ""));
      out.push(`<blockquote>${markdown(buf.join("\n"))}</blockquote>`);
      continue;
    }
    if (/^\|/.test(line) && i + 1 < lines.length && /^\|?\s*:?-{2,}/.test(lines[i + 1])) {
      const cells = (l) => l.trim().replace(/^\||\|$/g, "").split("|").map((c) => c.trim());
      const head = cells(line);
      i += 2;
      const rows = [];
      while (i < lines.length && /^\|/.test(lines[i])) rows.push(cells(lines[i++]));
      out.push(`<table><thead><tr>${head.map((h) => `<th>${inline(h)}</th>`).join("")}</tr></thead><tbody>${
        rows.map((r) => `<tr>${r.map((c) => `<td>${inline(c)}</td>`).join("")}</tr>`).join("")}</tbody></table>`);
      continue;
    }
    if (/^\s*([-*+]|\d+[.)])\s+/.test(line)) {
      const ordered = /^\s*\d/.test(line);
      const items = [];
      while (i < lines.length && (/^\s*([-*+]|\d+[.)])\s+/.test(lines[i]) || (/^\s{2,}\S/.test(lines[i]) && items.length))) {
        if (/^\s*([-*+]|\d+[.)])\s+/.test(lines[i]) && !/^\s{2,}/.test(lines[i])) items.push(lines[i].replace(/^\s*([-*+]|\d+[.)])\s+/, ""));
        else items[items.length - 1] += "\n" + lines[i].replace(/^\s{2,4}/, "");
        i++;
      }
      const tag = ordered ? "ol" : "ul";
      out.push(`<${tag}>${items.map((it) => {
        const [first, ...rest] = it.split("\n");
        return `<li>${inline(first)}${rest.length ? markdown(rest.join("\n")) : ""}</li>`;
      }).join("")}</${tag}>`);
      continue;
    }
    const buf = [];
    while (i < lines.length && !/^\s*$/.test(lines[i]) && !/^(#{1,6}\s|```|>|\|)/.test(lines[i]) && !/^\s*([-*+]|\d+[.)])\s+/.test(lines[i])) buf.push(lines[i++]);
    out.push(`<p>${inline(buf.join("\n")).replace(/ {2}\n/g, "<br>\n")}</p>`);
  }
  return out.join("\n");
}

// --------------------------------------------------------------------------------------- icon svg
/** The launcher icon (adaptive-icon foreground vector) as inline SVG; vector pathData is SVG path syntax. */
function iconSvg(cls = "", title = "TrekUI Launcher icon") {
  const xml = readText(path.join(ROOT, "app/src/main/res/drawable/ic_launcher_fg.xml")) || "";
  const paths = [...xml.matchAll(/<path[^>]*?android:fillColor="([^"]+)"[^>]*?android:pathData="([^"]+)"/gs)]
    .map(([, fill, d]) => `<path fill="${esc(fill.replace(/^#FF(?=[0-9a-f]{6}$)/i, "#"))}" d="${esc(d.replace(/\s+/g, " "))}"/>`);
  return `<svg class="${cls}" viewBox="22 22 68 72" role="img" aria-label="${esc(title)}" xmlns="http://www.w3.org/2000/svg">${paths.join("")}</svg>`;
}

// ------------------------------------------------------------------------------------- thumbnails
const tools = { ffmpeg: have("ffmpeg"), magick: have("magick", ["-version"]) };

function cached(src, kind, ext, make) {
  const st = fs.statSync(src);
  const key = `${path.relative(ASSETS, src).replace(/[\\/]/g, "__")}.${st.size}.${Math.floor(st.mtimeMs)}.${kind}${ext}`;
  const file = path.join(CACHE, key);
  if (!fs.existsSync(file)) {
    mkdirp(CACHE);
    if (!make(file) || !fs.existsSync(file)) return null;
  }
  return file;
}

function thumbnail(src) {
  return cached(src, "thumb", ".jpg", (dst) => {
    if (tools.ffmpeg) {
      return spawnSync("ffmpeg", ["-loglevel", "error", "-y", "-i", src, "-vf", "scale='min(480,iw)':-2", "-q:v", "4", dst]).status === 0;
    }
    if (tools.magick) return spawnSync("magick", [src, "-resize", "480x>", "-quality", "82", dst]).status === 0;
    return false;
  });
}

function poster(src) {
  if (!tools.ffmpeg) return null;
  return cached(src, "poster", ".jpg", (dst) =>
    spawnSync("ffmpeg", ["-loglevel", "error", "-y", "-ss", "2", "-i", src, "-frames:v", "1", "-vf", "scale='min(720,iw)':-2", "-q:v", "4", dst]).status === 0
    || spawnSync("ffmpeg", ["-loglevel", "error", "-y", "-i", src, "-frames:v", "1", "-q:v", "4", dst]).status === 0);
}

// ------------------------------------------------------------------------------------ media model
const FAMILY_LABEL = { phone: "Phone", tablet: "Tablet", foldable: "Foldable", robolectric: "Robolectric (JVM)", other: "Other" };

function familyOf(device) {
  const d = device.toLowerCase();
  if (d.includes("robolectric")) return "robolectric";
  if (d.includes("tablet")) return "tablet";
  if (d.includes("fold")) return "foldable";
  if (d.includes("phone") || d.includes("pixel")) return "phone";
  return "other";
}

/** "<scenario>[-<step>]__<device>__api<N>__<orientation>.png" (MediaCapture) or any other name. */
function parseShotName(file) {
  const base = file.replace(/\.[^.]+$/, "");
  const parts = base.split("__");
  if (parts.length >= 4 && /^api\d+$/.test(parts[parts.length - 2])) {
    return { title: humanize(parts.slice(0, parts.length - 3).join(" ")), orientation: parts[parts.length - 1] };
  }
  const o = /(^|[-_])(landscape|portrait|land|port)([-_]|$)/i.exec(base);
  return { title: humanize(base), orientation: o ? (o[2].toLowerCase().startsWith("land") ? "landscape" : "portrait") : null };
}

function normaliseRun(r) {
  if (!r || typeof r !== "object") return null;
  const id = r.id || r.run || r.name || (r.device && r.api ? `${r.device}-api${r.api}` : null);
  if (!id) return null;
  return { ...r, id: String(id) };
}

function indexRuns() {
  const idx = readJson(path.join(ASSETS, "index.json"));
  let runs = [];
  if (Array.isArray(idx)) runs = idx;
  else if (idx && Array.isArray(idx.runs)) runs = idx.runs;
  else if (idx && idx.devices && typeof idx.devices === "object") runs = Object.entries(idx.devices).map(([id, v]) => ({ id, ...v }));
  const map = new Map();
  for (const r of runs.map(normaliseRun).filter(Boolean)) map.set(r.id, r);
  return { raw: idx, map };
}

function collectRuns() {
  const { raw, map: fromIndex } = indexRuns();
  const runs = [];
  const subdirs = (d) => (fs.existsSync(d) ? fs.readdirSync(d, { withFileTypes: true }).filter((e) => e.isDirectory()).map((e) => e.name) : []);
  // robolectric/<interface>/ becomes one gallery group per interface
  const dirs = subdirs(ASSETS).flatMap((d) => {
    if (d !== "robolectric") return [d];
    const inner = subdirs(path.join(ASSETS, d));
    return inner.length ? inner.map((i) => `robolectric/${i}`) : [d];
  });
  const ids = new Set([...dirs, ...fromIndex.keys()]);
  for (const id of [...ids].sort()) {
    const dir = path.join(ASSETS, id);
    // CI meta.json (emulator-meta.py) or the local runner's result.json, over the merged index.json entry
    const meta = { ...(fromIndex.get(id) || {}), ...(readJson(path.join(dir, "result.json")) || {}), ...(readJson(path.join(dir, "meta.json")) || {}) };
    const m = /^(.*?)-api(\d+(?:\.\d+)?)(?:-(portrait|landscape))?$/.exec(id);
    const robo = id.startsWith("robolectric");
    const device = robo ? "robolectric" : String(meta.device || (m ? m[1] : id));
    const apiRaw = meta.api ?? (m ? m[2] : null);
    const api = apiRaw === null || apiRaw === undefined || apiRaw === "" ? null : Number.parseFloat(apiRaw);
    const family = robo ? "robolectric" : familyOf(`${meta.form || ""} ${device}`);
    // tests: totals object (meta.json) or a per-test array (result.json / index.json)
    const cases = (Array.isArray(meta.cases) ? meta.cases : Array.isArray(meta.tests) ? meta.tests : [])
      .map((c) => ({ ...c, status: c.status === "error" ? "failed" : c.status || "passed" }));
    let totals = meta.tests && !Array.isArray(meta.tests) ? meta.tests : null;
    if (!totals && cases.length) {
      const count = (st) => cases.filter((c) => c.status === st).length;
      totals = { total: cases.length, passed: count("passed"), failed: count("failed"), skipped: count("skipped") };
    }
    const status = meta.status ? (/^(passed|ok|success)$/i.test(meta.status) ? "passed" : /fail|error/i.test(meta.status) ? "failed" : meta.status) : null;
    const files = fs.existsSync(dir) ? walk(dir) : [];
    const shots = [];
    const videos = [];
    for (const f of files) {
      const ext = path.extname(f).toLowerCase();
      const rel = path.relative(dir, f);
      if (IMAGE_EXT.has(ext) && !/(^|[\\/])thumbs[\\/]/.test(rel)) {
        const parsed = parseShotName(path.basename(f));
        const size = imageSize(f);
        const orientation = parsed.orientation || (size ? (size.w > size.h ? "landscape" : "portrait") : meta.orientation || "portrait");
        const title = robo ? humanize(path.basename(f)) : parsed.title || humanize(path.basename(f));
        shots.push({ file: f, rel, title, orientation, size });
      } else if (VIDEO_EXT.has(ext)) {
        const parsed = parseShotName(path.basename(f));
        videos.push({ file: f, rel, title: parsed.title === "run" ? "Full test run" : parsed.title });
      }
    }
    shots.sort((a, b) => a.rel.localeCompare(b.rel, "en", { numeric: true }));
    videos.sort((a, b) => a.rel.localeCompare(b.rel, "en", { numeric: true }));
    runs.push({
      id, device, api, family,
      label: robo && id.includes("/") ? humanize(id.split("/")[1]) : null,
      orientation: (/^(portrait|landscape)$/.test(meta.orientation) && meta.orientation) || (m && m[3]) ||
        (device.includes("land") ? "landscape" : shots[0]?.orientation || "portrait"),
      profile: meta.profile || meta.avd || "",
      status, tests: totals, cases,
      generated: meta.generated || meta.started || null,
      shots, videos,
    });
  }
  const order = { phone: 0, foldable: 1, tablet: 2, other: 3, robolectric: 4 };
  runs.sort((a, b) => (order[a.family] - order[b.family]) || ((a.api || 0) - (b.api || 0)) || a.id.localeCompare(b.id));
  return { runs, rawIndex: raw };
}

function walk(dir) {
  const out = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p));
    else out.push(p);
  }
  return out;
}

function runLabel(r) {
  const fam = FAMILY_LABEL[r.family] || r.device;
  const parts = [fam];
  if (r.label) parts.push(r.label);
  if (r.api) parts.push(`API ${r.api}`);
  if (r.family !== "robolectric") parts.push(r.orientation);
  return parts.join(" · ");
}

/** Copies media into _out/media and attaches web paths, thumbnail and poster paths. */
function publishMedia(runs) {
  for (const r of runs) {
    for (const s of r.shots) {
      const dst = path.join(OUT, "media", r.id, s.rel);
      copy(s.file, dst);
      s.url = urlPath("media", r.id, s.rel);
      const t = thumbnail(s.file);
      if (t) {
        const tdst = path.join(OUT, "media", r.id, "thumbs", s.rel.replace(/\.[^.]+$/, ".jpg"));
        copy(t, tdst);
        s.thumb = urlPath("media", r.id, "thumbs", s.rel.replace(/\.[^.]+$/, ".jpg"));
      } else s.thumb = s.url;
    }
    for (const v of r.videos) {
      copy(v.file, path.join(OUT, "media", r.id, v.rel));
      v.url = urlPath("media", r.id, v.rel);
      const p = poster(v.file);
      if (p) {
        const rel = v.rel.replace(/\.[^.]+$/, ".poster.jpg");
        copy(p, path.join(OUT, "media", r.id, rel));
        v.poster = urlPath("media", r.id, rel);
      } else {
        v.poster = r.shots[0]?.thumb || null;
      }
    }
  }
}

// ------------------------------------------------------------------------------------- page shell
const NAV = [
  ["index.html#overview", "Overview", "k1"],
  ["index.html#interfaces", "Interfaces", "k2"],
  ["index.html#gallery", "Gallery", "k3"],
  ["index.html#video", "Video", "k4"],
  ["index.html#diagnostics", "Diagnostics", "k5"],
  ["index.html#download", "Download", "k6"],
  ["changelog.html", "Changelog", "k2"],
  ["privacy.html", "Privacy", "k1"],
];

// The app's COMMAND sub-themes; the colours themselves live in site/src/style.css ([data-theme]).
const THEMES = [
  ["classic", "Classic", ["#ea9c72", "#fcc19f", "#baa4e5", "#8899ff"]],
  ["deep-blue", "Deep Blue", ["#2266ff", "#6699ff", "#88bbff", "#adcaff"]],
  ["sunset-deck", "Sunset Deck", ["#ff7700", "#ff4400", "#ffaa44", "#ffcc99"]],
  ["night-padd", "Night PADD", ["#5588ee", "#7799dd", "#88eeff", "#99ccff"]],
  ["stellar-silver", "Stellar Silver", ["#828cad", "#70b5ff", "#ffbb33", "#ffe1ca"]],
  ["modern-gray", "Modern Gray", ["#9ea5ba", "#41c4f7", "#37a6d1", "#d2d5df"]],
];

function themePicker() {
  const buttons = THEMES.map(([id, label, sw]) =>
    `<button type="button" data-theme-pick="${id}" aria-pressed="${id === "classic"}"><span class="sw" aria-hidden="true">${sw.map((c) => `<i style="background:${c}"></i>`).join("")}</span>${esc(label)}</button>`).join("");
  return `<div class="themes" role="group" aria-label="Colour theme"><span class="label">Theme</span>${buttons}</div>`;
}

function page({ file, title, description, body, version }) {
  const nav = NAV.map(([href, label, k]) => {
    const current = href === file ? ' aria-current="page"' : "";
    const h = file === "index.html" && href.startsWith("index.html#") ? href.slice("index.html".length) : href;
    return `<a class="${k}" href="${h}"${current}>${label}</a>`;
  }).join("\n      ");
  const favicon = `data:image/svg+xml,${encodeURIComponent(iconSvg("", "TrekUI").replace('viewBox="22 22 68 72"', 'viewBox="0 0 108 108"').replace("<svg ", '<svg style="background:#000" '))}`;
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<meta name="description" content="${esc(description)}">
<meta name="theme-color" content="#000000">
<meta property="og:title" content="${esc(title)}">
<meta property="og:description" content="${esc(description)}">
<meta property="og:type" content="website">
<link rel="icon" href="${favicon}">
<link rel="preload" href="fonts/Antonio.ttf" as="font" type="font/ttf" crossorigin>
<link rel="stylesheet" href="style.css">
<script>try{var t=localStorage.getItem("trekui-theme");if(t)document.documentElement.dataset.theme=t}catch(e){}</script>
</head>
<body>
<a class="skip" href="#main">Skip to content</a>
<div class="frame">
  <header class="top">
    <div class="elbow" aria-hidden="true"><span class="code">TrekUI ${esc(version.versionCode || "")}</span></div>
    <div class="run">
      <div class="bar" aria-hidden="true"><span class="grow"></span><span class="seg-a"></span><span class="seg-b"></span><span class="cap"></span></div>
      <div class="title-row">
        <p class="site-title"><a href="index.html">TrekUI Launcher</a></p>
        <span class="title-meta">Version ${esc(version.versionName || "")} · Android 8.0+</span>
      </div>
    </div>
  </header>
  <nav class="rail" aria-label="Site">
      ${nav}
      <span class="fill" aria-hidden="true"></span>
  </nav>
  <main id="main" tabindex="-1">
${body}
  </main>
  <footer class="bottom">
    <div class="elbow" aria-hidden="true"></div>
    <div class="run">
      <div class="title-row">
        ${themePicker()}
        <p class="disclaimer">TrekUI Launcher is an independent, original interface for Android.
        The Play build ships no third-party recordings or artwork.
        <a href="${REPO_URL}">Source on GitHub</a> · <a href="privacy.html">Privacy policy</a></p>
      </div>
      <div class="bar" aria-hidden="true"><span class="grow"></span><span class="seg-a"></span><span class="seg-b"></span><span class="cap"></span></div>
    </div>
  </footer>
</div>
<script src="app.js" defer></script>
</body>
</html>
`;
}

function section(id, title, color, num, inner) {
  return `<section class="sec" id="${id}" aria-labelledby="${id}-h" style="--c: var(--${color})">
  <div class="sec-h"><h2 id="${id}-h">${esc(title)}</h2><span class="num" aria-hidden="true">${esc(num)}</span></div>
${inner}
</section>`;
}

// ------------------------------------------------------------------------------------------ index
const FAMILIES = [
  {
    name: "Starship interfaces", blurb: "Three complete interfaces with their own geometry, lettering and colour, plus the Classic and Next consoles. Every function works in all of them.",
    items: [
      ["command", "Command", "Command", "Curved elbows, solid colour keys and the Antonio typeface on black. The ACCESS key unfolds into the CONSOLE CASCADE: a segmented spine with tile keys extending left and right."],
      ["imperial", "Imperial", "Imperial tactical", "Hollow double-lined octagons, triangle lattices and blood-red brackets, lettered in Warrior Blade, a font drawn for the launcher. DEPLOY slides the tiles out as an arrowhead."],
      ["collective", "Collective", "Collective", "No hard edges: luminous nodes on circuit traces. HUB AND SPOKE packs every app into orbits around a central hub that always fits the screen without scrolling."],
    ],
  },
  {
    name: "Pipe Lounge interfaces", blurb: "Six warm, analogue interfaces in walnut, brass, leather and lacquer, with a pipe register, blend catalogue and smoking journal built in.",
    items: [
      ["pipe", "The Tobacconist", "Pipe Lounge", "A shop counter of brass labels and briar wood, with the apps on drawer fronts."],
      ["pipe2", "Ember Lounge", "Pipe Lounge", "Low light and ember glow: a lounge chair view of your apps and panels."],
      ["pipe3", "Blend Journal", "Pipe Lounge", "Kraft paper and fountain-pen ink; tasting notes sit beside the home tiles."],
      ["pipe", "Ember & Briar", "Painted scene", "A painted lounge where the furniture is the launcher: tap the pipe rack, the lamp, the ledger."],
      ["pipe2", "Tobacconist's Ledger", "Painted scene", "Leather, kraft and ruled columns. The register, catalogue and session clock live here."],
      ["pipe3", "Midnight Ember", "Painted scene", "Black lacquer and gilt for night use, with live overlays on the painting."],
    ],
  },
];

const FEATURES = [
  ["Live panels", "Chronometer with stardate, power systems, weather from Open-Meteo, your schedule, media controls and ship systems (CPU, memory, storage, network)."],
  ["Communications", "A console dialer with keypad, recents and contacts. Emergency numbers always go through the phone's own app."],
  ["Shade and quick controls", "Swipe down for a console shade with torch, Wi-Fi, Bluetooth and volume keys."],
  ["Start-up sequences", "Each interface powers up its own way: the computer-core sweep, the Imperial triangle assembly, the Collective network linking up."],
  ["Bridge stations", "Six toy consoles (OPS, CONN, TACTICAL, ENGINEERING, SCIENCE, MEDICAL) with animated displays, clearly marked SIMULATION."],
  ["Every screen size", "Portrait and landscape, phone to tablet, folded or unfolded, multi-window. Layouts scale from one responsive grid."],
  ["Work profile aware", "Apps come from Android's LauncherApps API, so work-profile apps appear and installs show up at once."],
  ["Sounds you choose", "Assign a sound to any key or event, import your own clips, or keep it silent. The Play build ships no recordings from the series."],
  ["Private by design", "No accounts, no analytics, no ads. Weather is the only network call, and only when you turn it on."],
];

function galleryHtml(runs) {
  const withShots = runs.filter((r) => r.shots.length);
  if (!withShots.length) {
    return `<p class="empty">No screenshots yet. Every release runs the launcher on emulators from Android 8 to the latest
    (phones, a tablet and a foldable, portrait and landscape) and publishes the captures here.</p>`;
  }
  const fams = [...new Set(withShots.map((r) => r.family))];
  const apis = [...new Set(withShots.map((r) => r.api).filter(Boolean))].sort((a, b) => a - b);
  const chip = (key, value, label, pressed) =>
    `<button type="button" class="chip" data-filter="${key}" data-value="${esc(value)}" aria-pressed="${pressed}">${esc(label)}</button>`;
  const filters = `<div class="filters" role="group" aria-label="Filter screenshots">
  <fieldset><legend>Device</legend>${chip("device", "all", "All", true)}${fams.map((f) => chip("device", f, FAMILY_LABEL[f] || f, false)).join("")}</fieldset>
  <fieldset><legend>Orientation</legend>${chip("orient", "all", "All", true)}${chip("orient", "portrait", "Portrait", false)}${chip("orient", "landscape", "Landscape", false)}</fieldset>
  ${apis.length > 1 ? `<fieldset><legend>Android</legend>${chip("api", "all", "All", true)}${apis.map((a) => chip("api", String(a), `API ${a}`, false)).join("")}</fieldset>` : ""}
</div>`;
  const groups = withShots.map((r) => {
    const land = r.shots.every((s) => s.orientation === "landscape");
    const items = r.shots.map((s) => {
      const ar = s.size ? `--ar: ${s.size.w} / ${s.size.h}` : "";
      const caption = `${s.title} — ${runLabel(r)}`;
      return `<li data-orient="${s.orientation}"><a href="${s.url}" data-full="${s.url}" data-caption="${esc(caption)}">` +
        `<img src="${s.thumb}" alt="${esc(caption)}" loading="lazy" decoding="async" style="${ar}"` +
        `${s.size ? ` width="${s.size.w}" height="${s.size.h}"` : ""}><span class="cap">${esc(s.title)}</span></a></li>`;
    }).join("\n");
    return `<div class="group" data-device="${esc(r.device)}" data-family="${r.family}" data-api="${r.api ?? ""}">
  <div class="group-h"><h3>${esc(runLabel(r))}</h3>${statusBadge(r)}${r.profile ? `<span class="muted">${esc(r.profile)}</span>` : ""}</div>
  <ul class="shots${land ? " land" : ""}">
${items}
  </ul>
</div>`;
  }).join("\n");
  return `${filters}
${groups}
<p id="gallery-none" class="empty" hidden>No screenshots match these filters.</p>
<dialog class="lightbox" id="lightbox" aria-label="Screenshot viewer">
  <div class="lb-inner">
    <div class="lb-top"><p class="lb-cap"></p><button type="button" class="pill lav" data-lb="close">Close <span class="visually-hidden">viewer</span></button></div>
    <div class="lb-stage"><img alt=""></div>
    <div class="lb-nav"><button type="button" class="pill blue" data-lb="prev">‹ Prev</button><span class="lb-count muted" aria-live="polite"></span><button type="button" class="pill blue" data-lb="next">Next ›</button></div>
  </div>
</dialog>`;
}

function statusBadge(r) {
  if (!r.tests && !r.status) return "";
  const t = r.tests || {};
  const failed = (t.failed || 0) > 0 || r.status === "failed";
  const label = t.total ? `${failed ? "FAIL" : "PASS"} ${t.passed ?? t.total - (t.failed || 0) - (t.skipped || 0)}/${t.total}` : failed ? "FAIL" : "PASS";
  return `<span class="badge ${failed ? "fail" : "pass"}">${label}</span>`;
}

function videoHtml(runs) {
  const vids = runs.flatMap((r) => r.videos.map((v) => ({ ...v, run: r })));
  if (!vids.length) return `<p class="empty">Screen recordings from the emulator runs appear here after the next release.</p>`;
  return `<ul class="videos">
${vids.map((v) => `<li><figure>
  <video controls preload="none" playsinline ${v.poster ? `poster="${v.poster}"` : ""} aria-label="${esc(`${v.title} on ${runLabel(v.run)}`)}">
    <source src="${v.url}" type="video/mp4">
    <a href="${v.url}">Download the video</a>
  </video>
  <figcaption>${esc(v.title)} · ${esc(runLabel(v.run))}</figcaption>
</figure></li>`).join("\n")}
</ul>`;
}

function diagnosticsHtml(runs) {
  const tested = runs.filter((r) => r.tests || r.status);
  if (!tested.length) {
    return `<p class="empty">Test results appear here after the next CI run: every device in the matrix runs the
    instrumented test suite and reports passed, failed and skipped tests.</p>`;
  }
  const n = (v) => (v === undefined || v === null ? "—" : String(v));
  const rows = tested.map((r) => {
    const t = r.tests || {};
    const failed = (t.failed || 0) > 0 || r.status === "failed";
    return `<tr><th scope="row">${esc(runLabel(r))}</th><td>${esc(r.profile || "")}</td>
      <td class="num">${n(t.total)}</td><td class="num ok">${n(t.passed)}</td><td class="num ${t.failed ? "bad" : ""}">${n(t.failed)}</td><td class="num skip">${n(t.skipped)}</td>
      <td class="${failed ? "bad" : "ok"}">${failed ? "✗ Failed" : "✓ Passed"}</td></tr>`;
  }).join("\n");
  const summary = `<div class="table-wrap"><table class="matrix">
  <caption>Instrumented tests per device${tested[0].generated ? ` · ${esc(String(tested[0].generated).slice(0, 10))}` : ""}</caption>
  <thead><tr><th scope="col">Device</th><th scope="col">Profile</th><th scope="col">Tests</th><th scope="col">Passed</th><th scope="col">Failed</th><th scope="col">Skipped</th><th scope="col">Result</th></tr></thead>
  <tbody>
${rows}
  </tbody></table></div>`;

  // per-test matrix: rows = test cases, columns = device runs
  const withCases = tested.filter((r) => r.cases.length);
  if (!withCases.length) return summary;
  const keys = [...new Set(withCases.flatMap((r) => r.cases.map((c) => `${c.class}#${c.name}`)))].sort();
  const symbol = { passed: ['ok', "✓", "passed"], failed: ['bad', "✗", "failed"], skipped: ['skip', "–", "skipped"] };
  const head = withCases.map((r) => `<th scope="col">${esc(r.id)}</th>`).join("");
  const body = keys.map((k) => {
    const [cls, name] = k.split("#");
    const cells = withCases.map((r) => {
      const c = r.cases.find((x) => `${x.class}#${x.name}` === k);
      if (!c) return `<td class="skip">·<span class="visually-hidden">not run</span></td>`;
      const [klass, sym, word] = symbol[c.status] || symbol.skipped;
      return `<td class="${klass}" title="${esc(c.message || word)}">${sym}<span class="visually-hidden">${word}</span></td>`;
    }).join("");
    return `<tr><th scope="row" title="${esc(cls)}">${esc(cls.split(".").pop())} · ${esc(name)}</th>${cells}</tr>`;
  }).join("\n");
  return `${summary}
<details class="cases"><summary>Every test on every device (${keys.length} tests)</summary>
<div class="table-wrap"><table class="matrix"><thead><tr><th scope="col">Test</th>${head}</tr></thead><tbody>
${body}
</tbody></table></div></details>`;
}

function indexPage(ctx) {
  const { runs, version } = ctx;
  const heroShots = runs.filter((r) => r.family === "phone").flatMap((r) => r.shots).filter((s) => s.orientation === "portrait");
  const pick = [...heroShots.filter((s) => /home/i.test(s.title)), ...heroShots].slice(0, 2);
  const art = pick.length
    ? pick.map((s) => `<img src="${s.thumb}" alt="${esc(s.title)} screenshot" width="${s.size?.w || ""}" height="${s.size?.h || ""}">`).join("")
    : iconSvg("hero-icon", "TrekUI Launcher: an orange elbow frame with lavender, pink and blue data readouts");
  const families = FAMILIES.map((f) => `<div class="family">
  <h3>${esc(f.name)}</h3>
  <p class="muted">${esc(f.blurb)}</p>
  <ul class="cards">
${f.items.map(([cls, name, tag, text]) => `    <li class="card ${cls}"><span class="tag">${esc(tag)}</span><h4>${esc(name)}</h4><p>${esc(text)}</p></li>`).join("\n")}
  </ul>
</div>`).join("\n");
  const total = runs.reduce((a, r) => a + r.shots.length, 0);
  const apis = [...new Set(runs.filter((r) => r.shots.length).map((r) => r.api).filter(Boolean))].sort((a, b) => a - b);

  const body = `
<section class="hero" id="overview" aria-labelledby="hero-h">
  <div>
    <h2 id="hero-h">Your phone, <em>re-skinned</em> as a starship console</h2>
    <p>TrekUI Launcher replaces the whole home screen: tiles, dock, app drawer, panels and dialer, drawn live in eleven
    interfaces from a starship command console to a briar-and-brass pipe lounge.</p>
    <ul class="chips" aria-label="At a glance">
      <li>9 interfaces</li><li>Android 8.0+</li><li>Phone · tablet · foldable</li><li>No ads · no tracking</li>
    </ul>
    <div class="pills">
      <a class="pill" href="#download">Download</a>
      <a class="pill lav" href="#gallery">Gallery${total ? ` <small>${total}</small>` : ""}</a>
      <a class="pill blue" href="#interfaces">Interfaces</a>
    </div>
  </div>
  <div class="hero-art">${art}</div>
</section>

${section("interfaces", "Interfaces", "orange", "01-09", `<p class="lede">Pick an interface family in CONFIG › INTERFACE. Only geometry, lettering and colour change, so each one stays recognisable even in greyscale.</p>
<div class="families">
${families}
</div>`)}

${section("features", "Systems", "blue", "47-1138", `<ul class="features">
${FEATURES.map(([h, t]) => `  <li><strong>${esc(h)}</strong>${esc(t)}</li>`).join("\n")}
</ul>`)}

${section("gallery", "Gallery", "lavender", apis.length ? `API ${apis[0]}–${apis[apis.length - 1]}` : "", `<p class="lede">Captured automatically by CI on Android emulators for every release, grouped by device, Android version and orientation. Select a screenshot to enlarge it; use the arrow keys or swipe to move through them.</p>
${galleryHtml(runs)}`)}

${section("video", "Video", "pink", "", `<p class="lede">Screen recordings of the instrumented test runs.</p>
${videoHtml(runs)}`)}

${section("diagnostics", "Diagnostics", "green", "", `<p class="lede">Results of the instrumented test suite on each emulator in the release matrix.</p>
${diagnosticsHtml(runs)}`)}

${section("download", "Download", "gold", `v${version.versionName || ""}`, `<p class="lede">Get the store build from Google Play, or sideload the signed APK from GitHub Releases on any Android 8.0+ device. After installing, press Home and choose TrekUI as the default home app.</p>
<div class="pills">
  <a class="pill gold" href="${PLAY_URL}">Get it on Google Play</a>
  <a class="pill lav" href="${RELEASES_URL}">APK · GitHub Releases</a>
  <a class="pill blue" href="${REPO_URL}">Source code</a>
  <a class="pill pink" href="changelog.html">What's new</a>
</div>`)}
`;
  return page({ file: "index.html", title: "TrekUI Launcher", description: "An Android home-screen launcher in Command, Imperial, Collective, console-era and Pipe Lounge interfaces.", body, version });
}

function privacyPage(ctx) {
  const md = readText(path.join(ROOT, "PRIVACY.md"));
  const inner = md
    ? markdown(md)
    : `<h1>Privacy policy</h1><p>TrekUI Launcher collects no personal data, has no accounts, analytics or advertising,
       and sends nothing to its developer. The full policy will be published here with the first store release.</p>`;
  const body = `<article class="prose">\n${inner}\n</article>`;
  return page({ file: "privacy.html", title: "Privacy policy · TrekUI Launcher", description: "Privacy policy for the TrekUI Launcher Android app.", body, version: ctx.version });
}

function changelogPage(ctx) {
  const dir = path.join(ROOT, "fastlane/metadata/launcher/android/en-US/changelogs");
  const history = readJson(path.join(ROOT, "fastlane/release-history.json")) || [];
  const byCode = new Map(history.map((h) => [String(h.versionCode), h]));
  const codes = fs.existsSync(dir) ? fs.readdirSync(dir).filter((f) => /^\d+\.txt$/.test(f)).map((f) => f.replace(".txt", "")) : [];
  for (const h of history) if (!codes.includes(String(h.versionCode))) codes.push(String(h.versionCode));
  codes.sort((a, b) => Number(b) - Number(a));
  const entries = codes.map((code) => {
    const h = byCode.get(code) || {};
    const text = readText(path.join(dir, `${code}.txt`)) || h.notes || "";
    return `<section class="release" aria-labelledby="v${code}">
  <h2 id="v${code}">${esc(h.versionName ? `Version ${h.versionName}` : `Build ${code}`)}</h2>
  <span class="when">${esc([h.date, `build ${code}`].filter(Boolean).join(" · "))}</span>
  <pre>${esc(text.trim())}</pre>
</section>`;
  }).join("\n");
  // CHANGELOG.md (the full notes) when present; the short Play notes per build otherwise.
  const full = readText(path.join(ROOT, "CHANGELOG.md"));
  const main = full ? markdown(full) : `<h1>Changelog</h1>
${entries || "<p>No releases yet.</p>"}`;
  const playNotes = full && entries ? `<details class="cases"><summary>Google Play release notes per build</summary>
${entries}
</details>` : "";
  const body = `<div class="prose">
${main}
${playNotes}
<h2>Earlier versions</h2>
<p>Earlier private builds are summarised in the repository's <a href="${REPO_URL}/blob/main/CHANGELOG.md">CHANGELOG.md</a>.</p>
</div>`;
  return page({ file: "changelog.html", title: "Changelog · TrekUI Launcher", description: "Release notes for TrekUI Launcher.", body, version: ctx.version });
}

function notFoundPage(ctx) {
  const body = section("lost", "Error 404", "red", "", `<p class="lede">That page is not on file.</p><div class="pills"><a class="pill" href="index.html">Return to main menu</a></div>`);
  return page({ file: "404.html", title: "Not found · TrekUI Launcher", description: "Page not found.", body, version: ctx.version });
}

// ------------------------------------------------------------------------------------------- build
function build() {
  const t0 = Date.now();
  rmrf(OUT);
  mkdirp(OUT);
  const version = properties(readText(path.join(ROOT, "version.properties")));
  const { runs, rawIndex } = collectRuns();
  publishMedia(runs);
  const ctx = { runs, version };

  fs.writeFileSync(path.join(OUT, "index.html"), indexPage(ctx));
  fs.writeFileSync(path.join(OUT, "privacy.html"), privacyPage(ctx));
  fs.writeFileSync(path.join(OUT, "changelog.html"), changelogPage(ctx));
  fs.writeFileSync(path.join(OUT, "404.html"), notFoundPage(ctx));
  fs.writeFileSync(path.join(OUT, ".nojekyll"), "");
  copy(path.join(SITE, "src/style.css"), path.join(OUT, "style.css"));
  copy(path.join(SITE, "src/app.js"), path.join(OUT, "app.js"));
  const fonts = path.join(ROOT, "app/src/main/assets/fonts");
  for (const f of ["Antonio.ttf", "OFL-Antonio.txt"]) if (fs.existsSync(path.join(fonts, f))) copy(path.join(fonts, f), path.join(OUT, "fonts", f));

  // Machine-readable index of everything published (merged CI meta + capture index).
  const data = {
    generated: new Date().toISOString(),
    version: version.versionName || null,
    versionCode: Number(version.versionCode) || null,
    release: process.env.SITE_RELEASE_TAG || null,
    runs: runs.map((r) => ({
      id: r.id, device: r.device, family: r.family, api: r.api, orientation: r.orientation, profile: r.profile,
      status: r.status, tests: r.tests, cases: r.cases,
      screenshots: r.shots.map((s) => ({ url: s.url, thumb: s.thumb, title: s.title, orientation: s.orientation, width: s.size?.w, height: s.size?.h })),
      videos: r.videos.map((v) => ({ url: v.url, poster: v.poster, title: v.title })),
    })),
    captureIndex: rawIndex || null,
  };
  mkdirp(path.join(OUT, "data"));
  fs.writeFileSync(path.join(OUT, "data/index.json"), JSON.stringify(data, null, 2));

  const shots = runs.reduce((a, r) => a + r.shots.length, 0);
  const vids = runs.reduce((a, r) => a + r.videos.length, 0);
  console.log(`site/_out: ${runs.length} runs, ${shots} screenshots, ${vids} videos` +
    ` (thumbnails: ${tools.ffmpeg ? "ffmpeg" : tools.magick ? "ImageMagick" : "none, full-size images"}) in ${Date.now() - t0} ms`);
}

function serve(port = 8080) {
  const types = { ".html": "text/html; charset=utf-8", ".css": "text/css", ".js": "text/javascript", ".json": "application/json",
    ".png": "image/png", ".jpg": "image/jpeg", ".webp": "image/webp", ".mp4": "video/mp4", ".ttf": "font/ttf", ".txt": "text/plain" };
  http.createServer((req, res) => {
    let p = decodeURIComponent(new URL(req.url, "http://x").pathname);
    if (p.endsWith("/")) p += "index.html";
    const file = path.join(OUT, path.normalize(p).replace(/^([/\\])+/, ""));
    if (!file.startsWith(OUT) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      res.writeHead(404, { "content-type": types[".html"] });
      return fs.createReadStream(path.join(OUT, "404.html")).pipe(res);
    }
    res.writeHead(200, { "content-type": types[path.extname(file).toLowerCase()] || "application/octet-stream" });
    fs.createReadStream(file).pipe(res);
  }).listen(port, "127.0.0.1", () => console.log(`Serving site/_out on http://localhost:${port}/ (Ctrl+C to stop)`));
}

build();
if (process.argv.includes("--serve")) serve(Number(process.env.PORT) || 8080);
