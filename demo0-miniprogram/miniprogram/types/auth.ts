/**
 * 与后端 UserAuthDTO 对齐（提交实名认证）。
 */
export interface UserAuthDTO {
  /** 身份类型：1-在校成员 2-历届校友 */
  identityType: number;
  realName: string;
  schoolId: string;
  /** Quanta 届数（必填） */
  quantaBatch: string;
  quantaDepartment: string;
}

/**
 * 与后端 UserAuthStatusVO 对齐。
 * auditStatus：-1 未提交、0 待审核、1 已通过、2 已驳回
 */
export interface UserAuthStatusVO {
  auditStatus?: number | null;
  auditRemark?: string | null;
}

/**
 * 与后端 UserAuth 实体对齐（提交返回 / 详情；字段可选以兼容部分场景）。
 */
export interface UserAuthVO {
  authId?: number;
  userId?: number;
  identityType?: number;
  realName?: string;
  schoolId?: string;
  quantaBatch?: string;
  quantaDepartment?: string;
  auditStatus?: number;
  auditRemark?: string | null;
  createTime?: string;
  updateTime?: string;
}
