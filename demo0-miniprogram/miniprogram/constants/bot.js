"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.BOT_NICKNAME = void 0;
exports.BOT_MENTION = void 0;
exports.BOT_NICKNAME = '框框';
exports.BOT_MENTION = `@${exports.BOT_NICKNAME}`;
function hasBotMention(value) {
    return typeof value === 'string' && value.includes(exports.BOT_MENTION);
}
exports.hasBotMention = hasBotMention;
function appendBotMention(value) {
    const draft = typeof value === 'string' ? value : '';
    if (hasBotMention(draft)) {
        return draft;
    }
    const separator = draft.length > 0 && !/\s$/.test(draft) ? ' ' : '';
    return `${draft}${separator}${exports.BOT_MENTION} `;
}
exports.appendBotMention = appendBotMention;
