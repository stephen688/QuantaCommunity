"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.REPORT_TYPE_LABELS = void 0;
exports.pickReportType = pickReportType;
/**
 * 与后端 ContentReportDTO / CommentReportDTO 的 reportType 一致（1–5）。
 */
exports.REPORT_TYPE_LABELS = [
    '垃圾广告',
    '人身攻击',
    '违规内容',
    '虚假信息',
    '其他',
];
/** 用户选择举报类型；取消时 resolve null */
function pickReportType() {
    return new Promise((resolve) => {
        wx.showActionSheet({
            itemList: exports.REPORT_TYPE_LABELS,
            success: (res) => {
                const t = res.tapIndex + 1;
                resolve(t >= 1 && t <= 5 ? t : null);
            },
            fail: () => resolve(null),
        });
    });
}
