const test = require('node:test');
const assert = require('node:assert/strict');
const { compileProduction } = require('./production-compiler.cjs');

function installWx(overrides = {}) {
  const storage = new Map();
  global.wx = {
    getStorageSync(key) {
      return storage.get(key);
    },
    setStorageSync(key, value) {
      storage.set(key, value);
    },
    removeStorageSync(key) {
      storage.delete(key);
    },
    getSystemInfoSync() {
      return { platform: 'devtools' };
    },
    pageScrollTo() {},
    showToast() {},
    navigateTo() {},
    base64ToArrayBuffer(value) {
      return Uint8Array.from(Buffer.from(value, 'base64')).buffer;
    },
    getRandomValues({ length, success }) {
      success({ randomValues: new Uint8Array(length).fill(7) });
    },
    ...overrides,
  };
  return storage;
}

function loadHomePage() {
  let definition;
  const previousPage = global.Page;
  global.Page = (page) => {
    definition = page;
    return page;
  };
  const build = compileProduction('miniprogram/pages/home/index.ts');
  require(build.entry);
  if (previousPage === undefined) {
    delete global.Page;
  } else {
    global.Page = previousPage;
  }
  assert.ok(definition, 'home page must register through Page()');
  const instance = Object.create(definition);
  instance.data = {
    ...definition.data,
    list: [...definition.data.list],
  };
  instance.setData = (patch) => Object.assign(instance.data, patch);
  return { build, definition, instance };
}

test('guest id is persisted and reused across recommendation requests', async () => {
  installWx();
  const build = compileProduction('miniprogram/utils/recommend-visitor.ts');
  test.after(() => build.cleanup());
  const entry = require(build.entry);
  const first = await entry.getRecommendGuestId();
  const second = await entry.getRecommendGuestId();
  assert.match(first, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
  assert.equal(second, first);
});

test('only a card at the visible threshold is reported once', async () => {
  installWx();
  const build = compileProduction('miniprogram/utils/recommend-exposure.ts');
  test.after(() => build.cleanup());
  const entry = require(build.entry);
  const batches = [];
  const queue = entry.createExposureQueue(async (batch) => {
    batches.push(batch);
  });
  queue.observe({ sessionId: 's1', actorKey: 'g1', contentId: 101, ratio: 0.49 });
  await queue.flush();
  assert.equal(batches.length, 0);
  queue.observe({ sessionId: 's1', actorKey: 'g1', contentId: 101, ratio: 0.5 });
  await queue.flush();
  queue.observe({ sessionId: 's1', actorKey: 'g1', contentId: 101, ratio: 0.8 });
  await queue.flush();
  assert.deepEqual(batches, [{ feedSessionId: 's1', contentIds: [101] }]);
});

test('recommendation query carries a stable session and opaque cursor', async () => {
  const build = compileProduction('miniprogram/services/content.service.ts');
  test.after(() => build.cleanup());
  const entry = require(build.entry);
  const query = entry.buildRecommendQuery('all', 'cursor-2', 0, 5, 'recommend', {
    feedSessionId: 'session-1',
  });
  assert.deepEqual(query, {
    pageSize: 5,
    offset: 0,
    scene: 'recommend',
    feedSessionId: 'session-1',
    pageCursor: 'cursor-2',
  });
});

test('identity change binds the new visitor before reloading the recommendation session', async () => {
  const guestId = '11111111-1111-4111-8111-111111111111';
  const storage = installWx();
  storage.set('RECOMMEND_GUEST_ID', guestId);
  storage.set('authorization', 'valid-token');
  storage.set('LOGIN_USER_ID', 42);
  const { build, definition, instance } = loadHomePage();
  try {
    instance._recommendVisitor = { guestId, actorKey: `g:${guestId}` };
    const reloads = [];
    instance.loadFirstPage = async (options) => {
      reloads.push(options);
    };

    await definition.checkRecommendationIdentity.call(instance);

    assert.equal(instance._recommendVisitor.actorKey, 'u:42');
    assert.deepEqual(reloads, [{ newRecommendationSession: true }]);
  } finally {
    build.cleanup();
  }
});

test('switching into hot starts the 45 second silent refresh timer and switching back stops it', () => {
  installWx();
  const previousSetInterval = global.setInterval;
  const previousClearInterval = global.clearInterval;
  const activeTimers = [];
  global.setInterval = (callback, intervalMs) => {
    const timer = { callback, intervalMs };
    activeTimers.push(timer);
    return timer;
  };
  global.clearInterval = (timer) => {
    const index = activeTimers.indexOf(timer);
    if (index >= 0) {
      activeTimers.splice(index, 1);
    }
  };

  const { build, instance } = loadHomePage();
  try {
    instance.loadFirstPage = async () => {};

    instance.resetFeedAndReload({ scene: 'hot' });
    assert.equal(instance.data.scene, 'hot');
    assert.equal(activeTimers.length, 1);
    assert.equal(activeTimers[0].intervalMs, 45_000);

    instance.resetFeedAndReload({ scene: 'recommend' });
    assert.equal(activeTimers.length, 0);
  } finally {
    instance.onUnload();
    build.cleanup();
    global.setInterval = previousSetInterval;
    global.clearInterval = previousClearInterval;
  }
});

test('stale hot silent refresh cannot write after scene switch or page hide', async () => {
  const requestSuccessCallbacks = [];
  installWx({
    request(options) {
      requestSuccessCallbacks.push(options.success);
    },
  });
  const { build, definition, instance } = loadHomePage();
  try {
    const initialCard = { contentId: 1, title: '当前内容' };
    instance.data.scene = 'hot';
    instance.data.filter = 'all';
    instance.data.loading = false;
    instance.data.list = [initialCard];
    instance.loadFirstPage = async () => {};

    const staleHotResponse = {
      statusCode: 200,
      data: {
        code: 200,
        msg: 'success',
        data: {
          list: [{
            contentId: 999,
            contentType: 1,
            title: '旧热度响应',
            content: 'stale',
            publishUserId: 1,
            nickName: '用户',
            avatarUrl: '',
            liked: 0,
            commentCount: 0,
            collectCount: 0,
          }],
          hasMore: false,
          minScore: null,
          offset: 0,
        },
      },
    };

    const switchingRefresh = definition.silentRefresh.call(instance);
    assert.equal(requestSuccessCallbacks.length, 1);
    instance.resetFeedAndReload({ scene: 'recommend', filter: 'life' });
    requestSuccessCallbacks[0](staleHotResponse);
    await switchingRefresh;
    assert.deepEqual(instance.data.list, [initialCard]);

    instance.data.scene = 'hot';
    instance.data.filter = 'all';
    instance.data.loading = false;
    instance.data.switching = false;
    instance.data.refreshing = false;
    instance.data.loadingMore = false;
    const hiddenRefresh = definition.silentRefresh.call(instance);
    assert.equal(requestSuccessCallbacks.length, 2);
    definition.onHide.call(instance);
    requestSuccessCallbacks[1](staleHotResponse);
    await hiddenRefresh;
    assert.deepEqual(instance.data.list, [initialCard]);
  } finally {
    definition.onUnload.call(instance);
    build.cleanup();
  }
});

test('same-actor session refresh flushes old exposure, while login change discards it', async () => {
  const guestId = '22222222-2222-4222-8222-222222222222';
  const requests = [];
  const storage = installWx({
    request(options) {
      requests.push(options);
    },
  });
  storage.set('RECOMMEND_GUEST_ID', guestId);
  const { build, definition, instance } = loadHomePage();
  try {
    const visitor = { guestId, actorKey: `g:${guestId}` };
    instance._recommendVisitor = visitor;
    instance.loadFirstPage = async () => {};
    instance.createRecommendationExposureQueue(visitor);
    instance._exposureQueue.observe({
      sessionId: 'old-session',
      actorKey: visitor.actorKey,
      contentId: 101,
      ratio: 0.5,
    });

    definition.resetFeedAndReload.call(instance, { scene: 'recommend', filter: 'all' });
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(requests.length, 1);
    assert.equal(requests[0].data.feedSessionId, 'old-session');
    assert.deepEqual(requests[0].data.contentIds, [101]);
    assert.equal(requests[0].header['X-Guest-Id'], guestId);
    requests[0].success({
      statusCode: 200,
      data: { code: 200, msg: 'success', data: null },
    });
    await new Promise((resolve) => setImmediate(resolve));

    instance.createRecommendationExposureQueue(visitor);
    instance._exposureQueue.observe({
      sessionId: 'guest-session',
      actorKey: visitor.actorKey,
      contentId: 102,
      ratio: 0.5,
    });
    storage.set('authorization', 'valid-token');
    storage.set('LOGIN_USER_ID', 42);
    await definition.checkRecommendationIdentity.call(instance);

    assert.equal(requests.length, 1);
    assert.equal(instance._recommendVisitor.actorKey, 'u:42');
    assert.equal(instance._exposureQueue, undefined);
  } finally {
    definition.onUnload.call(instance);
    build.cleanup();
  }
});

test('same-actor refresh drains every old exposure batch before rebuilding the session', async () => {
  const guestId = '33333333-3333-4333-8333-333333333333';
  const requests = [];
  const storage = installWx({
    request(options) {
      requests.push(options);
    },
  });
  storage.set('RECOMMEND_GUEST_ID', guestId);
  const { build, definition, instance } = loadHomePage();
  try {
    const visitor = { guestId, actorKey: `g:${guestId}` };
    instance._recommendVisitor = visitor;
    instance.data.scene = 'recommend';
    instance.data.filter = 'all';
    instance.data.loading = false;
    instance.data.switching = false;
    instance.data.refreshing = false;
    instance.data.loadingMore = false;
    instance.startExposureObserver = () => {};
    instance.createRecommendationExposureQueue(visitor);

    for (let contentId = 1; contentId <= 21; contentId += 1) {
      instance._exposureQueue.observe({
        sessionId: 'old-session',
        actorKey: visitor.actorKey,
        contentId,
        ratio: 0.5,
      });
    }
    const firstFlush = instance._exposureQueue.flush();
    assert.strictEqual(instance._exposureQueue.flush(), firstFlush);

    const reload = definition.loadFirstPage.call(instance, {
      newRecommendationSession: true,
    });
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual(requests.map((request) => request.method), ['POST']);
    assert.deepEqual(requests[0].data.contentIds, Array.from({ length: 20 }, (_, i) => i + 1));

    requests[0].success({
      statusCode: 200,
      data: { code: 200, msg: 'success', data: null },
    });
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual(requests.map((request) => request.method), ['POST', 'POST']);
    assert.deepEqual(requests[1].data.contentIds, [21]);

    requests[1].success({
      statusCode: 200,
      data: { code: 200, msg: 'success', data: null },
    });
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual(requests.map((request) => request.method), ['POST', 'POST', 'GET']);
    assert.notEqual(requests[2].data.feedSessionId, 'old-session');
    assert.equal(requests[2].data.scene, 'recommend');

    requests[2].success({
      statusCode: 200,
      data: {
        code: 200,
        msg: 'success',
        data: {
          list: [],
          hasMore: false,
          minScore: null,
          offset: 0,
          feedSessionId: requests[2].data.feedSessionId,
          recommendationState: 'EXHAUSTED',
          canRevisit: true,
        },
      },
    });
    await reload;
  } finally {
    definition.onUnload.call(instance);
    build.cleanup();
  }
});
