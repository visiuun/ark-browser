// Ark helper background page: answers the app's questions that need extension APIs.
// Right now that's "which sites have cookies?", used by fire mode to clear everything but fireproofed sites.
(() => {
  let tries = 0;
  const connect = () => {
    let port;
    try { port = browser.runtime.connectNative("arkbg"); } catch (e) { retry(); return; }
    port.onMessage.addListener(async (m) => {
      if (!m || m.type !== "cookieSites") return;
      const sites = new Set();
      const add = (d) => { if (d) sites.add(String(d).replace(/^\./, "").replace(/^https?:\/\//, "").replace(/[:/].*$/, "")); };
      try {
        const all = await browser.cookies.getAll({ firstPartyDomain: null, partitionKey: {} });
        for (const c of all) {
          add(c.domain);
          if (c.firstPartyDomain) add(c.firstPartyDomain);
          if (c.partitionKey && c.partitionKey.topLevelSite) add(c.partitionKey.topLevelSite);
        }
      } catch (e) {}
      port.postMessage({ type: "cookieSites", sites: [...sites] });
    });
    port.onDisconnect.addListener(() => retry());
  };
  // The app registers its side of the port a moment after the extension starts, so keep trying.
  const retry = () => { if (++tries < 60) setTimeout(connect, 1000); };
  connect();
})();
