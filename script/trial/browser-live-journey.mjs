import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';

// No new browser dependencies: reuse the actual frontend workspace's installed Playwright.
const frontend = process.env.TRIAL_FRONTEND_DIR || '/opt/mgs/yudao-ui-admin-vben';
const { chromium } = createRequire(path.join(frontend, 'package.json'))('playwright');
const input = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const base = process.env.TRIAL_UI_URL;
for (const url of [base, input.backendUrl]) {
  assert.equal(new URL(url).hostname, '127.0.0.1', 'Only isolated loopback servers are allowed');
}
assert.notEqual(new URL(base).origin, new URL(input.backendUrl).origin);
const output = process.env.TRIAL_BROWSER_OUT || '/tmp/mgs-trial-live-browser';
fs.mkdirSync(output, { recursive: true });
const content = '隔离联调：Agent 已提交客户跟进，浏览器读取同一业务记录';
const evidence = { browser: 'Playwright (Browser plugin not available)', backend: 'Loopback Tomcat; actual MGS login/OAuth/permissions/CRM; H2 and jedis-mock; KnowDo and dictionary fixtures', apiResponsesMocked: false, realKnowdoOrWeChatVerified: false, scenarios: [] };
const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE || undefined, args: ['--no-sandbox'] });
try {
  for (const [index, account] of input.accounts.entries()) {
    const width = index === 0 ? 1440 : 390;
    const context = await browser.newContext({ viewport: { width, height: 1000 }, timezoneId: 'UTC' });
    const page = await context.newPage();
    const scenario = { width, account: index + 1, calls: [], console: [], externalBlocked: [], checks: {} };
    evidence.scenarios.push(scenario);
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    page.on('console', message => {
      if (['warning', 'error'].includes(message.type())) scenario.console.push(message.text());
    });
    await context.route('**/*', async route => {
      const request = route.request(); const url = new URL(request.url());
      if (url.origin !== new URL(base).origin) {
        scenario.externalBlocked.push(url.origin + url.pathname);
        if (request.resourceType() === 'image') return route.fulfill({ contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48"><rect width="48" height="48" fill="#1677ff"/></svg>' });
        return route.abort();
      }
      if (!url.pathname.startsWith('/admin-api/')) return route.continue();
      scenario.calls.push({ method: request.method(), path: url.pathname });
      // Transport-only proxy to the ephemeral test server. No synthesized API response and no Vite production proxy.
      const response = await route.fetch({ url: input.backendUrl + url.pathname + url.search, maxRedirects: 0 });
      return route.fulfill({ response });
    });
    try {
      await page.goto(base + '/auth/login', { waitUntil: 'domcontentloaded' });
      await page.getByPlaceholder('请输入用户名').fill(account.username);
      await page.getByPlaceholder('请输入密码').fill(account.password);
      const loginResponse = page.waitForResponse(response => response.url().includes('/system/auth/login'));
      await page.getByRole('button', { name: 'login', exact: true }).click();
      const login = await (await loginResponse).json();
      assert.equal(login.code, 0, `Actual browser login failed: ${login.msg || 'no business message'}`);
      await page.waitForURL('**/trial-experience', { timeout: 60000 });
      await page.getByText('演示客户 · 星河文具（虚构）', { exact: true }).waitFor();
      assert.equal(await page.getByRole('button', { name: /新增|创建|提交/ }).count(), 0);
      const headers = { Authorization: `Bearer ${account.agentToken}`, 'tenant-id': String(account.tenantId) };
      const customerResponse = await context.request.get(input.backendUrl + '/admin-api/crm/trial-business/customer', { headers });
      const customer = await customerResponse.json(); assert.equal(customer.code, 0); assert.equal(customer.data.id, account.customerId);
      if (index === 0) {
        const data = { idempotencyKey: 'live-browser-agent-follow-up-1', content, type: 1, nextTime: '2027-01-02T10:30:00' };
        const written = await context.request.post(input.backendUrl + '/admin-api/crm/trial-business/follow-up', { headers, data });
        const result = await written.json(); assert.equal(result.code, 0, 'Agent follow-up request failed'); assert.equal(typeof result.data, 'number');
        const repeated = await context.request.post(input.backendUrl + '/admin-api/crm/trial-business/follow-up', { headers, data });
        assert.equal((await repeated.json()).data, result.data);
        const refreshed = page.waitForResponse(response => response.url().includes('/crm/trial-business/follow-ups'));
        await page.getByRole('button', { name: '刷新办理结果' }).click();
        const rows = await (await refreshed).json(); assert.equal(rows.code, 0); assert.equal(rows.data.length, 1); assert.equal(rows.data[0].id, result.data);
        assert.equal(typeof rows.data[0].nextTime, 'number', 'Use the production LocalDateTime serializer');
        await page.getByText(content, { exact: false }).waitFor();
        assert.ok((await page.locator('body').innerText()).includes('2027-01-02 10:30:00'), 'Next-contact time must be readable, not a raw epoch number');
        await page.reload(); await page.getByText(content, { exact: false }).waitFor();
        scenario.checks.agentWriteBrowserReadSameId = true;
        scenario.checks.reloadPreservesResult = true;
        scenario.checks.productionTimestampRenderedAsDate = true;
      } else {
        await page.getByText('尚无跟进记录，请通过公众号 Agent 办理一次演示跟进', { exact: true }).waitFor();
        assert.equal(await page.getByText(content, { exact: false }).count(), 0);
        const rows = await context.request.get(input.backendUrl + '/admin-api/crm/trial-business/follow-ups', { headers });
        assert.equal((await rows.json()).data.length, 0);
        scenario.checks.otherAccountCannotReadRecord = true;
      }
      assert.equal(new URL(page.url()).pathname, '/trial-experience');
      assert.ok((await page.title()).trim().length > 0);
      assert.ok((await page.locator('body').innerText()).includes('我的业务体验'));
      await page.locator('#__app-loading__').waitFor({ state: 'hidden' });
      assert.equal(await page.locator('vite-error-overlay').count(), 0);
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 2));
      assert.equal(pageErrors.length, 0, 'Unhandled browser exceptions');
      const knownWarnings = /Failed to load resource: net::ERR_FAILED|\[Vben Form\].*(BREAKING CHANGE|Legacy dependency callbacks)|\[StorageManager\] empty prefix/;
      assert.equal(scenario.console.filter(message => !knownWarnings.test(message)).length, 0, 'Unexpected browser warning/error; see sanitized evidence');
      assert.ok(!scenario.calls.some(call => /dict-data|notify-message/.test(call.path)));
      scenario.checks = { ...scenario.checks, actualLogin: true, pageIdentity: true, notBlank: true, noLoadingOverlay: true, noFrameworkOverlay: true, noHorizontalOverflow: true, noPageErrors: true, noUnexpectedConsoleErrors: true, noWriteButtons: true };
      scenario.url = page.url(); scenario.title = await page.title();
      await page.screenshot({ path: path.join(output, `account-${index + 1}-${width}.png`), fullPage: false });
    } catch (error) {
      await page.screenshot({ path: path.join(output, `failed-${index + 1}.png`), fullPage: false });
      scenario.failure = error.message; throw error;
    } finally { await context.close(); }
  }
  console.log('PASS: actual browser login, Agent write/read-back/reload, and second-account isolation');
} finally {
  fs.writeFileSync(path.join(output, 'evidence.json'), JSON.stringify(evidence, null, 2) + '\n');
  await browser.close();
}
