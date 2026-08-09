// Basic automated QA for the Family Notepad overlay in family-hub.html.
// Usage: cd family-hub && npm install && npm test
//
// Spins up a plain static file server for this directory, drives the page
// with Playwright, and asserts the notepad's core behavioral contract:
// default collapsed state, width-matching against real right-side UI
// elements, manual toggle, auto-open on a new note, forced 24h auto-collapse,
// the 12-message recent view, and the 50-message history cap.

const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const ROOT = path.join(__dirname, '..');
const PORT = 8791;

const MIME = { '.html': 'text/html', '.css': 'text/css', '.svg': 'image/svg+xml', '.js': 'text/javascript' };

function startServer() {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      const requestPath = decodeURIComponent(req.url.split('?')[0]);
      const filePath = path.join(ROOT, requestPath);
      fs.readFile(filePath, (err, data) => {
        if (err) { res.writeHead(404); res.end('not found'); return; }
        const ext = path.extname(filePath);
        res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
        res.end(data);
      });
    });
    server.listen(PORT, () => resolve(server));
  });
}

let passed = 0, failed = 0;
function check(label, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${ok ? '' : `  (expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)})`}`);
  if (ok) passed++; else failed++;
}

(async () => {
  const server = await startServer();
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  const jsErrors = [];
  let handoffRequest = null;
  page.on('pageerror', e => jsErrors.push(e.message));
  page.on('console', msg => { if (msg.type() === 'error') jsErrors.push(msg.text()); });

  // Keep the handoff entirely inside this local QA server. This also proves
  // the static source never needs to pass a Google token through a URL.
  await page.route(`http://localhost:${PORT}/api/auth/session`, async route => {
    const rejected = route.request().headers().authorization === 'Bearer qa-rejected-token';
    handoffRequest = {
      method: route.request().method(),
      url: route.request().url(),
      headers: route.request().headers(),
    };
    await route.fulfill({
      status: rejected ? 403 : 200,
      contentType: 'application/json',
      body: JSON.stringify({ email:'owner@example.com', authSource:'FAMILY_HUB', expiresAt:Date.now()+3600000 }),
    });
  });

  await page.goto(`http://localhost:${PORT}/family-hub.html`, { waitUntil: 'load' });
  await page.waitForTimeout(400);
  check('Family Hub hands one accepted Google credential to the platform session endpoint',
    await page.evaluate(apiBase => establishPlatformSession('qa-google-token', apiBase),
      `http://localhost:${PORT}`), true);
  check('handoff is a POST, never a token-bearing navigation', handoffRequest.method, 'POST');
  check('handoff URL contains no OAuth credential', handoffRequest.url.includes('qa-google-token'), false);
  check('handoff identifies Family Hub as its source',
    handoffRequest.headers['x-cabin-auth-source'], 'FAMILY_HUB');
  check('raw Google credential is not persisted in browser storage',
    await page.evaluate(() => Object.values({ ...localStorage, ...sessionStorage }).includes('qa-google-token')), false);
  const errorsBeforeRejectedHandoff = jsErrors.length;
  check('a rejected automated handoff falls back to cabin-ui direct authentication',
    await page.evaluate(apiBase => establishPlatformSession('qa-rejected-token', apiBase),
      `http://localhost:${PORT}`), false);
  const rejectedHandoffErrors = jsErrors.slice(errorsBeforeRejectedHandoff);
  check('rejected handoff raises no application JS exception',
    rejectedHandoffErrors.filter(message =>
      !message.includes('Failed to load resource: the server responded with a status of 403')).length, 0);
  // Chromium logs the intentionally mocked 403 as a resource error even though
  // establishPlatformSession handled it. Do not let that expected browser
  // message contaminate the later whole-journey application-error assertion.
  jsErrors.splice(errorsBeforeRejectedHandoff);
  // The remaining journey exercises the signed-in hub surface; auth itself is
  // covered separately in the mobile layering check below.
  await page.evaluate(() => document.getElementById('auth-overlay').classList.add('hidden'));

  // Default state is slid-in (collapsed), not open.
  check('default state is slid-in',
    await page.locator('#notepad-panel').evaluate(el => el.classList.contains('open')), false);

  // Expanded width is still computed from real right-side elements.
  // Collapsed width is a fixed constant as of 2026-08-07 (see
  // computeNotepadWidths()'s own comment: it used to also be computed from
  // these same elements, which caused the tucked-in handle to end up wider
  // than intended and block other UI -- a user-reported bug, confirmed via
  // this project's own CI failing "collapsed handle is fully on-screen"
  // the same day). Asserting the fixed value directly, not deriving it,
  // is the point: it should NOT track other elements' layout.
  const widths = await page.evaluate(() => {
    const cs = getComputedStyle(document.documentElement);
    return { expanded: cs.getPropertyValue('--np-w-expanded').trim(), collapsed: cs.getPropertyValue('--np-w-collapsed').trim() };
  });
  const refWidths = await page.evaluate(() =>
    ['chores-card', 'dashboard-fab', 'settings-btn']
      .map(id => document.getElementById(id).getBoundingClientRect().width));
  check('slid-out width == largest right-side element', widths.expanded, `${Math.round(Math.max(...refWidths))}px`);
  check('slid-in width is the fixed, narrow constant (not derived from other elements)', widths.collapsed, '56px');

  // The collapsed handle must actually be on-screen and clickable (regression
  // guard for the flex/translateX bug this suite was written to catch).
  const handleBox = await page.locator('#notepad-handle').boundingBox();
  const vw = page.viewportSize().width;
  check('collapsed handle is fully on-screen', handleBox.x >= 0 && handleBox.x + handleBox.width <= vw + 1, true);

  // Regression guard, added 2026-08-07, for a real user-reported bug: the
  // tucked-in notepad visibly covered #chores-card. Root cause was
  // computeNotepadTop() treating a *hidden* element's getBoundingClientRect
  // (mobile-action-dock, display:none on desktop, so top:0) as a real
  // position, poisoning the "how much room is below" math down to ~0 --
  // which a plain on-screen/bounding-box check doesn't catch, since a
  // clipped child still reports its own intended geometry. Two direct
  // checks: the panel must have real, non-zero height, and it must not
  // geometrically overlap #chores-card at all -- the actual hard
  // constraint the user asked for ("anything but covering the other
  // critical ui elements").
  const panelBox = await page.locator('#notepad-panel').boundingBox();
  check('notepad panel has real, non-zero rendered height', panelBox.height > 0, true);
  const choresBox = await page.locator('#chores-card').boundingBox();
  const overlapsChores = panelBox.y < choresBox.y + choresBox.height
    && panelBox.y + panelBox.height > choresBox.y
    && panelBox.x < choresBox.x + choresBox.width
    && panelBox.x + panelBox.width > choresBox.x;
  check('notepad panel never overlaps #chores-card, even tucked in', overlapsChores, false);

  // Manual slide-out / slide-in control.
  await page.locator('#notepad-handle').click();
  await page.waitForTimeout(500);
  check('handle click slides panel out', await page.locator('#notepad-panel').evaluate(el => el.classList.contains('open')), true);

  // Sending a note requires an explicit acting identity first -- no
  // silent default to the first profile (see docs/DEFINITION_OF_DONE.md's
  // 2026-08-02 note-attribution fix).
  check('composer is blocked with no actor selected',
    await page.locator('#notepad-input').isDisabled(), true);
  check('send button is disabled with no actor selected',
    await page.locator('.np-send').isDisabled(), true);
  check('"who\'s leaving this note" prompt is visible with no actor selected',
    await page.locator('#notepad-author-prompt').isVisible(), true);

  // Pick an identity via the notepad's own author row -- this must set the
  // *global* acting context (setCurrentActor), not a note-local variable.
  await page.locator('.np-author-pill').first().click();
  await page.waitForTimeout(100);
  check('prompt hides once an actor is selected',
    await page.locator('#notepad-author-prompt').isVisible(), false);
  check('composer enables once an actor is selected',
    await page.locator('#notepad-input').isDisabled(), false);

  // Compose a note.
  await page.locator('#notepad-input').fill('Pick up milk on the way home!');
  await page.locator('.np-send').click();
  await page.waitForTimeout(200);
  check('sent note appears in the list', await page.locator('#notepad-list .np-row').count() >= 1, true);
  check('input clears after send', await page.locator('#notepad-input').inputValue(), '');
  check('sent note is attributed to the selected actor, not a hardcoded default',
    await page.evaluate(() => loadNotes()[0].authorId), await page.evaluate(() => currentActorId));

  // Actor expiry must clear note attribution too -- they're the same
  // variable now, not two that can independently drift.
  await page.evaluate(() => clearActorState());
  await page.evaluate(() => renderNoteAuthorRow());
  check('composer re-blocks after the actor is cleared',
    await page.locator('#notepad-input').isDisabled(), true);

  // Re-select an actor so the rest of the suite (which assumes a working
  // composer) isn't left in the blocked state.
  await page.locator('.np-author-pill').first().click();
  await page.waitForTimeout(100);

  // Manual slide-in control (chevron).
  await page.locator('.np-close').click();
  await page.waitForTimeout(500);
  check('close chevron slides panel in', await page.locator('#notepad-panel').evaluate(el => el.classList.contains('open')), false);

  // Default action on a new note is to slide out automatically.
  await page.evaluate(() => {
    const notes = loadNotes();
    notes.unshift({ id: 'test-auto-open', authorId: profiles[0].id, text: 'Auto-open test note', ts: Date.now() });
    saveNotes(notes);
    onNewNoteArrived(notes[0].ts);
    renderNotepad();
  });
  await page.waitForTimeout(500);
  check('new note arrival auto-slides the panel out', await page.locator('#notepad-panel').evaluate(el => el.classList.contains('open')), true);

  // 24h-visible rule forces slide-in even without user action, independent
  // of whatever the user did with the manual control in the meantime.
  await page.evaluate(() => { toggleNotepad(true); });
  await page.evaluate(() => {
    notepadState.lastNoteTs = Date.now() - (25 * 60 * 60 * 1000);
    notepadState.forcedCollapseDone = false;
    saveNotepadState();
    checkNotepadAutoCollapse();
  });
  await page.waitForTimeout(300);
  check('panel force-collapses 24h after a note arrives', await page.locator('#notepad-panel').evaluate(el => el.classList.contains('open')), false);

  // Recent view caps at 12 lines; full history caps storage at 50.
  await page.evaluate(() => {
    let notes = [];
    for (let i = 0; i < 55; i++) notes.unshift({ id: 'n' + i, authorId: profiles[0].id, text: 'Note #' + i, ts: Date.now() - i * 1000 });
    saveNotes(notes);
    renderNotepad();
    toggleNotepad(true);
  });
  await page.waitForTimeout(400);
  check('recent list caps at 12 messages', await page.locator('#notepad-list .np-row').count(), 12);
  check('storage caps at 50 messages (oldest dropped)', await page.evaluate(() => loadNotes().length), 50);

  await page.locator('.np-history-link').click();
  await page.waitForTimeout(400);
  check('history overlay opens', await page.locator('#note-history-overlay').evaluate(el => el.classList.contains('open')), true);
  check('history shows all 50 saved messages', await page.locator('#note-history-list .np-row').count(), 50);
  check('"last 50 notes are saved" hint is present', await page.locator('#note-history-overlay .np-save-hint').isVisible(), true);

  check('no JS errors during the run', jsErrors, []);

  // Cross-app theme handoff (added 2026-08-07, Phase 7 §2c/2d -- see
  // docs/EXECUTION_PLAN_2026-08-07_template-theme-camera.md): the actual
  // reported bug was "theme resets when I link out to cabin-ui," root-caused
  // to origin-scoped localStorage between the two subdomains. Two directions
  // to prove: (1) the "How's the cabin?" link-out carries this app's current
  // theme forward, (2) loading with ?theme=<id> in the URL applies it here,
  // the same way cabin-ui's ThemeProvider now does on its side.
  check('location-links-out carries the active theme as a query param',
    await page.evaluate(() => locationLinksHtml().includes(`theme=${activeThemeId}`)), true);

  const themedPage = await browser.newPage();
  const themedJsErrors = [];
  themedPage.on('pageerror', e => themedJsErrors.push(e.message));
  await themedPage.goto(`http://localhost:${PORT}/family-hub.html?theme=lcars`, { waitUntil: 'load' });
  check('?theme= URL param is applied on load, not just the last-saved localStorage value',
    await themedPage.evaluate(() => activeThemeId), 'lcars');
  check('?theme= param application produces no JS errors', themedJsErrors, []);
  await themedPage.close();

  // Phone journey: every high-frequency family action must be visible and
  // reachable without hunting through tabs or losing the current hub context.
  const mobile = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  const mobileJsErrors = [];
  mobile.on('pageerror', e => mobileJsErrors.push(e.message));
  mobile.on('console', msg => { if (msg.type() === 'error') mobileJsErrors.push(msg.text()); });
  await mobile.goto(`http://localhost:${PORT}/family-hub.html`, { waitUntil: 'load' });
  await mobile.waitForTimeout(400);

  check('sign-in context hides quick actions until the hub is available', await mobile.locator('#mobile-action-dock').isVisible(), false);
  await mobile.evaluate(() => document.getElementById('auth-overlay').classList.add('hidden'));

  check('mobile quick-action dock is visible', await mobile.locator('#mobile-action-dock').isVisible(), true);
  check('mobile dock exposes four single-tap actions', await mobile.locator('#mobile-action-dock .mobile-action').count(), 4);
  check('desktop dashboard FAB is replaced on mobile', await mobile.locator('#dashboard-fab').isVisible(), false);
  check('mobile page has no horizontal document overflow', await mobile.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), true);

  await mobile.getByRole('button', { name: 'View the parenting schedule' }).click();
  await mobile.waitForTimeout(150);
  check('schedule action opens dashboard', await mobile.locator('#dashboard-overlay').getAttribute('aria-hidden'), 'false');
  check('schedule action lands directly in Parenting Days', await mobile.locator('#tab-schedule').evaluate(el => el.classList.contains('active')), true);
  check('schedule remains horizontally scrollable instead of squeezing days unreadably',
    await mobile.locator('#schedule-grid').evaluate(el => el.scrollWidth > el.clientWidth), true);

  await mobile.locator('#dash-close').click();
  await mobile.getByRole('button', { name: 'Read or send a family note' }).click();
  await mobile.waitForTimeout(150);
  check('notes action opens composer in the same hub context', await mobile.locator('#notepad-panel').evaluate(el => el.classList.contains('open')), true);
  const noteBox = await mobile.locator('#notepad-panel').boundingBox();
  const dockBox = await mobile.locator('#mobile-action-dock').boundingBox();
  check('open note composer stays above the mobile action dock', noteBox.y + noteBox.height <= dockBox.y + 1, true);

  await mobile.getByRole('button', { name: 'Open the family dashboard' }).click();
  await mobile.waitForTimeout(150);
  check('overview secondary cards collapse for one-screen scanning', await mobile.locator('#tab-overview .dash-card.mobile-collapsed').count() > 0, true);
  check('next-seven-days schedule stays expanded by default',
    await mobile.locator('#tab-overview .dash-card').filter({ hasText: 'Next 7 Days' }).evaluate(el => !el.classList.contains('mobile-collapsed')), true);
  check('unconfigured cabin activity explains why instead of disappearing',
    (await mobile.locator('#cabin-activity-widget').innerText()).includes('not connected on this device'), true);
  check('unavailable house capability explains its status onscreen',
    (await mobile.locator('.location-link-disabled').innerText()).includes('working template'), true);
  check('no JS errors during mobile journey', mobileJsErrors, []);

  await mobile.close();

  await browser.close();
  await new Promise(r => server.close(r));

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
})();
