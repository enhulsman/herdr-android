// Browser checks for kudde.dev, one or more per scenario in
// openspec/changes/kudde-dev-site/specs/website/spec.md.
//
// `npm test` builds the site first; this file serves dist/ with `wrangler dev`
// (local only, no account) on a free port, as Cloudflare will serve it: the same
// routing, 404 handling and public/_headers. It runs every check in Chromium at
// 1440 x 900 and 375 x 812, then stops the server. Playwright is pinned to the
// version whose browser is installed.

import { test, describe, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const SITE_DIR = fileURLToPath(new URL('..', import.meta.url));
const PAGES = ['/', '/install', '/setup', '/security', '/herdr'];
const MISSING = '/no-such-page';
// every page a reader can land on, the 404 page included
const ALL = [...PAGES, MISSING];
const DESKTOP = { width: 1440, height: 900 };
const PHONE = { width: 375, height: 812 };
const REPO = 'https://github.com/enhulsman/kudde';
const FOOTER_LINE = 'Independent app, built on herdr-core. Not made by the herdr project.';

// Tag yellow and its lit shades, as computed colours.
const YELLOWS = [
  [242, 194, 48], // #f2c230 flat / lit middle
  [252, 220, 114], // #fcdc72 lit top
  [217, 157, 18], // #d99d12 lit bottom
  [226, 172, 20], // #e2ac14 deep
];

// The invented example content the phone may show.
const INVENTED_AGENTS = new Set([
  'Pricing page copy', 'api tests', 'Docs sidebar', 'Build checks', 'Changelog draft',
]);
const INVENTED_PLACE = /^(website|api|docs)( \/ \d+)? · (workstation|server)$/;

let server;
let browser;
let base;

function freePort() {
  return new Promise((resolve, reject) => {
    const s = createServer();
    s.unref();
    s.on('error', reject);
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address();
      s.close(() => resolve(port));
    });
  });
}

async function waitForServer(url, ms = 30000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    try {
      const r = await fetch(url);
      if (r.ok) return;
    } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error(`wrangler dev did not start at ${url}`);
}

before(async () => {
  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  // its own process group, so stopping it also stops the workerd it starts
  server = spawn(
    process.execPath,
    ['node_modules/wrangler/bin/wrangler.js', 'dev', '--ip', '127.0.0.1', '--port', String(port),
      '--show-interactive-dev-session=false'],
    { cwd: SITE_DIR, stdio: 'ignore', detached: true, env: { ...process.env, WRANGLER_SEND_METRICS: 'false' } },
  );
  await waitForServer(base + '/', 60000);
  browser = await chromium.launch();
});

after(async () => {
  await browser?.close();
  if (server) {
    try { process.kill(-server.pid, 'SIGTERM'); } catch { /* already gone */ }
  }
});

async function open(path, { viewport = DESKTOP, reducedMotion = 'no-preference', javaScriptEnabled = true } = {}) {
  const phone = viewport.width < 600;
  const context = await browser.newContext({
    viewport,
    reducedMotion,
    javaScriptEnabled,
    hasTouch: phone,
    isMobile: phone,
  });
  // collect Content-Security-Policy violations from the first script on
  await context.addInitScript(() => {
    window.__csp = [];
    document.addEventListener('securitypolicyviolation', (e) => {
      window.__csp.push(`${e.violatedDirective} ${e.blockedURI}`);
    });
  });
  const page = await context.newPage();
  const requests = [];
  const consoleErrors = [];
  page.on('request', (r) => requests.push(r.url()));
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  await page.goto(base + path, { waitUntil: 'load' });
  await page.evaluate(() => document.fonts.ready);
  return { page, context, requests, consoleErrors };
}

/** Waits for the finite animations under the hanging tag to end. */
async function settle(page) {
  await page.evaluate(() => Promise.all(
    document.getAnimations()
      .filter((a) => a.effect?.target?.closest?.('[data-hang]') && a.effect.getTiming().iterations !== Infinity)
      .map((a) => a.finished),
  ));
}

function normalise(pathname) {
  return pathname.length > 1 ? pathname.replace(/\/$/, '') : pathname;
}

describe('Five pages share one header and footer', () => {
  test('the header on every page at 1440 px, and every internal link resolves', async () => {
    const linksSeen = [];
    const idsByPage = {};
    for (const path of PAGES) {
      const { page, context } = await open(path);
      const header = page.locator('header').first();

      const brand = header.locator('a[data-brand]');
      assert.equal(await brand.count(), 1, `${path}: one brand link`);
      assert.equal(await brand.getAttribute('href'), '/', `${path}: brand links home`);
      assert.ok(await brand.locator('svg').count() >= 1, `${path}: the brand has the mark`);
      assert.match(await brand.innerText(), /^\s*kudde\s*$/, `${path}: the brand says kudde`);

      const expected = [
        ['Install', '/install'],
        ['Setup', '/setup'],
        ['Security', '/security'],
        ['kudde and herdr', '/herdr'],
        ['GitHub', REPO],
      ];
      for (const [name, href] of expected) {
        const link = header.getByRole('link', { name, exact: true });
        assert.equal(await link.count(), 1, `${path}: header link ${name}`);
        assert.ok(await link.isVisible(), `${path}: ${name} visible`);
        assert.equal(await link.getAttribute('href'), href, `${path}: ${name} href`);
      }
      const get = header.getByRole('link', { name: 'Get kudde', exact: true }).filter({ visible: true });
      assert.equal(await get.count(), 1, `${path}: one visible Get kudde button`);
      assert.equal(await get.getAttribute('href'), '/install');

      const footer = page.locator('footer');
      assert.equal(await footer.count(), 1, `${path}: one footer`);
      assert.ok((await footer.innerText()).includes(FOOTER_LINE), `${path}: footer line`);
      assert.equal(await page.locator('h1').count(), 1, `${path}: exactly one h1`);

      const hrefs = await page.$$eval('a[href]', (as) => as.map((a) => a.href));
      for (const h of hrefs) linksSeen.push({ from: path, href: h });
      idsByPage[path] = await page.$$eval('[id]', (els) => els.map((e) => e.id));
      await context.close();
    }
    for (const { from, href } of linksSeen) {
      const url = new URL(href);
      if (url.origin !== base) continue;
      const target = normalise(url.pathname);
      assert.ok(PAGES.includes(target), `${from}: internal link ${url.pathname} is one of the five pages`);
      if (url.hash) {
        const id = decodeURIComponent(url.hash.slice(1));
        assert.ok(idsByPage[target].includes(id), `${from}: anchor ${url.pathname}${url.hash} exists`);
      }
    }
  });

  test('the header at phone width: Get kudde visible, no sideways scroll', async () => {
    for (const path of PAGES) {
      const { page, context } = await open(path, { viewport: PHONE });
      const get = page.locator('header').getByRole('link', { name: 'Get kudde', exact: true }).filter({ visible: true });
      assert.equal(await get.count(), 1, `${path}: Get kudde visible at 375`);
      const box = await get.boundingBox();
      assert.ok(box.x >= 0 && box.x + box.width <= PHONE.width, `${path}: Get kudde within the width`);
      const sideways = await page.evaluate(() => {
        window.scrollTo(400, window.scrollY);
        return { scrollX: window.scrollX, sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth };
      });
      assert.equal(sideways.scrollX, 0, `${path}: does not scroll sideways`);
      assert.ok(sideways.sw <= sideways.cw, `${path}: content ${sideways.sw}px fits ${sideways.cw}px`);
      await context.close();
    }
  });
});

describe('The name is written in lowercase', () => {
  test('no capitalised name in visible text, labels or titles', async () => {
    for (const path of ALL) {
      const { page, context } = await open(path);
      const text = await page.evaluate(() => [
        document.title,
        document.body.innerText,
        ...[...document.querySelectorAll('[aria-label],[alt],[title]')].flatMap((e) => [
          e.getAttribute('aria-label'), e.getAttribute('alt'), e.getAttribute('title'),
        ]),
        ...[...document.querySelectorAll('title')].map((t) => t.textContent),
        document.querySelector('meta[name="description"]')?.content,
      ].filter(Boolean).join('\n'));
      assert.ok(!/Kudde/.test(text), `${path}: "Kudde" found: ${text.match(/.{0,40}Kudde.{0,40}/)?.[0]}`);
      assert.match(await page.title(), /kudde/, `${path}: the title names kudde`);
      await context.close();
    }
  });

  test('the voice: no em dashes, no arrows on links, no all-caps labels', async () => {
    for (const path of ALL) {
      const { page, context } = await open(path);
      const text = await page.evaluate(() => document.body.innerText);
      assert.ok(!text.includes('—'), `${path}: em dash found`);
      const arrows = await page.$$eval('a', (as) => as.map((a) => a.innerText.trim()).filter((t) => /[→↗»›]$/.test(t)));
      assert.deepEqual(arrows, [], `${path}: links end in an arrow`);
      const caps = await page.evaluate(() => [...document.querySelectorAll('body *')]
        .filter((e) => getComputedStyle(e).textTransform === 'uppercase' && e.innerText?.trim())
        .map((e) => e.innerText.trim()));
      assert.deepEqual(caps, [], `${path}: all-caps labels`);
      await context.close();
    }
  });
});

describe('The site loads nothing from anyone else', () => {
  test('every request goes to the site itself', async () => {
    for (const viewport of [DESKTOP, PHONE]) {
      for (const path of ALL) {
        const { page, context, requests } = await open(path, { viewport });
        await page.waitForLoadState('networkidle');
        const foreign = requests.filter((u) => !u.startsWith(base + '/') && !u.startsWith('data:') && !u.startsWith('blob:'));
        assert.deepEqual(foreign, [], `${path} at ${viewport.width}: foreign requests`);
        await context.close();
      }
    }
  });
});

/** The knob sits in the hole, the hole's top edge resting on it, and the tag turns around its centre. */
async function knobInHole(page, label) {
  await settle(page);
  const r = await page.evaluate(() => {
    const box = (el) => { const b = el.getBoundingClientRect(); return { x: b.x, y: b.y, w: b.width, h: b.height }; };
    const knob = document.querySelector('[data-hang] [data-knob]');
    const tag = document.querySelector('[data-tag]');
    const [ox, oy] = getComputedStyle(tag).transformOrigin.split(' ').map(parseFloat);
    const t = tag.getBoundingClientRect();
    return {
      knob: knob && box(knob),
      hole: box(document.querySelector('[data-hole]')),
      turnsWithTag: !!knob?.closest('[data-tag]'),
      pivot: { x: t.x + ox, y: t.y + oy },
    };
  });
  assert.ok(r.knob, `${label}: there is a knob`);
  assert.ok(!r.turnsWithTag, `${label}: the knob is fixed, not part of the turning tag`);
  const k = r.knob; const h = r.hole;
  const kc = { x: k.x + k.w / 2, y: k.y + k.h / 2 };
  assert.ok(k.x >= h.x && k.x + k.w <= h.x + h.w && k.y + k.h <= h.y + h.h, `${label}: the knob is inside the hole`);
  assert.ok(Math.abs(kc.x - (h.x + h.w / 2)) <= 1.5, `${label}: the knob is centred in the hole`);
  assert.ok(Math.abs(k.y - h.y) <= 1.5, `${label}: the hole's top edge rests on the knob (${k.y} vs ${h.y})`);
  assert.ok(k.w >= 24, `${label}: the knob reads clearly (${k.w}px)`);
  assert.ok(Math.abs(r.pivot.x - kc.x) <= 1.5 && Math.abs(r.pivot.y - kc.y) <= 1.5,
    `${label}: the tag turns on the knob's centre (${JSON.stringify(r.pivot)} vs ${JSON.stringify(kc)})`);
}

describe('The hanging tag', () => {
  test('lit, hanging on a knob through its hole, with the phone on its body', async () => {
    const { page, context } = await open('/');
    const hang = page.locator('[data-hang]');
    assert.equal(await hang.count(), 1, 'one hanging tag');
    assert.equal(await hang.getAttribute('aria-hidden'), 'true', 'the art is decorative');
    const lit = await page.evaluate(() => {
      const body = document.querySelector('[data-hang] [data-tag-body]');
      const ref = body?.getAttribute('fill')?.match(/url\(#(.+)\)/)?.[1];
      const grad = ref && document.getElementById(ref);
      return {
        gradient: grad?.tagName,
        stops: grad?.querySelectorAll('stop').length ?? 0,
        rim: !!document.querySelector('[data-hang] [data-tag-rim]'),
      };
    });
    assert.equal(lit.gradient, 'linearGradient', 'the tag is filled with a gradient');
    assert.ok(lit.stops >= 3, 'the gradient runs light to deep');
    assert.ok(lit.rim, 'the tag has rim light');

    await settle(page);
    assert.equal(await page.locator('[data-cord]').count(), 0, 'no cord');

    const tag = await page.locator('[data-tag-art]').boundingBox();
    const phone = await page.locator('[data-phone]').boundingBox();
    const pc = { x: phone.x + phone.width / 2, y: phone.y + phone.height / 2 };
    assert.ok(pc.x > tag.x && pc.x < tag.x + tag.width && pc.y > tag.y && pc.y < tag.y + tag.height, 'the phone stands on the tag');
    assert.equal(await page.locator('[data-tag] [data-phone]').count(), 1, 'the phone turns with the tag it stands on');
    await context.close();
  });

  test('the whole tag, hole included, is in the first screen at 1440 x 900', async () => {
    const { page, context } = await open('/');
    await settle(page);
    for (const sel of ['[data-tag-art]', '[data-hole]']) {
      const b = await page.locator(sel).boundingBox();
      assert.ok(b.x >= 0 && b.y >= 0, `${sel} starts in view (${b.x}, ${b.y})`);
      assert.ok(b.x + b.width <= DESKTOP.width && b.y + b.height <= DESKTOP.height,
        `${sel} ends in view (${b.x + b.width}, ${b.y + b.height})`);
    }
    await context.close();
  });

  test('scenario: the hole in view on a phone', async () => {
    const { page, context } = await open('/', { viewport: PHONE });
    await settle(page);
    for (const sel of ['[data-tag-art]', '[data-hole]']) {
      const b = await page.locator(sel).boundingBox();
      assert.ok(b.x >= 0, `${sel} left edge ${b.x} in view`);
      assert.ok(b.x + b.width <= PHONE.width, `${sel} right edge ${b.x + b.width} in view`);
    }
    await context.close();
  });

  test('the knob sits in the hole at 1440 and at 375', async () => {
    for (const viewport of [DESKTOP, PHONE]) {
      const { page, context } = await open('/', { viewport });
      await knobInHole(page, `at ${viewport.width}`);
      await context.close();
    }
  });

  test('swings once on load and leans toward a mouse pointer', async () => {
    const { page, context } = await open('/');
    const anims = await page.evaluate(() => document.getAnimations()
      .filter((a) => a.effect?.target?.closest?.('[data-tag]'))
      .map((a) => ({ name: a.animationName, iterations: a.effect.getTiming().iterations })));
    assert.ok(anims.some((a) => a.name === 'swing' && a.iterations === 1), `swings once: ${JSON.stringify(anims)}`);
    const swing = await page.evaluate(() => {
      const a = document.getAnimations().find((x) => x.animationName === 'swing');
      const angles = a.effect.getKeyframes().map((k) => parseFloat(k.transform.match(/rotate\(([-\d.]+)deg\)/)?.[1] ?? '0'));
      return { duration: a.effect.getTiming().duration, angles };
    });
    const peaks = swing.angles.filter((x) => x !== 0);
    const biggest = Math.max(...peaks.map(Math.abs));
    assert.ok(swing.duration >= 4000 && swing.duration <= 5000, `swings for 4 to 5 s (${swing.duration} ms)`);
    assert.ok(biggest >= 8 && biggest <= 10, `the first swing is 8 to 10 degrees (${biggest})`);
    assert.equal(Math.abs(peaks[0]), biggest, 'the first swing is the biggest');
    assert.ok(peaks.length >= 4, `a few oscillations (${peaks.length})`);
    for (let i = 1; i < peaks.length; i++) {
      assert.ok(Math.sign(peaks[i]) !== Math.sign(peaks[i - 1]) && Math.abs(peaks[i]) < Math.abs(peaks[i - 1]),
        `each swing turns back and is smaller (${swing.angles.join(', ')})`);
    }
    assert.equal(swing.angles.at(-1), 0, 'it settles');
    await settle(page);
    await page.mouse.move(40, 450);
    await page.waitForTimeout(150);
    const sway = await page.evaluate(() => getComputedStyle(document.querySelector('[data-tag-art]')).getPropertyValue('--sway').trim());
    assert.ok(sway && parseFloat(sway) !== 0, `leans toward the pointer (--sway ${sway})`);
    await context.close();
  });

  test('scenario: reduced motion shows the tag settled, no swing, no lean', async () => {
    const { page, context } = await open('/', { reducedMotion: 'reduce' });
    const running = await page.evaluate(() => document.getAnimations()
      .filter((a) => a.effect?.target?.closest?.('[data-hang]') && !a.effect.target.closest('[data-phone]'))
      .map((a) => a.animationName || a.transitionProperty || 'animation'));
    assert.deepEqual(running, [], 'no running animation on the tag');
    await page.mouse.move(40, 450);
    await page.mouse.move(1400, 300);
    await page.waitForTimeout(150);
    const state = await page.evaluate(() => {
      const svg = document.querySelector('[data-tag-art]');
      return {
        sway: getComputedStyle(svg).getPropertyValue('--sway').trim(),
        transform: getComputedStyle(svg.parentElement).transform,
        tagTransform: getComputedStyle(document.querySelector('[data-tag]')).transform,
      };
    });
    assert.ok(!state.sway || parseFloat(state.sway) === 0, `no lean (--sway ${state.sway})`);
    for (const t of [state.transform, state.tagTransform]) {
      assert.ok(t === 'none' || t === 'matrix(1, 0, 0, 1, 0, 0)', `settled transform, got ${t}`);
    }
    await context.close();
  });
});

describe('Yellow is never a background', () => {
  test('scenario: no element wider than 200 px has a yellow ground', async () => {
    for (const viewport of [DESKTOP, PHONE]) {
      for (const path of ALL) {
        const { page, context } = await open(path, { viewport });
        const hits = await page.evaluate((yellows) => {
          const isYellow = (r, g, b) => yellows.some(([R, G, B]) => R === r && G === g && B === b);
          const out = [];
          for (const el of document.querySelectorAll('body, body *')) {
            if (el.getBoundingClientRect().width <= 200) continue;
            const cs = getComputedStyle(el);
            const m = cs.backgroundColor.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/);
            if (m && (m[4] === undefined || +m[4] > 0) && isYellow(+m[1], +m[2], +m[3])) out.push(el.tagName + '.' + el.className);
            for (const c of cs.backgroundImage.matchAll(/rgba?\((\d+),\s*(\d+),\s*(\d+)/g)) {
              if (isYellow(+c[1], +c[2], +c[3])) out.push(el.tagName + '.' + el.className + ' (image)');
            }
          }
          return out;
        }, YELLOWS);
        assert.deepEqual(hits, [], `${path} at ${viewport.width}: yellow grounds`);
        await context.close();
      }
    }
  });
});

describe('The release fingerprint matches the repository', () => {
  test('scenario: the Install page shows the digest from docs/', async () => {
    const file = readFileSync(new URL('../../docs/release-signing-fingerprint.txt', import.meta.url), 'utf8');
    const lines = file.split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));
    assert.equal(lines.length, 1, 'one non-comment line');
    assert.match(lines[0], /^[0-9a-f]{64}$/, '64 lowercase hex characters');
    const { page, context } = await open('/install');
    const shown = page.locator('[data-fingerprint]');
    assert.equal(await shown.count(), 1, 'one fingerprint on the page');
    assert.equal((await shown.textContent()).trim(), lines[0], 'the same digest');
    await context.close();
  });
});

describe('Example content is invented', () => {
  test('scenario: the phone shows invented agent names and places', async () => {
    const { page, context } = await open('/');
    const phone = page.locator('[data-phone]');
    assert.equal(await phone.getAttribute('role'), 'img');
    assert.match(await phone.getAttribute('aria-label'), /kudde/, 'the phone has a text label');
    const names = await phone.locator('.txt b').allTextContents();
    const places = await phone.locator('.txt span').allTextContents();
    assert.ok(names.length >= 3, 'the phone lists agents');
    for (const n of names) assert.ok(INVENTED_AGENTS.has(n.trim()), `invented agent name: ${n}`);
    for (const p of places) assert.match(p.trim(), INVENTED_PLACE, `invented place: ${p}`);
    await context.close();
  });
});

describe('The pages are accessible', () => {
  test('lang, decorative art hidden, the phone labelled', async () => {
    for (const path of PAGES) {
      const { page, context } = await open(path);
      assert.equal(await page.getAttribute('html', 'lang'), 'en', `${path}: lang="en"`);
      const exposed = await page.$$eval('svg', (svgs) => svgs
        .filter((s) => !s.closest('[aria-hidden="true"]') && !(s.getAttribute('role') === 'img' && s.getAttribute('aria-label'))
          && !s.closest('[role="img"][aria-label]'))
        .map((s) => s.outerHTML.slice(0, 80)));
      assert.deepEqual(exposed, [], `${path}: svg not hidden or labelled`);
      await context.close();
    }
  });

  test('scenario: keyboard focus rings the Get kudde button', async () => {
    const { page, context } = await open('/');
    let found = false;
    for (let i = 0; i < 20 && !found; i++) {
      await page.keyboard.press('Tab');
      found = await page.evaluate(() => {
        const a = document.activeElement;
        return a?.closest('header') && a.textContent.trim() === 'Get kudde';
      });
    }
    assert.ok(found, 'Tab reaches Get kudde');
    const ring = await page.evaluate(() => {
      const cs = getComputedStyle(document.activeElement);
      return { style: cs.outlineStyle, width: parseFloat(cs.outlineWidth), color: cs.outlineColor };
    });
    assert.notEqual(ring.style, 'none', 'an outline shows');
    assert.ok(ring.width >= 2, `outline at least 2px, got ${ring.width}`);
    assert.ok(!/rgba\(.*, 0\)$/.test(ring.color) && ring.color !== 'transparent', 'the outline has a colour');
    await context.close();
  });

  test('scenario: buttons are at least 44 px high at 375 px', async () => {
    for (const path of PAGES) {
      const { page, context } = await open(path, { viewport: PHONE });
      const small = await page.$$eval('.btn, button', (els) => els
        .filter((e) => e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden')
        .map((e) => ({ text: e.innerText.trim(), h: e.getBoundingClientRect().height }))
        .filter((e) => e.h < 44));
      assert.deepEqual(small, [], `${path}: buttons under 44 px`);
      await context.close();
    }
  });

  test('text contrast meets WCAG AA', async () => {
    for (const viewport of [DESKTOP, PHONE]) {
      for (const path of ALL) {
        const { page, context } = await open(path, { viewport });
        const fails = await page.evaluate(() => {
          const parse = (c) => {
            const m = c.match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?\)/);
            return m ? [+m[1], +m[2], +m[3], m[4] === undefined ? 1 : +m[4]] : null;
          };
          const lum = ([r, g, b]) => {
            const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
            return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
          };
          const bgOf = (el) => {
            for (let e = el; e; e = e.parentElement) {
              const c = parse(getComputedStyle(e).backgroundColor);
              if (c && c[3] >= 0.99) return c;
            }
            return [247, 245, 239, 1];
          };
          const out = [];
          const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
          for (let n = walker.nextNode(); n; n = walker.nextNode()) {
            if (!n.textContent.trim()) continue;
            const el = n.parentElement;
            if (el.closest('[aria-hidden="true"], [role="img"], .sr-only, svg')) continue;
            if (!el.getClientRects().length) continue;
            const cs = getComputedStyle(el);
            const fg = parse(cs.color);
            const bg = bgOf(el);
            const a = lum(fg); const b = lum(bg);
            const ratio = (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
            const size = parseFloat(cs.fontSize);
            const large = size >= 24 || (size >= 18.66 && +cs.fontWeight >= 700);
            if (ratio < (large ? 3 : 4.5)) out.push(`${n.textContent.trim().slice(0, 30)} (${ratio.toFixed(2)})`);
          }
          return out;
        });
        assert.deepEqual(fails, [], `${path} at ${viewport.width}: low contrast`);
        await context.close();
      }
    }
  });
});

describe('The Setup page step markers', () => {
  test('three steps; without JavaScript the first marker is filled', async () => {
    const { page, context } = await open('/setup', { javaScriptEnabled: false });
    const fills = await page.$$eval('[data-step-marker]', (ms) => ms.map((m) => m.hasAttribute('data-current')));
    assert.deepEqual(fills, [true, false, false]);
    const colour = await page.$eval('[data-step-marker][data-current] [data-marker-shape]', (p) => getComputedStyle(p).fill);
    assert.equal(colour, 'rgb(242, 194, 48)', 'the current marker is filled with tag yellow');
    const other = await page.$eval('[data-step-marker]:not([data-current]) [data-marker-shape]', (p) => getComputedStyle(p).fill);
    assert.equal(other, 'none', 'the others are outlines');
    await context.close();
  });

  test('the marker of the step in view is filled', async () => {
    for (const viewport of [DESKTOP, PHONE]) {
      const { page, context } = await open('/setup', { viewport });
      await page.locator('[data-step]').nth(2).evaluate((el) => el.scrollIntoView({ block: 'center' }));
      await page.waitForTimeout(300);
      const fills = await page.$$eval('[data-step-marker]', (ms) => ms.map((m) => m.hasAttribute('data-current')));
      assert.deepEqual(fills, [false, false, true], `at ${viewport.width}`);
      await context.close();
    }
  });
});

describe('The home page content', () => {
  test('hero, what the app does, three steps and the app card', async () => {
    const { page, context } = await open('/');
    assert.equal((await page.locator('h1').innerText()).replace(/\s+/g, ' ').trim(), 'Keep in touch with your herd.');
    const text = await page.evaluate(() => document.body.innerText);
    for (const word of ['terminal', 'chat', 'Changes', 'alert', 'Your herd, in your pocket.']) {
      assert.ok(text.includes(word), `home mentions ${word}`);
    }
    assert.equal(await page.locator('[data-step-marker]').count(), 3, 'three steps');
    assert.equal(await page.locator('[data-app-card]').count(), 1, 'the app card closes the page');
    await context.close();
  });
});

describe('The 404 page', () => {
  test('an unknown path answers 404 with the site’s own page', async () => {
    const res = await fetch(base + MISSING);
    assert.equal(res.status, 404);
    assert.match(res.headers.get('content-type') ?? '', /text\/html/);
    const { page, context } = await open(MISSING);
    assert.equal(await page.locator('h1').count(), 1, 'one h1');
    assert.match(await page.title(), /kudde/);
    assert.equal(await page.locator('header a[data-brand]').count(), 1, 'the header');
    assert.ok((await page.locator('footer').innerText()).includes(FOOTER_LINE), 'the footer');
    const main = page.locator('main');
    for (const href of ['/', '/install']) {
      assert.ok(await main.locator(`a[href="${href}"]`).count() >= 1, `a way to ${href} in the page`);
    }
    const look = await page.evaluate(() => ({
      bg: getComputedStyle(document.body).backgroundColor,
      h1: getComputedStyle(document.querySelector('h1')).fontFamily,
    }));
    assert.equal(look.bg, 'rgb(247, 245, 239)', 'paper ground');
    assert.match(look.h1, /Bricolage/, 'display type');
    await context.close();
  });
});

describe('Hosting headers', () => {
  const csp = (value) => Object.fromEntries((value ?? '').split(';').map((d) => d.trim()).filter(Boolean)
    .map((d) => { const [k, ...v] = d.split(/\s+/); return [k, v]; }));

  function checkSecurityHeaders(h, what) {
    const policy = csp(h.get('content-security-policy'));
    assert.deepEqual(policy['default-src'], ["'self'"], `${what}: default-src 'self'`);
    assert.deepEqual(policy['frame-ancestors'], ["'none'"], `${what}: frame-ancestors 'none'`);
    assert.deepEqual(policy['object-src'], ["'none'"], `${what}: object-src 'none'`);
    for (const [k, v] of Object.entries(policy)) {
      assert.ok(!v.some((x) => /^https?:|^\*|^'unsafe-(inline|eval)'$/.test(x)), `${what}: ${k} allows only the site (${v.join(' ')})`);
    }
    assert.match(h.get('strict-transport-security') ?? '', /max-age=(\d{8,}|3[1-9]\d{6})/, `${what}: HSTS for a year or more`);
    assert.equal(h.get('x-content-type-options'), 'nosniff', `${what}: nosniff`);
    assert.ok(['no-referrer', 'strict-origin-when-cross-origin', 'same-origin'].includes(h.get('referrer-policy')), `${what}: Referrer-Policy`);
    assert.match(h.get('permissions-policy') ?? '', /camera=\(\)/, `${what}: Permissions-Policy`);
  }

  test('every page, the 404 page and an asset carry the security headers', async () => {
    for (const path of ALL) {
      const res = await fetch(base + path, { redirect: 'manual' });
      assert.equal(res.status, path === MISSING ? 404 : 200, `${path} answers without a redirect`);
      checkSecurityHeaders(res.headers, path);
      assert.ok(!/immutable/.test(res.headers.get('cache-control') ?? ''), `${path}: pages are not cached as immutable`);
    }
    assert.equal((await fetch(base + '/_headers')).status, 404, 'the rules file itself is not served');
    const html = await (await fetch(base + '/')).text();
    const css = html.match(/href="(\/_astro\/[^"]+\.css)"/)?.[1];
    assert.ok(css, 'the page links its stylesheet from /_astro/');
    const font = (await (await fetch(base + css)).text()).match(/url\((\/_astro\/[^)]+\.woff2)\)/)?.[1];
    assert.ok(font, 'the stylesheet loads a font from /_astro/');
    for (const asset of [css, font]) {
      const res = await fetch(base + asset);
      assert.equal(res.status, 200, asset);
      checkSecurityHeaders(res.headers, asset);
      const cc = res.headers.get('cache-control') ?? '';
      assert.match(cc, /max-age=31536000/, `${asset}: cached for a year (${cc})`);
      assert.match(cc, /immutable/, `${asset}: immutable (${cc})`);
      assert.ok(!/must-revalidate|max-age=0/.test(cc), `${asset}: no default cache header left over (${cc})`);
    }
  });

  test('no page breaks its Content-Security-Policy, at 1440 and 375', async () => {
    for (const viewport of [DESKTOP, PHONE]) {
      for (const path of ALL) {
        const { page, context, consoleErrors } = await open(path, { viewport });
        await page.waitForLoadState('networkidle');
        await page.mouse.move(200, 300);
        await page.waitForTimeout(200);
        const violations = await page.evaluate(() => window.__csp);
        assert.deepEqual(violations, [], `${path} at ${viewport.width}: CSP violations`);
        assert.deepEqual(consoleErrors.filter((m) => /Content Security Policy|Refused to/i.test(m)), [],
          `${path} at ${viewport.width}: CSP errors in the console`);
        await context.close();
      }
    }
  });
});
