/* ============================================================
   androidskills.dev — landing page behavior
   A focused subset of the design-system controller: theme (system
   / light / dark) persistence + header toggle, mobile nav drawer,
   and a tiny toast helper. Drops the design-tool host protocol,
   Tweaks panel, starred store, tabs and file-viewer that the full
   mock needs but a production landing page does not.
   Same localStorage key + attribute scheme as the design system, so
   the future Astro port stays compatible.
   ============================================================ */
(function () {
  "use strict";
  var KEY = "androidskills";
  var root = document.documentElement;

  function read() { try { return JSON.parse(localStorage.getItem(KEY) || "{}"); } catch (e) { return {}; } }
  function write(s) { try { localStorage.setItem(KEY, JSON.stringify(s)); } catch (e) {} }
  var state = Object.assign({ theme: "system", accent: "green", density: "regular" }, read());

  function resolvedDark() {
    if (state.theme === "dark") return true;
    if (state.theme === "light") return false;
    return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
  }

  function apply() {
    if (state.theme && state.theme !== "system") root.setAttribute("data-theme", state.theme);
    else root.removeAttribute("data-theme");
    if (state.accent && state.accent !== "green") root.setAttribute("data-accent", state.accent);
    else root.removeAttribute("data-accent");
    if (state.density && state.density !== "regular") root.setAttribute("data-density", state.density);
    else root.removeAttribute("data-density");
    syncHeaderToggle();
    syncMetaTheme();
  }

  function set(patch) {
    Object.assign(state, patch);
    write(state);
    apply();
  }

  var ICON_SUN = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="4.5"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4"/></svg>';
  var ICON_MOON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>';

  function syncHeaderToggle() {
    document.querySelectorAll("[data-theme-toggle]").forEach(function (b) {
      b.innerHTML = resolvedDark() ? ICON_SUN : ICON_MOON;
      b.setAttribute("aria-label", resolvedDark() ? "Switch to light theme" : "Switch to dark theme");
    });
  }

  /* Keep the <meta name="theme-color"> in sync so the browser chrome
     (mobile address bar etc.) matches the active theme. */
  function syncMetaTheme() {
    var m = document.querySelector('meta[name="theme-color"]');
    if (!m || !window.CSS || !CSS.supports) return;
    var dark = resolvedDark();
    // resolve the --bg token for the current scheme
    var bg = getComputedStyle(root).getPropertyValue("--bg").trim();
    if (bg) {
      m.setAttribute("content", bg);
    } else {
      m.setAttribute("content", dark ? "#0e1014" : "#fbfbfc");
    }
  }

  /* ---------- Mobile drawer ---------- */
  function openDrawer(d) { d.style.display = "block"; document.body.style.overflow = "hidden"; }
  function closeDrawer(d) { d.style.display = "none"; document.body.style.overflow = ""; }

  /* ---------- Toast ---------- */
  var toastT;
  function toast(msg) {
    var ex = document.querySelector(".toast"); if (ex) ex.remove();
    var t = document.createElement("div"); t.className = "toast";
    t.setAttribute("role", "status");
    t.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg><span></span>';
    t.querySelector("span").textContent = msg;
    document.body.appendChild(t);
    clearTimeout(toastT); toastT = setTimeout(function () { t.remove(); }, 2600);
  }

  window.sxToast = toast;

  /* ---------- Wire-up ---------- */
  function ready(fn) { if (document.readyState !== "loading") fn(); else document.addEventListener("DOMContentLoaded", fn); }
  ready(function () {
    apply();

    document.querySelectorAll("[data-theme-toggle]").forEach(function (b) {
      b.addEventListener("click", function () { set({ theme: resolvedDark() ? "light" : "dark" }); });
    });
    if (window.matchMedia) {
      var mq = window.matchMedia("(prefers-color-scheme: dark)");
      var onChange = function () { if (state.theme === "system") { syncHeaderToggle(); syncMetaTheme(); } };
      if (mq.addEventListener) mq.addEventListener("change", onChange);
      else if (mq.addListener) mq.addListener(onChange);
    }

    var drawer = document.querySelector(".drawer");
    document.querySelectorAll("[data-menu-open]").forEach(function (b) {
      b.addEventListener("click", function () { if (drawer) openDrawer(drawer); });
    });
    document.querySelectorAll("[data-menu-close]").forEach(function (b) {
      b.addEventListener("click", function () { if (drawer) closeDrawer(drawer); });
    });
    if (drawer) {
      drawer.addEventListener("click", function (e) { if (e.target === drawer) closeDrawer(drawer); });
    }
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape" && drawer && drawer.style.display === "block") closeDrawer(drawer);
    });

    /* Waitlist = a Tally.so inline <iframe> embedded directly in the
       notify CTA (see index.html). Tally's loader script self-loads
       embed.js and handles dynamic height + transparent background, so
       there's nothing to wire up here. Responses live in Tally. */

    /* Copy buttons (e.g. the preview install command). */
    document.querySelectorAll("[data-copy]").forEach(function (b) {
      b.addEventListener("click", function () {
        var val = b.getAttribute("data-copy");
        if (!val) return;
        try {
          navigator.clipboard.writeText(val).then(function () { toast("Copied \u201c" + val + "\u201d"); });
        } catch (e) { toast("Copied \u201c" + val + "\u201d"); }
      });
    });
  });
})();
