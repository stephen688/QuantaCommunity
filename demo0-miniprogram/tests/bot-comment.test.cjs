const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const {
  BOT_MENTION,
  appendBotMention,
  hasBotMention,
} = require('../miniprogram/constants/bot.js');
const {
  mapCommentRowToItem,
  sendComment,
} = require('../miniprogram/services/comment.service.js');

test('appendBotMention inserts one structured mention and preserves draft', () => {
  assert.equal(BOT_MENTION, '@框框');
  assert.equal(appendBotMention(''), '@框框 ');
  assert.equal(appendBotMention('帮我看看'), '帮我看看 @框框 ');
  assert.equal(appendBotMention('已有 @框框 了'), '已有 @框框 了');
});

test('hasBotMention follows submitted text after user edits', () => {
  assert.equal(hasBotMention('@框框 你好'), true);
  assert.equal(hasBotMention('@框 你好'), false);
  assert.equal(hasBotMention('普通评论'), false);
});

test('mapCommentRowToItem preserves bot identity for top-level and nested replies', () => {
  const item = mapCommentRowToItem({
    commentId: 1,
    userId: 10000,
    nickName: '框框',
    isBot: true,
    replyList: [{
      commentId: 2,
      userId: 10000,
      nickName: '框框',
      isBot: true,
    }],
  });

  assert.equal(item.isBot, true);
  assert.equal(item.replies[0].isBot, true);
});

test('sendComment forwards mentionBot only when explicitly true', async () => {
  const previousWx = global.wx;
  const requests = [];
  global.wx = {
    getStorageSync: () => '',
    request(options) {
      requests.push(options);
      options.success({ statusCode: 200, data: { code: 200, data: 1 } });
    },
  };

  try {
    await sendComment({ contentId: 1, content: '@框框 你好', mentionBot: true });
    await sendComment({ contentId: 1, content: '普通评论', mentionBot: false });
  } finally {
    global.wx = previousWx;
  }

  assert.equal(requests[0].data.mentionBot, true);
  assert.equal(Object.hasOwn(requests[1].data, 'mentionBot'), false);
});

test('comment composer button appends one mention and emits the updated input', () => {
  let componentConfig;
  const previousComponent = global.Component;
  global.Component = (config) => {
    componentConfig = config;
  };
  delete require.cache[require.resolve('../miniprogram/components/comment-composer/index.js')];
  require('../miniprogram/components/comment-composer/index.js');
  global.Component = previousComponent;

  const events = [];
  const instance = {
    data: { draft: '帮我看看', submitting: false, disabled: false },
    setData(update) {
      this.data = { ...this.data, ...update };
    },
    syncHint() {},
    triggerEvent(name, detail) {
      events.push({ name, detail });
    },
  };

  componentConfig.methods.onMentionBotTap.call(instance);

  assert.equal(instance.data.draft, '帮我看看 @框框 ');
  assert.deepEqual(events, [{ name: 'input', detail: { value: '帮我看看 @框框 ' } }]);
});

test('comment composer submit derives mentionBot from the current draft', () => {
  let componentConfig;
  const previousComponent = global.Component;
  global.Component = (config) => {
    componentConfig = config;
  };
  delete require.cache[require.resolve('../miniprogram/components/comment-composer/index.js')];
  require('../miniprogram/components/comment-composer/index.js');
  global.Component = previousComponent;

  const events = [];
  const instance = {
    data: { draft: '@框框 你好', submitting: false, disabled: false },
    triggerEvent(name, detail) {
      events.push({ name, detail });
    },
  };

  componentConfig.methods.onSubmitTap.call(instance);

  assert.deepEqual(events, [{
    name: 'submit',
    detail: { content: '@框框 你好', mentionBot: true },
  }]);
});

test('comment composer renders a dedicated bot mention action beside the hint', () => {
  const wxml = fs.readFileSync(
    path.join(__dirname, '../miniprogram/components/comment-composer/index.wxml'),
    'utf8',
  );
  const wxss = fs.readFileSync(
    path.join(__dirname, '../miniprogram/components/comment-composer/index.wxss'),
    'utf8',
  );

  assert.match(wxml, /class="cc__tools"/);
  assert.match(wxml, /bindtap="onMentionBotTap"/);
  assert.match(wxml, />@框框<\/button>/);
  assert.match(wxss, /\.cc__mention/);
});

test('comment list renders AI badges from isBot for both comment depths', () => {
  const wxml = fs.readFileSync(
    path.join(__dirname, '../miniprogram/components/comment-list/index.wxml'),
    'utf8',
  );
  const wxss = fs.readFileSync(
    path.join(__dirname, '../miniprogram/components/comment-list/index.wxss'),
    'utf8',
  );
  const runtimeJs = fs.readFileSync(
    path.join(__dirname, '../miniprogram/components/comment-list/index.js'),
    'utf8',
  );

  assert.equal((wxml.match(/class="cl__ai-badge"/g) || []).length, 2);
  assert.equal((wxml.match(/wx:if="\{\{item\.isBot\}\}"/g) || []).length, 2);
  assert.match(wxss, /\.cl__ai-badge/);
  assert.match(runtimeJs, /isBot: it\.isBot === true/);
});

test('life and answer detail pages forward the composer mention flag', () => {
  for (const page of ['detail-life', 'answer-detail']) {
    const ts = fs.readFileSync(
      path.join(__dirname, `../miniprogram/pages/${page}/index.ts`),
      'utf8',
    );
    const runtimeJs = fs.readFileSync(
      path.join(__dirname, `../miniprogram/pages/${page}/index.js`),
      'utf8',
    );

    assert.match(ts, /mentionBot\?: boolean/);
    assert.match(ts, /mentionBot: e\.detail\?\.mentionBot === true/);
    assert.match(runtimeJs, /mentionBot: e\.detail\?\.mentionBot === true/);
  }
});
