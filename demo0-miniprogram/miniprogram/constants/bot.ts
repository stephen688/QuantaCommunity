/**
 * Bot 用户可见身份常量与评论编辑器的 mention 规则。
 * 本模块只负责稳定的展示/输入规则，不负责请求发送或 bot 业务判断。
 */
export const BOT_NICKNAME = '框框';
export const BOT_MENTION = `@${BOT_NICKNAME}`;

export function hasBotMention(value: string): boolean {
  return typeof value === 'string' && value.includes(BOT_MENTION);
}

export function appendBotMention(value: string): string {
  const draft = typeof value === 'string' ? value : '';
  if (hasBotMention(draft)) {
    return draft;
  }
  const separator = draft.length > 0 && !/\s$/.test(draft) ? ' ' : '';
  return `${draft}${separator}${BOT_MENTION} `;
}
