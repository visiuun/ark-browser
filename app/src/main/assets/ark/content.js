(() => {
  if (window.top !== window) return;
  let port;
  try { port = browser.runtime.connectNative("ark"); } catch (e) { return; }

  const norm = (c) => {
    if (!c) return "";
    const d = document.createElement("div");
    d.style.color = c;
    d.style.display = "none";
    (document.body || document.documentElement).appendChild(d);
    const v = getComputedStyle(d).color;
    d.remove();
    return v;
  };

  const meta = () => {
    let theme = "";
    const m = document.querySelector('meta[name="theme-color"]:not([media])') ||
      [...document.querySelectorAll('meta[name="theme-color"][media]')].find(x => matchMedia(x.media).matches);
    if (m && m.content) theme = norm(m.content);
    if (!theme && document.body) {
      const bg = getComputedStyle(document.body).backgroundColor;
      if (bg && !bg.startsWith("rgba(0, 0, 0, 0)") && bg !== "transparent") theme = bg;
      else {
        const hb = getComputedStyle(document.documentElement).backgroundColor;
        if (hb && !hb.startsWith("rgba(0, 0, 0, 0)")) theme = hb;
      }
    }
    let icon = "";
    const links = [...document.querySelectorAll('link[rel~="icon"], link[rel="apple-touch-icon"], link[rel="apple-touch-icon-precomposed"]')];
    const best = links.sort((a, b) => {
      const s = (l) => { const z = (l.sizes && l.sizes.value || "").split("x")[0]; return parseInt(z) || (l.rel.includes("apple") ? 180 : 16); };
      return s(b) - s(a);
    })[0];
    if (best && best.href && !best.href.endsWith(".svg")) icon = best.href;
    port.postMessage({ type: "meta", theme, icon, url: location.href });
  };

  // Readability-lite: find the element holding the most real paragraph text, then read only that.
  const JUNK = "script,style,noscript,nav,footer,header,aside,form,svg,iframe,button,dialog,[role=navigation],[role=banner],[role=contentinfo],[role=complementary],[aria-hidden=true],.comments,#comments,.related,.share,.newsletter,.cookie,.advert,.ad";
  const linkDensity = (el) => {
    const len = (el.innerText || "").length || 1;
    let l = 0; el.querySelectorAll("a").forEach(a => l += (a.innerText || "").length);
    return l / len;
  };
  const pickRoot = () => {
    const explicit = document.querySelector("article [itemprop=articleBody], [itemprop=articleBody], article, main, [role=main]");
    const scores = new Map();
    document.querySelectorAll("p").forEach(p => {
      const t = (p.innerText || "").trim();
      if (t.length < 60 || linkDensity(p) > 0.5 || p.closest(JUNK)) return;
      const add = (el, w) => { if (el) scores.set(el, (scores.get(el) || 0) + t.length * w); };
      add(p.parentElement, 1); add(p.parentElement && p.parentElement.parentElement, 0.5);
    });
    let best = null, bestScore = 0;
    scores.forEach((v, el) => { if (v > bestScore) { best = el; bestScore = v; } });
    if (explicit && (!best || explicit.contains(best) || (explicit.innerText || "").length < 20000)) {
      const inside = best && explicit.contains(best);
      return inside || !best ? explicit : best;
    }
    return best || explicit || document.body;
  };

  const extract = () => {
    const root = pickRoot();
    const clone = root.cloneNode(true);
    clone.querySelectorAll(JUNK).forEach(n => n.remove());
    const parts = [];
    const h1 = document.querySelector("h1");
    if (h1 && !root.contains(h1)) parts.push(h1.innerText.trim());
    clone.querySelectorAll("h1,h2,h3,h4,p,li,blockquote,pre,figcaption,td").forEach(n => {
      if (n.tagName === "LI" && (n.querySelector("p") || linkDensity(n) > 0.6)) return;
      if (n.tagName === "TD" && (n.innerText || "").length < 30) return;
      const t = (n.innerText || n.textContent || "").replace(/\s+/g, " ").trim();
      if (t.length > 1) parts.push(t);
    });
    let text = parts.join("\n");
    if (text.length < 400) text = (root.innerText || document.body.innerText || "");
    const headings = [...root.querySelectorAll("h1,h2,h3")].map(h => h.innerText.trim()).filter(Boolean).slice(0, 12);
    port.postMessage({ type: "text", text: text.slice(0, 200000), headings, links: document.links.length, lang: document.documentElement.lang || "" });
  };

  port.onMessage.addListener((m) => {
    if (!m || !m.type) return;
    if (m.type === "zoom") {
      document.documentElement.style.zoom = (m.value && m.value !== 1) ? String(m.value) : "";
    } else if (m.type === "extract") {
      extract();
    } else if (m.type === "meta") {
      meta();
    }
  });

  meta();
  const mo = new MutationObserver(() => { clearTimeout(window.__arkMeta); window.__arkMeta = setTimeout(meta, 400); });
  const head = document.head;
  if (head) mo.observe(head, { subtree: true, childList: true, attributes: true, attributeFilter: ["content", "href"] });
  window.addEventListener("load", () => setTimeout(meta, 300));
  matchMedia("(prefers-color-scheme: dark)").addEventListener("change", meta);
})();
