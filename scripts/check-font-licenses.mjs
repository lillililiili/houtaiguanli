// Regression guard for the application's approved font assets and known OS stacks.
// This checks our sources; it does not infer licenses for uploaded/raster content.
import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { parseArgs } from 'node:util';

const { values } = parseArgs({ options: { 'business-dir': { type: 'string' } } });
if (!values['business-dir']) throw new Error('必须指定 --business-dir');
const root = fileURLToPath(new URL('..', import.meta.url));
const roots = [path.join(root, 'ruoyi-ui'), path.resolve(values['business-dir'])];
const sha = buffer => createHash('sha256').update(buffer).digest('hex');
const failures = [];
const banned = /\b(?:Arial|Helvetica|Calibri|Consolas|Menlo|Monaco|Bahnschrift|PingFang|Hiragino|Segoe|Tahoma|Verdana|SimSun|SimHei|Microsoft\s+YaHei|SFMono|Times New Roman|DIN Alternate|system-ui|ui-monospace|BlinkMacSystemFont)\b|-apple-system|微软雅黑|宋体|黑体/i;
const fontBinary = /\.(?:woff2?|ttf|otf|eot|ttc)$/i;

async function walk(dir) {
  const files = [];
  for (const item of await readdir(dir, { withFileTypes: true })) {
    const file = path.join(dir, item.name);
    if (item.isDirectory()) files.push(...await walk(file));
    else if (item.isFile()) files.push(file);
  }
  return files;
}

for (const app of roots) {
  const fontsDir = path.join(app, 'public/fonts');
  const manifest = JSON.parse(await readFile(path.join(fontsDir, 'manifest.json'), 'utf8'));
  if (manifest.license !== 'OFL-1.1') failures.push(`${app}: unapproved font license`);
  const approved = new Set();
  for (const font of manifest.fonts) {
    const file = path.join(fontsDir, font.file);
    approved.add(file);
    if (sha(await readFile(file)) !== font.sha256) failures.push(`${file}: font checksum changed`);
    const license = await readFile(path.join(fontsDir, font.licenseFile));
    if (sha(license) !== font.licenseSha256 || !license.toString().includes('SIL OPEN FONT LICENSE Version 1.1')) {
      failures.push(`${file}: missing/changed license`);
    }
  }
  for (const dir of ['src', 'public']) {
    for (const file of await walk(path.join(app, dir))) {
      if (fontBinary.test(file) && !approved.has(file)) failures.push(`${file}: font not in approved inventory`);
      // Vendor defaults are overridden at the component/chart integration points.
      if (file.includes(`${path.sep}vendor${path.sep}`) || !/\.(?:css|scss|vue|js|ts|html|svg)$/.test(file)) continue;
      const source = await readFile(file, 'utf8');
      for (const [index, line] of source.split(/\r?\n/).entries()) {
        if (banned.test(line)) failures.push(`${file}:${index + 1}: proprietary/system font reference`);
        if (/fonts\.(?:googleapis|gstatic)\.com|@import\s+url\(['"]?https?:/i.test(line)) {
          failures.push(`${file}:${index + 1}: remote font/style dependency`);
        }
      }
    }
  }
  const entry = await readFile(path.join(app, 'index.html'), 'utf8');
  if (!entry.includes('/fonts/fonts.css')) failures.push(`${app}: font stylesheet not loaded`);
  const main = await readFile(path.join(app, 'src/main.js'), 'utf8');
  if (!main.includes('startWithAppFonts(')) failures.push(`${app}: first rendering is not waiting for fonts`);
}

const pdfFont = path.join(root, 'server/src/main/resources/fonts/NotoSansSC-Regular.ttf');
if (sha(await readFile(pdfFont)) !== 'fb829f76ebb72904eb2aed150d58d0362c1674e0b0c8d629f1e2884590fcd737') {
  failures.push(`${pdfFont}: font changed; review its license before updating the inventory`);
}
if (failures.length) {
  console.error(failures.join('\n'));
  process.exitCode = 1;
} else {
  console.log('PASS: both applications use the approved local font assets; licenses/hashes and source checks passed.');
}
