const bundledFonts = [
  { family: 'Noto Sans SC', file: 'NotoSansSC-Regular.woff2', weight: '400', text: '平台 Aa 0123' },
  { family: 'Noto Sans Mono', file: 'NotoSansMono-Variable.woff2', weight: '100 900', text: 'Aa 0123' }
];

// An existing tab can receive new JS before its HTML has the font stylesheet.
// An empty FontFaceSet.load result means "not registered", not "file missing".
export async function loadAppFonts(fonts = document.fonts, Font = globalThis.FontFace) {
  if (!fonts?.load) throw new Error('浏览器不支持本地字体加载');
  const base = import.meta.env?.BASE_URL || '/';
  await Promise.all(bundledFonts.map(async font => {
    try {
      const faces = await fonts.load(`400 14px "${font.family}"`, font.text);
      if (faces.length) return;
    } catch {
      // A failed CSS FontFace remains failed. A fresh face also permits retry
      // after a transient download error without reloading the entire page.
    }
    if (!Font || !fonts.add) throw new Error(`无法加载本地字体：${font.family}`);
    const face = new Font(font.family, `url("${base}fonts/${font.file}") format("woff2")`, {
      style: 'normal', weight: font.weight, display: 'block'
    });
    await face.load();
    fonts.add(face);
  }));
}

export function startWithAppFonts(start, host = document.getElementById('app')) {
  const doc = host.ownerDocument;
  // Keep the same tokens available when the tab still has an older HTML head.
  doc.documentElement.style.setProperty('--app-font-sans', '"Noto Sans SC"');
  doc.documentElement.style.setProperty('--app-font-mono', '"Noto Sans Mono", "Noto Sans SC"');
  const attempt = async () => {
    const message = doc.createElement('p');
    message.textContent = '正在加载页面资源…';
    host.setAttribute('role', 'status');
    host.replaceChildren(message);
    try {
      await loadAppFonts(doc.fonts, doc.defaultView.FontFace);
    } catch (error) {
      console.error('Application font initialization failed', error);
      message.textContent = '页面字体资源暂时无法加载，请重试。无需安装字体。';
      const retry = doc.createElement('button');
      retry.type = 'button';
      retry.textContent = '重新加载';
      retry.addEventListener('click', () => { void attempt(); }, { once: true });
      host.setAttribute('role', 'alert');
      host.replaceChildren(message, retry);
      return;
    }
    host.removeAttribute('role');
    host.replaceChildren();
    // Application errors must not be mislabeled as font-download failures.
    return start();
  };
  return attempt();
}
