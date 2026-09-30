// Screenshots the provisioned "Payments pipeline" dashboard at a fixed interval.
// Usage: node record.js <outDir> <label> <totalSeconds> <intervalSeconds>
// Env:   GRAFANA_URL (default http://localhost:3030), GRAFANA_ADMIN_USER / GRAFANA_ADMIN_PASSWORD
const fs = require('fs');
const puppeteer = require('puppeteer');

const [, , outDir, label, totalSeconds, intervalSeconds] = process.argv;
const base = process.env.GRAFANA_URL || 'http://localhost:3030';
const user = process.env.GRAFANA_ADMIN_USER || 'admin';
const password = process.env.GRAFANA_ADMIN_PASSWORD || 'change-me-local-only'; // local placeholder from .env.example

(async () => {
  fs.mkdirSync(outDir, { recursive: true });
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1200, height: 640 });
  await page.setExtraHTTPHeaders({ Authorization: 'Basic ' + Buffer.from(`${user}:${password}`).toString('base64') });
  const url = `${base}/d/payments-pipeline/payments-pipeline?orgId=1&kiosk&from=now-6m&to=now&refresh=5s`;
  await page.goto(url, { waitUntil: 'networkidle2', timeout: 60000 });

  const frames = Math.floor(Number(totalSeconds) / Number(intervalSeconds));
  const started = Date.now();
  for (let i = 0; i < frames; i++) {
    await page.evaluate((text, elapsed) => {
      let el = document.getElementById('lab-label');
      if (!el) {
        el = document.createElement('div');
        el.id = 'lab-label';
        el.style.cssText = 'position:fixed;top:14px;left:14px;z-index:99999;padding:4px 10px;border-radius:4px;' +
          'background:#111;color:#fff;font:600 16px system-ui,sans-serif;opacity:.9';
        document.body.appendChild(el);
      }
      el.textContent = `${text} · ${elapsed}s`;
    }, label, Math.round((Date.now() - started) / 1000));
    await page.screenshot({ path: `${outDir}/frame_${String(i).padStart(4, '0')}.png` });
    const wait = started + (i + 1) * Number(intervalSeconds) * 1000 - Date.now();
    if (wait > 0) await new Promise((r) => setTimeout(r, wait));
  }
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
