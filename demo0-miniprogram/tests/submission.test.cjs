const test = require('node:test');
const assert = require('node:assert/strict');
const { compileProduction } = require('./production-compiler.cjs');

function makeWx(options = {}) {
  const store = new Map();
  const requests = [];
  const randomBytes = options.randomBytes || Array.from({ length: 16 }, (_, i) => i);
  const wx = {
    requests,
    getStorageSync(key) {
      if (options.readStorageError) {
        throw new Error('storage read failed');
      }
      return store.get(key);
    },
    setStorageSync(key, value) {
      if (options.writeStorageError) {
        throw new Error('storage write failed');
      }
      store.set(key, value);
    },
    removeStorageSync(key) {
      if (options.writeStorageError) {
        throw new Error('storage write failed');
      }
      store.delete(key);
    },
    getRandomValues(request) {
      if (options.randomError) {
        request.fail?.({ errMsg: 'getRandomValues:fail' });
        return;
      }
      const bytes = new Uint8Array(randomBytes.slice(0, request.length));
      request.success?.({ randomValues: bytes.buffer });
    },
    getSystemInfoSync() {
      return { platform: 'devtools' };
    },
    showToast() {},
    navigateTo() {},
    request(request) {
      requests.push(request);
      const response = options.response || { statusCode: 200, data: { code: 200, data: { ok: true } } };
      request.success?.(response);
    },
  };
  return { wx, store, requests };
}

function withWx(options, callback) {
  const previous = global.wx;
  const { wx, store, requests } = makeWx(options);
  global.wx = wx;
  return Promise.resolve()
    .then(() => callback({ store, requests }))
    .finally(() => {
      global.wx = previous;
    });
}

test('UUID v4 uses wx secure bytes and a failed local write prevents a submission', async () => {
  const compiled = compileProduction('miniprogram/utils/submission.ts');
  try {
    await withWx({
      randomBytes: [0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff],
      writeStorageError: true,
    }, async () => {
      global.wx.setStorageSync = () => {
        throw new Error('storage write failed');
      };
      global.wx.getStorageSync = (key) => (key === 'LOGIN_USER_ID' ? 42 : undefined);
      const submission = require(compiled.entry);
      const failed = await submission.prepareSubmission('content-publish', 'publish', { title: '一条帖子' });
      assert.equal(failed.ok, false);
      assert.equal(failed.errorType, 'storage');
    });
  } finally {
    compiled.cleanup();
  }
});

test('a pending submission freezes its payload and reuses its token for the same account and context', async () => {
  const compiled = compileProduction('miniprogram/utils/submission.ts');
  try {
    await withWx({
      randomBytes: [0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff],
    }, async ({ store }) => {
      store.set('LOGIN_USER_ID', 42);
      const submission = require(compiled.entry);
      const first = await submission.prepareSubmission('comment-send', 'comment:7:root', { contentId: 7, content: '原文' });
      const second = await submission.prepareSubmission('comment-send', 'comment:7:root', { contentId: 7, content: '改过的文字' });

      assert.equal(first.ok, true);
      assert.equal(second.ok, true);
      assert.equal(first.data.token, '00112233-4455-4677-8899-aabbccddeeff');
      assert.equal(second.data.token, first.data.token);
      assert.deepEqual(second.data.payload, { contentId: 7, content: '原文' });
      assert.equal(submission.isSubmissionExpired(first.data, Date.now()), false);
    });
  } finally {
    compiled.cleanup();
  }
});

test('success cleanup makes the next operation use a new token and account/context records stay isolated', async () => {
  const compiled = compileProduction('miniprogram/utils/submission.ts');
  try {
    await withWx({ randomBytes: Array.from({ length: 16 }, (_, i) => i + 1) }, async ({ store }) => {
      store.set('LOGIN_USER_ID', 42);
      const submission = require(compiled.entry);
      const first = await submission.prepareSubmission('answer-publish', 'question:9', { questionId: 9, content: '回答' });
      assert.equal(first.ok, true);
      assert.equal(submission.clearPendingSubmission(first.data), true);
      global.wx.getRandomValues = (request) => {
        request.success({ randomValues: new Uint8Array(Array.from({ length: 16 }, (_, i) => i + 32)).buffer });
      };
      const next = await submission.prepareSubmission('answer-publish', 'question:9', { questionId: 9, content: '第二次回答' });
      assert.equal(next.ok, true);
      assert.notEqual(next.data.token, first.data.token);

      store.set('LOGIN_USER_ID', 43);
      const otherAccount = await submission.prepareSubmission('answer-publish', 'question:9', { questionId: 9, content: '另一个用户' });
      assert.equal(otherAccount.ok, true);
      assert.deepEqual(submission.listPendingSubmissions('answer-publish').map((record) => record.payload.content), ['另一个用户']);

      const otherContext = await submission.prepareSubmission('answer-publish', 'question:10', { questionId: 10, content: '另一个问题' });
      assert.equal(otherContext.ok, true);
      assert.equal(submission.listPendingSubmissions('answer-publish').length, 2);
    });
  } finally {
    compiled.cleanup();
  }
});

test('an async UUID generation rechecks the store, account, and expired context before writing', async () => {
  const compiled = compileProduction('miniprogram/utils/submission.ts');
  try {
    await withWx({}, async ({ store }) => {
      store.set('LOGIN_USER_ID', 42);
      const submission = require(compiled.entry);
      const pendingRandom = [];
      global.wx.getRandomValues = (request) => pendingRandom.push(request);

      const first = submission.prepareSubmission('comment-send', 'comment:1', { contentId: 1, content: '一' });
      const second = submission.prepareSubmission('comment-send', 'comment:2', { contentId: 2, content: '二' });
      pendingRandom[1].success({ randomValues: new Uint8Array(16).buffer });
      const secondResult = await second;
      pendingRandom[0].success({ randomValues: new Uint8Array(16).buffer });
      const firstResult = await first;
      assert.equal(secondResult.ok, true);
      assert.equal(firstResult.ok, true);
      assert.equal(submission.listPendingSubmissions('comment-send').length, 2);

      const switched = submission.prepareSubmission('comment-send', 'comment:3', { contentId: 3, content: '三' });
      store.set('LOGIN_USER_ID', 43);
      pendingRandom[2].success({ randomValues: new Uint8Array(16).buffer });
      const switchedResult = await switched;
      assert.equal(switchedResult.ok, false);
      assert.equal(switchedResult.errorType, 'accountChanged');
      assert.equal(submission.clearPendingSubmission(firstResult.data), false);

      store.set('LOGIN_USER_ID', 42);
      global.wx.getRandomValues = (request) => {
        request.success({ randomValues: new Uint8Array(Array.from({ length: 16 }, (_, i) => i + 64)).buffer });
      };
      const expired = await submission.prepareSubmission(
        'comment-send',
        'comment:4',
        { contentId: 4, content: '四' },
        100,
      );
      assert.equal(expired.ok, true);
      const stopped = await submission.prepareSubmission(
        'comment-send',
        'comment:4',
        { contentId: 4, content: '四' },
        100 + 7 * 24 * 60 * 60 * 1000,
      );
      assert.equal(stopped.ok, false);
      assert.equal(stopped.errorType, 'expired');
    });
  } finally {
    compiled.cleanup();
  }
});

test('submission status query sends the token as a header and never as a query parameter', async () => {
  const compiled = compileProduction('miniprogram/services/submission.service.ts');
  try {
    await withWx({ response: { statusCode: 200, data: { code: 200, data: { status: 'UNCONFIRMED', scene: 'comment-send' } } } }, async ({ requests }) => {
      const service = require(compiled.entry);
      const result = await service.querySubmissionStatus('comment-send', '00112233-4455-4677-8899-aabbccddeeff');
      assert.equal(result.ok, true);
      assert.equal(result.data.status, 'UNCONFIRMED');
      assert.equal(requests.length, 1);
      assert.equal(requests[0].url.endsWith('/submission/status?scene=comment-send'), true);
      assert.equal(requests[0].header['Idempotency-Key'], '00112233-4455-4677-8899-aabbccddeeff');
      assert.equal(requests[0].url.includes('00112233'), false);
    });
  } finally {
    compiled.cleanup();
  }
});

test('the three write services forward the same explicit Idempotency-Key', async () => {
  const compiled = [
    compileProduction('miniprogram/services/content.service.ts'),
    compileProduction('miniprogram/services/answer.service.ts'),
    compileProduction('miniprogram/services/comment.service.ts'),
  ];
  try {
    await withWx({}, async ({ requests }) => {
      const token = '00112233-4455-4677-8899-aabbccddeeff';
      await require(compiled[0].entry).publishContent({ contentType: 1, title: '标题', content: '正文', images: [] }, token);
      await require(compiled[1].entry).publishAnswer({ questionId: 9, content: '回答' }, token);
      await require(compiled[2].entry).sendComment({ contentId: 7, content: '评论' }, token);
      assert.equal(requests.length, 3);
      assert.deepEqual(requests.map((request) => request.header['Idempotency-Key']), [token, token, token]);
    });
  } finally {
    for (const item of compiled) {
      item.cleanup();
    }
  }
});

test('request classifies the three idempotency conflicts and preserves Retry-After', async () => {
  const compiled = compileProduction('miniprogram/utils/request.ts');
  try {
    const cases = [
      ['提交凭证与内容不一致', 'submissionConflict'],
      ['提交结果尚未确认，请稍后查询', 'submissionPending'],
      ['提交凭证已过期，请先核对发布记录', 'submissionExpired'],
    ];
    for (const [message, errorType] of cases) {
      await withWx({
        response: { statusCode: 409, header: { 'Retry-After': '2' }, data: { code: 409, msg: message } },
      }, async () => {
        delete require.cache[require.resolve(compiled.entry)];
        const requestModule = require(compiled.entry);
        const result = await requestModule.request({ method: 'POST', url: '/comment/send', data: {} });
        assert.equal(result.ok, false);
        assert.equal(result.errorType, errorType);
        assert.equal(result.businessCode, 409);
        assert.equal(result.retryAfter, 2);
      });
    }
    for (const statusCode of [400, 409]) {
      await withWx({
        response: { statusCode, data: { code: statusCode, msg: '提交结果尚未确认，请稍后查询' } },
      }, async () => {
        delete require.cache[require.resolve(compiled.entry)];
        const requestModule = require(compiled.entry);
        const result = await requestModule.request({ method: 'POST', url: '/comment/report', data: {} });
        assert.equal(result.ok, false);
        assert.equal(result.errorType, 'network');
        assert.equal(result.businessCode, undefined);
      });
    }
  } finally {
    compiled.cleanup();
  }
});

test('the shared send guard blocks an expired or switched-account pending submission', async () => {
  const compiled = compileProduction('miniprogram/utils/submission.ts');
  try {
    await withWx({}, async ({ store }) => {
      store.set('LOGIN_USER_ID', 42);
      const submission = require(compiled.entry);
      const prepared = await submission.prepareSubmission(
        'comment-send',
        'comment:7:root',
        { contentId: 7, content: '冻结评论' },
        100,
      );
      assert.equal(prepared.ok, true);
      assert.deepEqual(submission.validatePendingSubmissionForSend(prepared.data, 100), { ok: true });
      assert.equal(
        submission.validatePendingSubmissionForSend(
          prepared.data,
          100 + submission.SUBMISSION_RETENTION_MS,
        ).errorType,
        'expired',
      );
      assert.equal(submission.getPendingSubmission('comment-send', 'comment:7:root'), undefined);
      store.set('LOGIN_USER_ID', 43);
      assert.equal(submission.validatePendingSubmissionForSend(prepared.data, 100).errorType, 'accountChanged');
    });
  } finally {
    compiled.cleanup();
  }
});

test('capacity cleanup removes expired records while preserving active unknown submissions', async () => {
  const compiled = compileProduction('miniprogram/utils/submission.ts');
  try {
    await withWx({ randomBytes: Array.from({ length: 16 }, (_, i) => i + 1) }, async ({ store }) => {
      const userId = 42;
      const now = 1_000 + 7 * 24 * 60 * 60 * 1000 - 1;
      store.set('LOGIN_USER_ID', userId);
      store.set('HTTP_SUBMISSION_PENDING_V1', {
        [userId]: [
          {
            token: 'expired-token',
            scene: 'comment-send',
            payload: { contentId: 1, content: '已过期' },
            contextKey: 'comment:expired',
            createdAt: 0,
            ownerUserId: userId,
          },
          ...Array.from({ length: 19 }, (_, index) => ({
            token: `active-${index}`,
            scene: 'comment-send',
            payload: { contentId: index + 2, content: `未确认${index}` },
            contextKey: `comment:active:${index}`,
            createdAt: 1_000,
            ownerUserId: userId,
          })),
        ],
      });
      const submission = require(compiled.entry);
      const result = await submission.prepareSubmission(
        'comment-send',
        'comment:new',
        { contentId: 99, content: '新评论' },
        now,
      );
      assert.equal(result.ok, true);
      const records = submission.listPendingSubmissions('comment-send');
      assert.equal(records.length, 20);
      assert.equal(records.some((record) => record.token === 'expired-token'), false);
      assert.equal(records.filter((record) => record.token.startsWith('active-')).length, 19);
    });
  } finally {
    compiled.cleanup();
  }
});
