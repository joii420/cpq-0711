import { test, expect, type Page } from '@playwright/test';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { loginAsAdmin } from './fixtures/auth';

const FAKE = process.env.FILE_CRYPTO_FAKE_URL!;
const EVIDENCE = path.resolve(process.env.PW_EVIDENCE_DIR || 'test-results/task261026-file-crypto-evidence');
const RUN = `${Date.now()}-${process.pid}`;
const DIR_NAME = `T261026-CRYPTO-UI-DIR-${RUN}`;
const COMPONENT_CODE = `T261026-CRYPTO-UI-COMP-${RUN}`;
const CIPHER = Buffer.from(`T261026-CRYPTO-UI-CIPHER-${RUN}`);

type FakeCapture = {
  method: string;
  path: string;
  size: number;
  sha256: string;
  contentType: string;
  encodedFileName: string;
  requestId: string;
  authorizationMatched: boolean;
};

async function fake(pathname: string, init?: RequestInit) {
  const response = await fetch(`${FAKE}${pathname}`, init);
  expect(response.ok, `fake control ${pathname} -> ${response.status}`).toBeTruthy();
  return response.json() as Promise<any>;
}

async function choosePolicy(page: Page, panel: ReturnType<Page['locator']>, label: RegExp) {
  const labelled = panel.locator('.ant-form-item').filter({ hasText: /冲突.*策略|冲突处理/ })
    .locator('.ant-select').first();
  const select = (await labelled.count()) > 0 ? labelled : panel.locator('.ant-select').first();
  await expect(select, '组件导入弹层应有冲突策略选择器').toBeVisible();
  await select.click();
  const option = page.locator('.ant-select-item-option').filter({ hasText: label }).last();
  await expect(option, `冲突策略应包含 ${label}`).toBeVisible();
  await option.click();
}

async function deletePrivateFixture(page: Page, directoryId: string) {
  const list = await page.request.get(`/api/cpq/components?directoryId=${directoryId}`);
  if (list.ok()) {
    const json = await list.json();
    const candidates = Array.isArray(json?.data) ? json.data : (json?.data?.content || []);
    for (const component of candidates.filter((item: any) => item.directoryId === directoryId)) {
      await page.request.delete(`/api/cpq/components/${component.id}`);
    }
  }
  await page.request.delete(`/api/cpq/component-directories/${directoryId}`);
}

async function componentsInDirectory(page: Page, directoryId: string) {
  const response = await page.request.get(`/api/cpq/components?directoryId=${directoryId}`);
  expect(response.ok(), `component list HTTP ${response.status()}`).toBeTruthy();
  const json = await response.json();
  const candidates = Array.isArray(json?.data) ? json.data : (json?.data?.content || []);
  return candidates.filter((item: any) => item.directoryId === directoryId);
}

test('T-U01 / AC-4: encrypted Bundle preview -> policy re-preview -> commit -> close/reopen/refresh', async ({ page, context }) => {
  fs.mkdirSync(EVIDENCE, { recursive: true });
  await loginAsAdmin(page);

  // Pre-warm the newly started Vite stack before the timed sequence assertions.
  await page.goto('/components');
  await expect(page.getByText('组件管理', { exact: true }).first(),
    '组件管理页标题应可见').toBeVisible({ timeout: 60_000 });

  const createDirectory = await page.request.post('/api/cpq/component-directories', {
    data: { name: DIR_NAME, parentId: null },
  });
  expect(createDirectory.ok(), await createDirectory.text()).toBeTruthy();
  const directoryId = (await createDirectory.json()).data.id as string;

  await context.tracing.start({ screenshots: true, snapshots: true, sources: true });

  const bundle = Buffer.from(JSON.stringify({
    bundleVersion: '1.0',
    components: [{
      code: COMPONENT_CODE,
      name: COMPONENT_CODE,
      componentType: 'NORMAL',
      fields: [],
      formulas: [],
    }],
  }));
  await fake('/__plan', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      inspectStatus: 200,
      inspectBody: '{"encrypted":true}',
      decryptStatus: 200,
      decryptBodyBase64: bundle.toString('base64'),
    }),
  });

  const browserRequests: Array<{ url: string; contentType: string }> = [];
  page.on('request', request => {
    if (request.method() !== 'POST' || !request.url().includes(`/component-directories/${directoryId}/import`)) return;
    browserRequests.push({
      url: request.url(),
      contentType: request.headers()['content-type'] || '',
    });
  });

  try {
    await page.reload();
    await expect(page.getByText(DIR_NAME, { exact: false }).first()).toBeVisible();
    const directory = page.locator('.cmm-dir').filter({ hasText: DIR_NAME }).first();
    const importButton = directory.locator('.cmm-dir-acts button')
      .filter({ has: page.locator('.anticon-import') }).first();
    await expect(importButton).toBeVisible();
    await importButton.click();

    const panel = page.locator('.ant-modal, .ant-drawer').filter({ hasText: /导入组件到目录/ }).last();
    await expect(panel).toBeVisible();
    await panel.locator('input[type=file]').first().setInputFiles({
      name: 'T261026-CRYPTO-UI-bundle.json',
      mimeType: 'application/json',
      buffer: CIPHER,
    });

    await choosePolicy(page, panel, /跳过/);
    const previewSkip = page.waitForResponse(response =>
      response.request().method() === 'POST'
      && response.url().includes(`/component-directories/${directoryId}/import`)
      && !response.url().includes('/commit'));
    await panel.getByRole('button', { name: /预\s*览/ }).click();
    await expect.poll(() => browserRequests.length,
      { message: '第一次预览必须发出浏览器请求' }).toBe(1);
    expect(browserRequests[0].contentType,
      '第一次预览必须以 multipart file 发送').toContain('multipart/form-data; boundary=');
    const previewSkipResponse = await previewSkip;
    expect(previewSkipResponse.ok()).toBeTruthy();
    const previewSkipJson = await previewSkipResponse.json();
    expect(previewSkipJson?.data?.conflictPolicy,
      '第一次预览结果必须明确对应 SKIP 策略').toBe('SKIP');
    await expect(panel.getByText(COMPONENT_CODE, { exact: false }).first(),
      '第一次预览必须渲染解密后的非空组件').toBeVisible();

    await choosePolicy(page, panel, /重命名/);
    const previewRename = page.waitForResponse(response =>
      response.request().method() === 'POST'
      && response.url().includes(`/component-directories/${directoryId}/import`)
      && !response.url().includes('/commit'));
    await panel.getByRole('button', { name: /预\s*览/ }).click();
    const previewRenameResponse = await previewRename;
    expect(previewRenameResponse.ok()).toBeTruthy();
    const previewRenameJson = await previewRenameResponse.json();
    expect(previewRenameJson?.data?.conflictPolicy,
      '第二次预览结果必须明确对应 RENAME 策略').toBe('RENAME');
    await expect(panel.getByText(COMPONENT_CODE, { exact: false }).first(),
      '策略改变后的第二次预览必须仍来自同一原始文件').toBeVisible();

    const commit = page.waitForResponse(response =>
      response.request().method() === 'POST'
      && response.url().includes(`/component-directories/${directoryId}/import/commit`));
    await panel.getByRole('button', { name: /确认导入|提\s*交|导\s*入/ }).last().click();
    expect((await commit).ok()).toBeTruthy();

    const committedComponents = await componentsInDirectory(page, directoryId);
    expect(committedComponents.filter((item: any) => item.code === COMPONENT_CODE),
      '两次预览只能产生一次提交，唯一组件 code 必须恰好一条').toHaveLength(1);

    expect(browserRequests, 'preview + re-preview + commit request count').toHaveLength(3);
    expect(browserRequests[0].url).toContain('conflictPolicy=SKIP');
    expect(browserRequests[1].url).toContain('conflictPolicy=RENAME');
    expect(browserRequests[2].url).toContain('/import/commit');
    for (const request of browserRequests) {
      expect(request.contentType).toContain('multipart/form-data; boundary=');
      expect(request.url).not.toContain('/v1/inspect');
      expect(request.url).not.toContain('/v1/decrypt');
    }

    const capture = await fake('/__requests');
    const upstream = capture.requests as FakeCapture[];
    expect(upstream, 'Three browser actions must produce inspect -> decrypt each').toHaveLength(6);
    expect(upstream.map(item => item.path)).toEqual([
      '/v1/inspect', '/v1/decrypt', '/v1/inspect', '/v1/decrypt', '/v1/inspect', '/v1/decrypt',
    ]);
    const cipherHash = createHash('sha256').update(CIPHER).digest('hex');
    for (let index = 0; index < upstream.length; index += 2) {
      expect(upstream[index].size).toBe(CIPHER.length);
      expect(upstream[index + 1].size).toBe(CIPHER.length);
      expect(upstream[index].sha256).toBe(cipherHash);
      expect(upstream[index + 1].sha256).toBe(cipherHash);
      expect(upstream[index].encodedFileName).toBe('T261026-CRYPTO-UI-bundle.json');
      expect(upstream[index + 1].encodedFileName).toBe('T261026-CRYPTO-UI-bundle.json');
      expect(upstream[index].requestId).toBeTruthy();
      expect(upstream[index].requestId).toBe(upstream[index + 1].requestId);
      expect(upstream[index].authorizationMatched).toBeTruthy();
      expect(upstream[index + 1].authorizationMatched).toBeTruthy();
    }

    await page.goto('/dashboard');
    await page.goto('/components');
    await page.reload();
    const reopenedDirectory = page.locator('.cmm-dir').filter({ hasText: DIR_NAME }).first();
    await expect(reopenedDirectory).toBeVisible();
    if (!await reopenedDirectory.evaluate(element => element.classList.contains('open'))) {
      await reopenedDirectory.locator('.cmm-dir-head').click();
    }
    const normalSection = reopenedDirectory.locator('.cmm-sec').filter({ hasText: '页签组件' }).first();
    await expect(normalSection, '私有目录内应有“页签组件”分组').toBeVisible();
    const reopenedComponent = normalSection.locator('.cmm-c-name').filter({ hasText: COMPONENT_CODE }).first();
    if (!await reopenedComponent.isVisible()) {
      await normalSection.locator('.cmm-sec-title').click();
    }
    await expect(reopenedComponent,
      'Commit result must survive drawer close, route switch and refresh').toBeVisible();

    await page.screenshot({ path: path.join(EVIDENCE, 'AC-4-final-component.png'), fullPage: true });
    fs.writeFileSync(path.join(EVIDENCE, 'AC-4-request-summary.json'), JSON.stringify({
      directoryId,
      componentCode: COMPONENT_CODE,
      browserRequests: browserRequests.map(request => ({
        url: request.url,
        contentType: request.contentType,
      })),
      upstream,
    }, null, 2));
  } finally {
    try {
      await deletePrivateFixture(page, directoryId);
    } finally {
      await context.tracing.stop({ path: path.join(EVIDENCE, 'AC-4-trace.zip') });
    }
  }
});
