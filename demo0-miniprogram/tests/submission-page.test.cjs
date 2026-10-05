const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { compileProduction } = require('./production-compiler.cjs');

function makeWx() {
  const store = new Map();
  const requests = [];
  let randomCalls = 0;
  const wx = {
    getStorageSync(key) {
      return store.get(key);
    },
    setStorageSync(key, value) {
      store.set(key, value);
    },
    removeStorageSync(key) {
      store.delete(key);
    },
    getRandomValues(request) {
      randomCalls += 1;
      request.success({ randomValues: new Uint8Array(Array.from({ length: request.length }, (_, i) => i)).buffer });
    },
    getSystemInfoSync() {
      return { platform: 'devtools' };
    },
    showToast() {},
    redirectTo() {},
    navigateBack() {},
    request(options) {
      requests.push(options);
      if (options.url.includes('/submission/status')) {
        return;
      }
      options.success({ statusCode: 200, data: { code: 200, data: { contentId: 9 } } });
    },
  };
  return { wx, store, requests, getRandomCalls: () => randomCalls };
}

function makePage(config, data) {
  return Object.assign(Object.create(config), {
    data: { ...config.data, ...data },
    setData(update) {
      this.data = { ...this.data, ...update };
    },
  });
}

function loadPublishPage() {
  const build = compileProduction('miniprogram/pages/publish/index.ts');
  const pagePath = path.resolve(build.entry);
  let pageConfig;
  global.Page = (config) => {
    pageConfig = config;
  };
  delete require.cache[require.resolve(pagePath)];
  require(pagePath);
  return {
    build,
    pageConfig,
    submission: require(path.resolve(path.dirname(pagePath), '../../utils/submission.js')),
  };
}

function loadDetailLifePage() {
  const build = compileProduction('miniprogram/pages/detail-life/index.ts');
  const pagePath = path.resolve(build.entry);
  let pageConfig;
  global.Page = (config) => {
    pageConfig = config;
  };
  const realNamePath = path.resolve(path.dirname(pagePath), '../../services/real-name.service.js');
  const realName = require(realNamePath);
  realName.ensureRealNameVerified = async () => true;
  delete require.cache[require.resolve(pagePath)];
  require(pagePath);
  return {
    build,
    pageConfig,
    submission: require(path.resolve(path.dirname(pagePath), '../../utils/submission.js')),
  };
}

test('a stale restore GET cannot repaint a publish page after the record was completed', async () => {
  const previousWx = global.wx;
  const previousPage = global.Page;
  const previousSetTimeout = global.setTimeout;
  const runtime = makeWx();
  global.wx = runtime.wx;
  global.setTimeout = (callback) => {
    callback();
    return 0;
  };
  try {
    const loaded = loadPublishPage();
    runtime.store.set('LOGIN_USER_ID', 42);
    const prepared = await loaded.submission.prepareSubmission(
      'content-publish',
      'publish',
      { contentType: 1, title: '标题', content: '正文', images: [] },
    );
    assert.equal(prepared.ok, true);
    const page = makePage(loaded.pageConfig, { pendingSubmissionMessage: '' });
    const restore = loaded.pageConfig.restorePendingSubmission.call(page);
    await Promise.resolve();
    assert.equal(runtime.requests.length, 1);

    loaded.pageConfig.completeSubmission.call(page, { contentId: 9 }, prepared.data);
    runtime.requests[0].success({
      statusCode: 200,
      data: { code: 200, data: { status: 'UNCONFIRMED', scene: 'content-publish' } },
    });
    await restore;

    assert.equal(page._pendingSubmission, null);
    assert.equal(page.data.pendingSubmissionMessage, '');
    assert.equal(loaded.submission.getPendingSubmission('content-publish', 'publish'), undefined);
    loaded.build.cleanup();
  } finally {
    global.wx = previousWx;
    global.Page = previousPage;
    global.setTimeout = previousSetTimeout;
  }
});

test('a successful publish enters a terminal state before delayed navigation and ignores a second click', async () => {
  const previousWx = global.wx;
  const previousPage = global.Page;
  const previousSetTimeout = global.setTimeout;
  const runtime = makeWx();
  global.wx = runtime.wx;
  global.setTimeout = (callback) => {
    callback();
    return 0;
  };
  try {
    const loaded = loadPublishPage();
    runtime.store.set('LOGIN_USER_ID', 42);
    const prepared = await loaded.submission.prepareSubmission(
      'content-publish',
      'publish',
      { contentType: 1, title: '标题', content: '正文', images: [] },
    );
    assert.equal(prepared.ok, true);
    const page = makePage(loaded.pageConfig, {
      contentType: 1,
      title: '标题',
      content: '正文',
      images: [],
    });
    page._pendingSubmission = prepared.data;

    await loaded.pageConfig.onSubmit.call(page);
    await loaded.pageConfig.onSubmit.call(page);

    assert.equal(runtime.requests.length, 1);
    assert.equal(runtime.requests[0].url.includes('/content/publish'), true);
    assert.equal(runtime.getRandomCalls(), 1);
    assert.equal(page._submissionCompleted, true);
    loaded.build.cleanup();
  } finally {
    global.wx = previousWx;
    global.Page = previousPage;
    global.setTimeout = previousSetTimeout;
  }
});

test('restored reply retries with the frozen token and reconstructed parent target', async () => {
  const previousWx = global.wx;
  const previousPage = global.Page;
  const previousSetTimeout = global.setTimeout;
  const runtime = makeWx();
  global.wx = runtime.wx;
  global.setTimeout = (callback) => {
    callback();
    return 0;
  };
  try {
    const loaded = loadDetailLifePage();
    runtime.store.set('LOGIN_USER_ID', 42);
    const payload = {
      contentId: 7,
      content: '原回复',
      parentId: 11,
      replyCommentId: 12,
      replyUserId: 99,
    };
    const contextKey = loaded.pageConfig.commentSubmissionContextKey(7, payload);
    const prepared = await loaded.submission.prepareSubmission('comment-send', contextKey, payload);
    assert.equal(prepared.ok, true);
    const page = makePage(loaded.pageConfig, {
      contentIdNum: 7,
      comments: [{ commentId: 11, replies: [{ commentId: 12, nickName: '原用户' }] }],
    });
    const restore = loaded.pageConfig.restorePendingSubmission.call(page);
    await Promise.resolve();
    runtime.requests[0].success({
      statusCode: 200,
      data: { code: 200, data: { status: 'UNCONFIRMED', scene: 'comment-send' } },
    });
    await restore;

    assert.deepEqual(page._replyTarget, { parentId: 11, replyCommentId: 12, replyUserId: 99 });
    assert.equal(page.data.replyTargetNick, '原用户');
    assert.equal(page.data.composerValue, '原回复');
    const targetToken = prepared.data.token;

    await loaded.pageConfig.onComposerSubmit.call(page, {
      detail: { content: '原回复', mentionBot: false },
    });
    const post = runtime.requests.find((request) => request.url.includes('/comment/send'));
    assert.ok(post);
    assert.equal(post.header['Idempotency-Key'], targetToken);
    assert.equal(post.data.parentId, 11);
    assert.equal(post.data.replyCommentId, 12);
    assert.equal(post.data.replyUserId, 99);
    assert.equal(loaded.submission.getPendingSubmission('comment-send', contextKey), undefined);
    loaded.build.cleanup();
  } finally {
    global.wx = previousWx;
    global.Page = previousPage;
    global.setTimeout = previousSetTimeout;
  }
});
