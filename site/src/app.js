// TrekUI Launcher site: gallery filters and lightbox. Progressive enhancement only: without JS
// every thumbnail is a plain link to the full-size image.
(function () {
  "use strict";

  // ---------- filters ----------
  const groups = Array.from(document.querySelectorAll(".group[data-device]"));
  const state = { device: "all", orient: "all", api: "all" };

  function applyFilters() {
    let shown = 0;
    groups.forEach((g) => {
      const okDevice = state.device === "all" || g.dataset.family === state.device;
      const okApi = state.api === "all" || g.dataset.api === state.api;
      let visibleShots = 0;
      g.querySelectorAll("li[data-orient]").forEach((li) => {
        const ok = state.orient === "all" || li.dataset.orient === state.orient;
        li.hidden = !ok;
        if (ok) visibleShots++;
      });
      const show = okDevice && okApi && visibleShots > 0;
      g.hidden = !show;
      if (show) shown++;
    });
    const none = document.getElementById("gallery-none");
    if (none) none.hidden = shown > 0;
  }

  document.querySelectorAll(".filters [data-filter]").forEach((btn) => {
    btn.addEventListener("click", () => {
      const key = btn.dataset.filter;
      state[key] = btn.dataset.value;
      document.querySelectorAll(`.filters [data-filter="${key}"]`).forEach((b) =>
        b.setAttribute("aria-pressed", String(b === btn)));
      applyFilters();
    });
  });

  // ---------- lightbox ----------
  const dlg = document.getElementById("lightbox");
  if (!dlg || typeof dlg.showModal !== "function") return;
  const img = dlg.querySelector("img");
  const cap = dlg.querySelector(".lb-cap");
  const count = dlg.querySelector(".lb-count");
  let list = [];
  let index = 0;

  function visibleLinks() {
    return Array.from(document.querySelectorAll(".shots a[data-full]"))
      .filter((a) => !a.closest("[hidden]"));
  }

  function show(i) {
    index = (i + list.length) % list.length;
    const a = list[index];
    img.src = a.dataset.full;
    img.alt = a.querySelector("img").alt;
    cap.textContent = a.dataset.caption || img.alt;
    count.textContent = `${index + 1} / ${list.length}`;
  }

  document.addEventListener("click", (e) => {
    const a = e.target.closest(".shots a[data-full]");
    if (!a || e.ctrlKey || e.metaKey || e.shiftKey) return;
    e.preventDefault();
    list = visibleLinks();
    show(list.indexOf(a));
    dlg.showModal();
  });

  dlg.querySelector("[data-lb=prev]").addEventListener("click", () => show(index - 1));
  dlg.querySelector("[data-lb=next]").addEventListener("click", () => show(index + 1));
  dlg.querySelector("[data-lb=close]").addEventListener("click", () => dlg.close());
  dlg.addEventListener("keydown", (e) => {
    if (e.key === "ArrowLeft") { e.preventDefault(); show(index - 1); }
    if (e.key === "ArrowRight") { e.preventDefault(); show(index + 1); }
  });
  // click on the dark backdrop (outside the image) closes
  dlg.addEventListener("click", (e) => { if (e.target === dlg || e.target.classList.contains("lb-stage")) dlg.close(); });
  dlg.addEventListener("close", () => { img.removeAttribute("src"); });

  // swipe left/right on touch screens
  let x0 = null;
  dlg.addEventListener("touchstart", (e) => { x0 = e.touches[0].clientX; }, { passive: true });
  dlg.addEventListener("touchend", (e) => {
    if (x0 === null) return;
    const dx = e.changedTouches[0].clientX - x0;
    if (Math.abs(dx) > 50) show(index + (dx < 0 ? 1 : -1));
    x0 = null;
  });
})();
