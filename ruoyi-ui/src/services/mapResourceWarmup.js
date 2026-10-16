// Fetch only small, same-origin rendering dependencies while map configuration is loading.
// These are cache warmups, never substitutes for the renderer's normal validation/errors.
export function warmMapResource(value, signal, base = window.location.href) {
  if (signal?.aborted || typeof value !== 'string') return;
  try {
    const url = new URL(value, base);
    if (url.origin !== window.location.origin || !['http:', 'https:'].includes(url.protocol)
        || url.username || url.password) return;
    void fetch(url.href, { signal, cache: 'default', redirect: 'error', credentials: 'same-origin' })
      .then(response => response.ok ? response.arrayBuffer() : response.body?.cancel())
      .catch(() => {});
  } catch { /* A speculative load must never prevent the actual map from reporting its error. */ }
}

export function warmMapStyleAssets(style, signal) {
  const urls = new Set();
  if (typeof style.sprite === 'string') {
    const suffix = window.devicePixelRatio > 1 ? '@2x' : '';
    for (const extension of ['.json', '.png']) {
      const url = new URL(style.sprite, window.location.href);
      url.pathname += suffix + extension;
      urls.add(url.href);
    }
  }
  if (typeof style.glyphs === 'string') {
    for (const layer of style.layers || []) {
      const fonts = layer.layout?.['text-font'];
      // Dynamic font expressions remain entirely owned by MapLibre.
      if (!Array.isArray(fonts) || !fonts.length || !fonts.every(font => typeof font === 'string')
          || ['get', 'literal', 'coalesce', 'match', 'case', 'step', 'let', 'var'].includes(fonts[0])) continue;
      urls.add(style.glyphs.replace('{fontstack}', encodeURIComponent(fonts.join(','))).replace('{range}', '0-255'));
      if (urls.size >= 10) break;
    }
  }
  urls.forEach(url => warmMapResource(url, signal));
}
