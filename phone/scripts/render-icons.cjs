// Rendu des icônes d'Aura depuis les SVG versionnés (assets/icon/*.svg), charte du 22/10 (lot 20). Chromium headless.
// Usage : PLAYWRIGHT_CORE=<chemin de playwright-core> node scripts/render-icons.cjs [dossier d'aperçus des masques]
//   -> assets/icon.png, assets/adaptive-icon.png, assets/adaptive-icon-monochrome.png (1024 px)
//   avec un dossier d'aperçus : l'icône adaptative sous les masques rond, carré arrondi et goutte, en 48/72/96/192 px.
const { chromium } = require(process.env.PLAYWRIGHT_CORE || "playwright-core")
const fs = require("fs")
const path = require("path")
const PHONE = path.resolve(__dirname, "..")
const svg = (n) => fs.readFileSync(path.join(PHONE, "assets/icon", n), "utf8")
const BG = "#0e1017"

;(async () => {
  const browser = await chromium.launch({ headless: true })
  const ctx = await browser.newContext({ viewport: { width: 1024, height: 1024 }, deviceScaleFactor: 1 })
  const page = await ctx.newPage()
  const render = async (html, out, w = 1024, h = 1024) => {
    await page.setViewportSize({ width: w, height: h })
    await page.setContent(`<html><body style="margin:0;background:transparent">${html}</body></html>`)
    await page.screenshot({ path: out, omitBackground: true })
  }
  const box = (s, px) => s.replace("<svg ", `<svg width="${px}" height="${px}" style="display:block" `)
  await render(box(svg("aura-orb-icon.svg"), 1024), path.join(PHONE, "assets/icon.png"))
  await render(box(svg("aura-orb-foreground.svg"), 1024), path.join(PHONE, "assets/adaptive-icon.png"))
  await render(box(svg("aura-orb-monochrome.svg"), 1024), path.join(PHONE, "assets/adaptive-icon-monochrome.png"))
  const preview = process.argv[2]
  if (preview) {
    fs.mkdirSync(preview, { recursive: true })
    // masques du lanceur : l'icône adaptative fait 108 dp, le lanceur en montre 72 (zoom 1,5) sous son masque
    const masks = { rond: "50%", carre: "22%", goutte: "50% 50% 12% 50%" }
    const cell = (px, r, mono) => `<div style="width:${px}px;height:${px}px;border-radius:${r};overflow:hidden;background:${mono ? "#d8dcf0" : BG};position:relative">
      <div style="position:absolute;left:${-px / 4}px;top:${-px / 4}px;${mono ? "filter:brightness(0.18)" : ""}">${box(svg(mono ? "aura-orb-monochrome.svg" : "aura-orb-foreground.svg"), px * 1.5)}</div></div>`
    let html = `<div style="background:#3a3f55;padding:24px;display:flex;flex-direction:column;gap:24px;font:14px sans-serif;color:#fff">`
    for (const mono of [false, true]) for (const [n, r] of Object.entries(masks)) {
      html += `<div style="display:flex;align-items:center;gap:24px"><span style="width:150px">${n}${mono ? " (monochrome)" : ""}</span>`
      for (const px of [48, 72, 96, 192]) html += cell(px, r, mono)
      html += "</div>"
    }
    html += `</div>`
    await page.setViewportSize({ width: 760, height: 1500 })
    await page.setContent(`<html><body style="margin:0">${html}</body></html>`)
    await page.screenshot({ path: path.join(preview, "icone_masques.png"), fullPage: true })
  }
  await browser.close()
  console.log("icônes rendues")
})().catch((e) => { console.error(e); process.exit(1) })
