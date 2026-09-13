"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.REQUEST_TIMEOUT = exports.DEFAULT_PAGE_SIZE = exports.DEV_LOGIN_CODE = exports.TOKEN_KEY = exports.MOCK_DELAY_MS = exports.DEV_CLEAR_TOKEN_ON_LAUNCH = exports.USE_MOCK = exports.BASE_URL = exports.DEVTOOLS_USE_REMOTE = exports.REMOTE_BASE_URL = exports.LOCAL_BASE_URL = void 0;
/**
 * 请求与分页基础配置。
 * ngrok：与终端 Forwarding 左侧公网地址一致（勿末尾斜杠）；重启 ngrok 后域名会变，需同步修改。
 * 本机联调：http://127.0.0.1:9191；真机局域网：http://电脑IP:9191
 */
exports.LOCAL_BASE_URL = 'http://127.0.0.1:9191';
/** 真机 / 体验版联调公网地址（ngrok 等）；重启 ngrok 后只改这一处 */
exports.REMOTE_BASE_URL = 'https://quintuple-paprika-kilt.ngrok-free.dev';
/**
 * 开发者工具是否也走 REMOTE_BASE_URL（ngrok）。
 * 为 true 时：改 ngrok 后开发者工具与真机打同一后端；本机 9191 未起或未重新编译时也能联调。
 */
exports.DEVTOOLS_USE_REMOTE = false;
/** @deprecated 请使用 resolveBaseUrl()；保留兼容旧引用 */
exports.BASE_URL = exports.REMOTE_BASE_URL;
/**
 * 为 true 时：推荐流、关注流、搜索与搜索历史、RAG/AI 搜索、用户信息、未读数、内容详情、评论、点赞/收藏、回答列表等走本地 Mock，
 * 不发起真实 wx.request（避免真机访问 127.0.0.1 失败）。
 * 与后端联调前改为 false，并把 BASE_URL 改为本机局域网 IP（真机不可使用 127.0.0.1）。
 */
exports.USE_MOCK = false;
/**
 * 开发验收：每次冷启动清除本地 JWT，便于测试底部登录弹层。
 * 联调/提审前务必改为 false。
 */
exports.DEV_CLEAR_TOKEN_ON_LAUNCH = false;
/** Mock 请求延迟（毫秒），用于观察骨架屏与加载态 */
exports.MOCK_DELAY_MS = 280;
/** 与后端 Jwt 配置中的请求头名称一致 */
exports.TOKEN_KEY = 'authorization';
/**
 * 本地联调登录 code（对应后端 UserServiceImpl mock：code "test" → test_openid_123456）。
 * 游客 appid 或开发者工具模拟器 mock code 下无法走后端微信 API，登录会回退使用此值。
 */
exports.DEV_LOGIN_CODE = 'test';
exports.DEFAULT_PAGE_SIZE = 10;
exports.REQUEST_TIMEOUT = 15000;
