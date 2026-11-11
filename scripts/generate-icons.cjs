#!/usr/bin/env node
// long: SVG 为唯一图形源；统一生成各种遮罩和密度，避免方形、圆形及自适应版本发生漂移。
const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require('sharp');

const root = path.resolve(__dirname, '..');
const branding = path.join(root, 'branding');
const resources = path.join(root, 'app/src/main/res');
const densities = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };
const sourceNote = 'long: Rendered from the PdfReader SVG artwork in branding/; PDF document and editing pencil.';

async function rasterize(svg, size, output) {
  await fs.mkdir(path.dirname(output), { recursive: true });
  // long: 先以高分辨率栅格化再缩小，让桌面上的小字和笔尖保持平滑清晰。
  const viewportWidth = Number(svg.match(/viewBox="([^"]+)"/)[1].split(/\s+/)[2]);
  await sharp(Buffer.from(svg), { density: Math.ceil(size * 2 * 72 / viewportWidth) })
    .resize(size, size, { kernel: 'lanczos3' })
    .withXmp(`<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:source>${sourceNote}</dc:source></rdf:Description></rdf:RDF></x:xmpmeta>`)
    .png({ compressionLevel: 9, palette: false })
    .toFile(output);
}

async function main() {
  const foreground = await fs.readFile(path.join(branding, 'pdfreader-icon-foreground.svg'), 'utf8');
  const monochrome = await fs.readFile(path.join(branding, 'pdfreader-icon-monochrome.svg'), 'utf8');
  const artwork = foreground.replace(/^[\s\S]*?<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '');
  const backdrop = `<defs><linearGradient id="backdrop" x1="18" y1="18" x2="85" y2="90" gradientUnits="userSpaceOnUse"><stop stop-color="#23634C"/><stop offset="1" stop-color="#103C2F"/></linearGradient></defs>`;
  const shapes = {
    '': '<rect x="18" y="18" width="72" height="72" rx="16" fill="url(#backdrop)"/>',
    '-round': '<circle cx="54" cy="54" r="36" fill="url(#backdrop)"/>',
  };
  for (const [suffix, shape] of Object.entries(shapes)) {
    // long: 导出的独立图标也裁切前景阴影，避免圆形外缘出现半透明杂边。
    const clip = shape.replace(' fill="url(#backdrop)"', '');
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72">\n${backdrop}\n<defs><clipPath id="icon-mask">${clip}</clipPath></defs>\n${shape}\n<g clip-path="url(#icon-mask)">${artwork}</g></svg>\n`;
    await fs.writeFile(path.join(branding, `pdfreader-icon${suffix}.svg`), svg);
    await rasterize(svg, 1024, path.join(branding, `pdfreader-icon${suffix}.png`));
    for (const [density, scale] of Object.entries(densities)) {
      await rasterize(svg, 48 * scale, path.join(resources, `mipmap-${density}`, `ic_launcher${suffix.replace('-', '_')}.png`));
    }
    if (!suffix) {
      await rasterize(svg, 512, path.join(root, 'fastlane/metadata/android/en-US/images/icon.png'));
    }
  }
  // long: 自适应图层保持 108dp 全画布透明边距，由系统负责裁切，不能再次套用圆形背景。
  for (const [density, scale] of Object.entries(densities)) {
    await rasterize(foreground, 108 * scale, path.join(resources, `mipmap-${density}`, 'ic_launcher_foreground.png'));
    await rasterize(monochrome, 108 * scale, path.join(resources, `mipmap-${density}`, 'ic_launcher_monochrome.png'));
  }
  console.log('已生成方形、圆形、自适应前景和主题图标，以及 README 图标。');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
