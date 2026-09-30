// Exports Markdown reports to styled PDFs: pandoc converts Markdown to HTML, headless Chrome (puppeteer) renders
// Mermaid diagrams and prints the page.
// Usage: node scripts/export_pdf.js <input.md> [<input.md> ...]      (writes <input>.pdf next to each input)
// Needs: pandoc, and node with puppeteer and mermaid resolvable. Either run `npm install` in scripts/record-grafana, or set
//        PUPPETEER_NODE_PATH to a node_modules directory that contains both (NODE_PATH is used to resolve them).
const { execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const root = path.resolve(__dirname, '..');
const nodePath = process.env.PUPPETEER_NODE_PATH || path.join(root, 'scripts', 'record-grafana', 'node_modules');
module.paths.push(nodePath);
const puppeteer = require(require.resolve('puppeteer', { paths: [nodePath] }));
const mermaidFile = require.resolve('mermaid/dist/mermaid.min.js', { paths: [nodePath] });

async function main(inputs) {
  if (inputs.length === 0) {
    console.error('usage: node scripts/export_pdf.js <input.md> [...]');
    process.exit(2);
  }
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  try {
    for (const input of inputs) {
      const html = path.join(os.tmpdir(), `export-${path.basename(input)}.html`);
      const title = fs.readFileSync(input, 'utf8').split('\n')[0].replace(/^#\s*/, '');
      execFileSync('pandoc', [input, '-f', 'gfm', '-t', 'html5', '--standalone', '--embed-resources',
        '--css', path.join(root, 'scripts', 'pdf-style.css'), '--metadata', `title=${title}`, '--metadata', 'pagetitle=' + title,
        '-V', 'document-css=false', '-o', html]);
      // pandoc puts the title in a header block; the reports already start with an H1, so hide the duplicate.
      fs.appendFileSync(html, '<style>header#title-block-header{display:none}</style>');
      const page = await browser.newPage();
      await page.goto('file://' + html, { waitUntil: 'load' });
      await page.addScriptTag({ path: mermaidFile });
      await page.evaluate(async () => {
        document.querySelectorAll('pre.mermaid').forEach((pre) => { pre.textContent = pre.textContent; });
        window.mermaid.initialize({ startOnLoad: false, theme: 'neutral' });
        await window.mermaid.run({ querySelector: 'pre.mermaid' });
      });
      // Fail loudly rather than print raw diagram source if a Mermaid block did not render.
      const { blocks, rendered } = await page.evaluate(() => ({
        blocks: document.querySelectorAll('pre.mermaid').length,
        rendered: document.querySelectorAll('pre.mermaid svg').length,
      }));
      if (blocks !== rendered) {
        throw new Error(`${input}: ${blocks} Mermaid block(s) but only ${rendered} rendered`);
      }
      console.log(`${input}: ${rendered} diagram(s) rendered`);
      const out = input.replace(/\.md$/, '.pdf');
      await page.pdf({
        path: out, format: 'A4', printBackground: true, displayHeaderFooter: true,
        headerTemplate: '<span></span>',
        footerTemplate: `<div style="font-size:8px;width:100%;text-align:center;color:#5b6675">${title.replace(/[<&]/g, '')} - page <span class="pageNumber"></span> of <span class="totalPages"></span></div>`,
        margin: { top: '18mm', bottom: '20mm', left: '15mm', right: '15mm' },
      });
      await page.close();
      console.log(`wrote ${out}`);
    }
  } finally {
    await browser.close();
  }
}

main(process.argv.slice(2)).catch((e) => { console.error(e); process.exit(1); });
