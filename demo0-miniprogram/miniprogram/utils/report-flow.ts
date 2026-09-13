/**
 * 与后端 ContentReportDTO / CommentReportDTO 的 reportType 一致（1–5）。
 */
export const REPORT_TYPE_LABELS: string[] = [
  '垃圾广告',
  '人身攻击',
  '违规内容',
  '虚假信息',
  '其他',
];

/** 用户选择举报类型；取消时 resolve null */
export function pickReportType(): Promise<number | null> {
  return new Promise((resolve) => {
    wx.showActionSheet({
      itemList: REPORT_TYPE_LABELS,
      success: (res) => {
        const t = res.tapIndex + 1;
        resolve(t >= 1 && t <= 5 ? t : null);
      },
      fail: () => resolve(null),
    });
  });
}
