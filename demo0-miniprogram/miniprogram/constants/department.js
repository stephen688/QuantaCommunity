"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.QUANTA_DEPARTMENT_OPTIONS = void 0;
exports.isFixedQuantaDepartment = isFixedQuantaDepartment;
exports.indexOfFixedQuantaDepartment = indexOfFixedQuantaDepartment;
/**
 * 实名认证「所属部门」固定选项，提交字段名仍为后端 `UserAuthDTO.quantaDepartment`。
 */
exports.QUANTA_DEPARTMENT_OPTIONS = ['前端部', '后端部', '安卓部', '设计部', '产品部'];
function isFixedQuantaDepartment(value) {
    const t = value.trim();
    return exports.QUANTA_DEPARTMENT_OPTIONS.includes(t);
}
/** @returns 在固定列表中的下标 0..4，否则 -1 */
function indexOfFixedQuantaDepartment(value) {
    const t = value.trim();
    return exports.QUANTA_DEPARTMENT_OPTIONS.indexOf(t);
}
