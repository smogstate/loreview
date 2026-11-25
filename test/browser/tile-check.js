// Phase 1 gate (SPEC 15): the example tile renders for two fixture users with different
// results, and a hidden column says why it is empty.
// Usage: clojure -M:dev, then: node test/browser/tile-check.js [baseUrl] [screenshotDir]
const puppeteer = require('puppeteer-core');
const BASE = (process.argv[2] || 'http://localhost:8292').replace(/\/?$/, '/');
const SHOTS = process.argv[3];
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS ' : 'FAIL ') + name); if (!ok) failures++; };

async function viewAs(page, user) {
  await page.evaluateOnNewDocument((u) => localStorage.setItem('loreview-user', u), user);
  await page.goto(BASE + '#/tiles/tile%2Funpaid-invoices/1', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.querySelector('.tile[data-status="ok"] .chart svg .mark-rect, .tile[data-status="ok"] table.data'), { timeout: 30000 });
  return page.evaluate(() => ({
    title: document.querySelector('.tile h2').textContent,
    bars: document.querySelectorAll('.chart svg .mark-rect path').length,
    labels: [...document.querySelectorAll('.chart svg .role-axis-label text')].map((t) => t.textContent),
    table: [...document.querySelectorAll('.tile table.data tbody tr')].map((tr) => [...tr.children].map((td) => td.textContent)),
    notes: [...document.querySelectorAll('.tile ul.notes li')].map((li) => li.textContent),
    asOf: document.querySelector('.tile footer')?.textContent || '',
    param: document.querySelector('.param input')?.value,
  }));
}

(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: 'new' });
  try {
    const errors = [];
    const open = async () => { const p = await browser.newPage(); p.on('pageerror', (e) => errors.push(e.message)); await p.setViewport({ width: 1100, height: 760 }); return p; };

    const pa = await open();
    const ana = await viewAs(pa, 'ana');
    console.log('ana', JSON.stringify(ana));
    check(ana.title === 'Unpaid invoices over 30 days by client', 'the tile shows its title');
    check(ana.bars === 5, `ana sees five clients (${ana.bars})`);
    check(ana.labels.includes('Umbrella') && ana.labels.includes('ACME'), 'ana sees EU clients by name');
    check(!ana.notes.some((n) => /hidden/.test(n)), 'nothing is hidden from ana');
    check(ana.param === '30', 'the parameter control shows its default');
    check(/Data as of/.test(ana.asOf), 'the data as-of time is shown');
    if (SHOTS) await pa.screenshot({ path: `${SHOTS}/tile-ana.png` });

    const pb = await open();
    const bob = await viewAs(pb, 'bob');
    console.log('bob', JSON.stringify(bob));
    const names = bob.table.map((r) => r[0]);
    check(bob.bars === 0 && bob.table.length === 3, `with every amount hidden, bob gets the table, not an empty chart (${bob.table.length} rows)`);
    check(!names.includes('Umbrella') && !names.includes('ACME'), 'bob does not see EU clients by name');
    check(names.includes('Globex') && names.includes('Initech'), 'bob sees US clients');
    check(bob.table.every((r) => r[1] === '—'), 'his totals are empty, not zero');
    check(bob.notes.includes('Amount is hidden from you'), 'the hidden amount says why the chart is empty');
    check(bob.notes.includes('Rows may be limited by your access'), 'bob is told rows are cut by his access');
    if (SHOTS) await pb.screenshot({ path: `${SHOTS}/tile-bob.png` });

    // the kpi tile: one number, explained per viewer (contract values are visible per row)
    for (const u of ['ana', 'bob', 'cleo']) {
      const pk = await open();
      await pk.evaluateOnNewDocument((x) => localStorage.setItem('loreview-user', x), u);
      await pk.goto(BASE + '#/tiles/tile%2Fcontract-total/1', { waitUntil: 'domcontentloaded' });
      await pk.waitForFunction(() => document.querySelector('.tile[data-status="ok"] .kpi, .tile[data-status="ok"] .empty-result, .tile[data-status="ok"] table.data'), { timeout: 30000 });
      const k = await pk.evaluate(() => ({
        value: document.querySelector('.kpi-value')?.textContent,
        notes: [...document.querySelectorAll('.tile ul.notes li')].map((li) => li.textContent),
      }));
      console.log('kpi', u, JSON.stringify(k));
      check(k.value !== undefined, `${u} sees the contract total as a kpi (${k.value})`);
      if (SHOTS && u === 'ana') await pk.screenshot({ path: `${SHOTS}/kpi-ana.png` });
    }

    check(errors.length === 0, `no page errors (${errors.join(' | ')})`);
  } catch (e) {
    console.log('CHECK CRASHED', e.message); failures++;
  } finally {
    await browser.close();
  }
  console.log(failures ? `tile-check: ${failures} FAILED` : 'tile-check: OK');
  process.exit(failures ? 1 : 0);
})();
