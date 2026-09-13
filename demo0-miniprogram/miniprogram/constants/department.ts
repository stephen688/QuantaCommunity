/**
 * 实名认证「所属部门」固定选项，提交字段名仍为后端 `UserAuthDTO.quantaDepartment`。
 */
export const QUANTA_DEPARTMENT_OPTIONS = ['前端部', '后端部', '安卓部', '设计部', '产品部'] as const;

export type QuantaDepartmentOption = (typeof QUANTA_DEPARTMENT_OPTIONS)[number];

export function isFixedQuantaDepartment(value: string): boolean {
  const t = value.trim();
  return (QUANTA_DEPARTMENT_OPTIONS as readonly string[]).includes(t);
}

/** @returns 在固定列表中的下标 0..4，否则 -1 */
export function indexOfFixedQuantaDepartment(value: string): number {
  const t = value.trim();
  return (QUANTA_DEPARTMENT_OPTIONS as readonly string[]).indexOf(t);
}
