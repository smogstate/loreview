// Phase 3 gate (SPEC 15): selecting a client in one tile filters another, on a mosaic.
// Usage: clojure -M:dev, then: node test/browser/mosaic-check.js [baseUrl] [screenshotDir]
const puppeteer = require('puppeteer-core');
const BASE = (process.argv[2] || 'http://localhost:8292').replace(/\/?$/, '/');
const SHOTS = process.argv[3];
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS ' : 'FAIL ') + name); if (!ok) failures++; };

// the invoices tile is the one whose title says "of a client"
const invoiceIds = (page) => page.evaluate(() => {
  const t = [...document.querySelectorAll('.tile')].find((s) => /of a client/.test(s.querySelector('h2').textContent));
  return t && t.dataset.status === 'ok' ? [...t.querySelectorAll('tbody tr')].map((tr) => tr.children[0].textContent) : null;
});
const waitIds = (page, want) => page.waitForFunction((w) => {
  const t = [...document.querySelectorAll('.tile')].find((s) => /of a client/.test(s.querySelector('h2').textContent));
  return t && t.dataset.status === 'ok' && [...t.querySelectorAll('tbody tr')].map((tr) => tr.children[0].textContent).join() === w;
}, { timeout: 15000 }, want.join());

async function open(browser, user, errors) {
  const page = await browser.newPage();
  page.on('pageerror', (e) => errors.push(e.message));
  await page.setViewport({ width: 1300, height: 800 });
  await page.evaluateOnNewDocument((u) => localStorage.setItem('loreview-user', u), user);
  await page.goto(BASE + '#/mosaics/mosaic%2Freceivables', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.querySelectorAll('.mosaic .tile[data-status="ok"]').length === 2, { timeout: 30000 });
  return page;
}

(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: 'new' });
  const errors = [];
  try {
    // ana: click the Globex bar in the chart
    const pa = await open(browser, 'ana', errors);
    check((await invoiceIds(pa)).join() === 'i01,i03', 'before any selection, the invoices tile shows its default client (ACME)');
    const bars = await pa.$$('.chart svg .mark-rect path');
    check(bars.length === 5, `the chart has five bars (${bars.length})`);
    await bars[4].click();                                   // Globex, the last bar
    await waitIds(pa, ['i04', 'i05']).catch(() => {});
    check((await invoiceIds(pa)).join() === 'i04,i05', `clicking Globex in the chart shows Globex's invoices (${await invoiceIds(pa)})`);
    if (SHOTS) await pa.screenshot({ path: `${SHOTS}/mosaic-ana.png` });
    await bars[2].click();                                   // "—": the client nobody has
    await new Promise((r) => setTimeout(r, 800));
    check((await invoiceIds(pa)).join() === 'i04,i05', 'clicking the empty category emits nothing');

    // bob: amounts hidden, so the first tile is a table; click the Initech row
    const pb = await open(browser, 'bob', errors);
    check(await pb.evaluate(() => !!document.querySelector('.tile .empty-result')), 'bob cannot see ACME, so its invoices tile says there is nothing to show');
    const rows = await pb.$$('.mosaic .tile tr.selectable');
    const names = await Promise.all(rows.map((r) => r.evaluate((tr) => tr.children[0].textContent)));
    await rows[names.indexOf('Initech')].click();
    await waitIds(pb, ['i07', 'i08']).catch(() => {});
    check((await invoiceIds(pb)).join() === 'i07,i08', `clicking Initech in the table shows its invoices (${await invoiceIds(pb)})`);
    const amounts = await pb.evaluate(() => [...[...document.querySelectorAll('.tile')]
      .find((s) => /of a client/.test(s.querySelector('h2').textContent))
      .querySelectorAll('tbody tr td:last-child')].map((td) => td.textContent));
    check(amounts.every((a) => a === '—'), 'his invoice amounts stay hidden');
    if (SHOTS) await pb.screenshot({ path: `${SHOTS}/mosaic-bob.png` });

    check(errors.length === 0, `no page errors (${errors.join(' | ')})`);
  } catch (e) {
    console.log('CHECK CRASHED', e.message); failures++;
  } finally {
    await browser.close();
  }
  console.log(failures ? `mosaic-check: ${failures} FAILED` : 'mosaic-check: OK');
  process.exit(failures ? 1 : 0);
})();
