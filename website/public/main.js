// iTantra reviewer site: copy buttons, OS tabs for the model installer, image lightbox,
// and highlighting the current section in the header. No dependencies.
(() => {
  "use strict";

  // Phone/tablet menu: toggles the header nav; closes on link tap, Escape or tap outside.
  const bar = document.querySelector(".topbar");
  const menuBtn = document.querySelector(".menu-btn");
  if (bar && menuBtn) {
    const setOpen = (open) => {
      bar.classList.toggle("open", open);
      menuBtn.setAttribute("aria-expanded", String(open));
      menuBtn.setAttribute("aria-label", open ? "Close menu" : "Open menu");
    };
    menuBtn.addEventListener("click", () => setOpen(!bar.classList.contains("open")));
    bar.querySelectorAll("nav a").forEach((a) => a.addEventListener("click", () => setOpen(false)));
    document.addEventListener("keydown", (e) => { if (e.key === "Escape") setOpen(false); });
    document.addEventListener("click", (e) => { if (!bar.contains(e.target)) setOpen(false); });
    window.matchMedia("(min-width: 861px)").addEventListener("change", (e) => { if (e.matches) setOpen(false); });
  }

  // Copy buttons: data-copy="#id" copies that element's text.
  document.querySelectorAll("[data-copy]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const target = document.querySelector(btn.dataset.copy);
      if (!target) return;
      const text = target.textContent.trim();
      const label = btn.textContent;
      try {
        await navigator.clipboard.writeText(text);
        btn.textContent = "Copied";
        btn.classList.add("done");
      } catch {
        const range = document.createRange();
        range.selectNodeContents(target);
        const sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
        btn.textContent = "Selected, press Ctrl+C";
      }
      setTimeout(() => { btn.textContent = label; btn.classList.remove("done"); }, 2000);
    });
  });

  // Tabs for the model installer (macOS/Linux, Windows, Manual).
  const tabs = Array.from(document.querySelectorAll('.tabs [role="tab"]'));
  const select = (tab) => {
    tabs.forEach((t) => {
      const on = t === tab;
      t.setAttribute("aria-selected", String(on));
      t.tabIndex = on ? 0 : -1;
      document.getElementById(t.getAttribute("aria-controls")).hidden = !on;
    });
  };
  tabs.forEach((tab, i) => {
    tab.addEventListener("click", () => select(tab));
    tab.addEventListener("keydown", (e) => {
      const step = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
      if (!step) return;
      const next = tabs[(i + step + tabs.length) % tabs.length];
      select(next);
      next.focus();
    });
  });
  if (/Windows/i.test(navigator.userAgent)) {
    const win = document.getElementById("tab-win");
    if (win) select(win);
  }

  // Lightbox for screenshots and diagrams.
  const box = document.getElementById("lightbox");
  if (box && typeof box.showModal === "function") {
    const img = box.querySelector("img");
    const cap = box.querySelector(".lb-cap");
    document.querySelectorAll("a[data-lightbox]").forEach((link) => {
      link.addEventListener("click", (e) => {
        e.preventDefault();
        const thumb = link.querySelector("img");
        img.src = link.href;
        img.alt = thumb ? thumb.alt : "";
        cap.textContent = (link.querySelector("span") || {}).textContent || "";
        box.showModal();
      });
    });
    box.querySelector(".lb-close").addEventListener("click", () => box.close());
    box.addEventListener("click", (e) => { if (e.target === box) box.close(); });
    box.addEventListener("close", () => { img.removeAttribute("src"); });
  }

  // Demo film at the top: big play button over the poster; hides while playing, returns at the end.
  const film = document.getElementById("filmVideo");
  const filmPlay = document.querySelector(".film-play");
  if (film && filmPlay) {
    // Native controls stay off until the film starts, so they never sit under the play button
    // (without JS the markup keeps `controls`, so the video still works).
    film.controls = false;
    filmPlay.addEventListener("click", () => { film.controls = true; film.play().catch(() => {}); });
    film.addEventListener("play", () => { filmPlay.hidden = true; film.controls = true; });
    film.addEventListener("ended", () => { filmPlay.hidden = false; film.controls = false; });
    // Header "Demo" link and #film deep links scroll to the film; they don't auto-play (browsers block sound).
  }

  // Only one video plays at a time.
  const videos = Array.from(document.querySelectorAll("video"));
  videos.forEach((v) => v.addEventListener("play", () => {
    videos.forEach((o) => { if (o !== v) o.pause(); });
  }));

  // Mark the header link for the section in view.
  const links = new Map(Array.from(document.querySelectorAll('.topbar nav a[href^="#"]'))
    .map((a) => [a.getAttribute("href").slice(1), a]));
  if ("IntersectionObserver" in window && links.size) {
    const io = new IntersectionObserver((entries) => {
      entries.forEach((en) => {
        const a = links.get(en.target.id);
        if (a && en.isIntersecting) {
          links.forEach((l) => l.removeAttribute("aria-current"));
          a.setAttribute("aria-current", "true");
        }
      });
    }, { rootMargin: "-40% 0px -55% 0px" });
    links.forEach((_, id) => { const s = document.getElementById(id); if (s) io.observe(s); });
  }
})();
