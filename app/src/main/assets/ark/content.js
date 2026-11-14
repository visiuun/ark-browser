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

  const extract = () => {
    const root = document.querySelector("article") || document.querySelector("main") || document.body;
    const clone = root.cloneNode(true);
    clone.querySelectorAll("script,style,noscript,nav,footer,header,aside,form,svg,iframe,button").forEach(n => n.remove());
    const parts = [];
    clone.querySelectorAll("h1,h2,h3,p,li,blockquote,pre,td").forEach(n => {
      const t = (n.innerText || n.textContent || "").trim();
      if (t) parts.push(t);
    });
    const text = parts.length ? parts.join("\n") : (root.innerText || "");
    const headings = [...document.querySelectorAll("h1,h2,h3")].map(h => h.innerText.trim()).filter(Boolean).slice(0, 12);
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
