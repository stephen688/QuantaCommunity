import { indexOfFixedQuantaDepartment, QUANTA_DEPARTMENT_OPTIONS } from '../../constants/department';
import { getAuthDetail, getAuthStatus, submitUserAuth } from '../../services/user-auth.service';
import { getUserInfo, updateUserInfo } from '../../services/user.service';
import type { ApiErrorType } from '../../types/api';
import type { UserAuthStatusVO, UserAuthVO } from '../../types/auth';
import type { UserInfoDTO, UserInfoVO } from '../../types/user';
import { feedFullScreenError } from '../../utils/feed-error';

const DEPT_PICKER_PLACEHOLDER = '请选择所属部门';
const DEPARTMENT_PICKER_RANGE = [DEPT_PICKER_PLACEHOLDER, ...QUANTA_DEPARTMENT_OPTIONS];

function resolveDepartmentPickerState(
  raw: string | undefined | null,
  range: string[],
): { departmentPickerIndex: number; departmentLegacyRaw: string; departmentPickerLabel: string } {
  const s = (raw ?? '').trim();
  if (!s) {
    return { departmentPickerIndex: 0, departmentLegacyRaw: '', departmentPickerLabel: range[0] };
  }
  const fixIdx = indexOfFixedQuantaDepartment(s);
  if (fixIdx >= 0) {
    const departmentPickerIndex = fixIdx + 1;
    return {
      departmentPickerIndex,
      departmentLegacyRaw: '',
      departmentPickerLabel: range[departmentPickerIndex] ?? range[0],
    };
  }
  return { departmentPickerIndex: 0, departmentLegacyRaw: s, departmentPickerLabel: range[0] };
}

const AUTH_PENDING = 0;
const AUTH_APPROVED = 1;
const AUTH_REJECTED = 2;
const AUTH_NONE = -1;

function displayNick(u: UserInfoVO | null): string {
  if (!u) {
    return '';
  }
  const n = (u.nickName || u.nickname || '').trim();
  return n;
}

function authStatusTitle(st?: number | null): string {
  if (st === AUTH_NONE) {
    return '尚未提交实名认证';
  }
  if (st === AUTH_PENDING) {
    return '审核中';
  }
  if (st === AUTH_APPROVED) {
    return '已通过实名认证';
  }
  if (st === AUTH_REJECTED) {
    return '认证未通过';
  }
  return '认证状态';
}

Page({
  data: {
    verifyMode: false,
    pageLoading: true,
    profileErrorType: '' as ApiErrorType | '',
    profileErrorMessage: '',
    stateTitle: '',
    stateActionText: '',
    userInfo: null as UserInfoVO | null,
    nickName: '',
    images: [] as string[],
    submitting: false,
    uploading: false,
    uploadError: false,
    authStatus: null as UserAuthStatusVO | null,
    authStatusText: '',
    approvedDetail: null as UserAuthVO | null,
    identityType: 1,
    realName: '',
    schoolId: '',
    quantaBatch: '',
    departmentPickerRange: DEPARTMENT_PICKER_RANGE,
    departmentPickerIndex: 0,
    departmentPickerLabel: DEPT_PICKER_PLACEHOLDER,
    departmentLegacyRaw: '',
    authSubmitting: false,
    focusedField: '',
    fieldErrors: {
      nickName: '',
      realName: '',
      schoolId: '',
      quantaBatch: '',
      department: '',
    },
    keyboardInset: 0,
  },

  onKeyboardHeightChange(res: { height?: number }) {
    const h = Math.max(0, res.height || 0);
    if (h !== this.data.keyboardInset) {
      this.setData({ keyboardInset: h });
    }
  },

  onLoad(query: Record<string, string | undefined>) {
    const verifyMode = query.intent === 'verify';
    if (verifyMode) {
      wx.setNavigationBarTitle({ title: '实名认证' });
    } else {
      wx.setNavigationBarTitle({ title: '编辑资料' });
    }
    this.setData({ verifyMode });
    void this.reload();
  },

  onProfileRetry() {
    void this.reload();
  },

  onFieldFocus(e: WechatMiniprogram.CustomEvent) {
    const field = (e.currentTarget?.dataset?.field as string) || '';
    this.setData({ focusedField: field });
  },

  onFieldBlur() {
    this.setData({ focusedField: '' });
  },

  clearFieldError(field: 'nickName' | 'realName' | 'schoolId' | 'quantaBatch' | 'department') {
    if (!this.data.fieldErrors[field]) {
      return;
    }
    this.setData({ [`fieldErrors.${field}`]: '' });
  },

  setFieldError(field: 'nickName' | 'realName' | 'schoolId' | 'quantaBatch' | 'department', message: string) {
    this.setData({ [`fieldErrors.${field}`]: message });
  },

  onNickInput(e: WechatMiniprogram.CustomEvent<{ value?: string }>) {
    this.clearFieldError('nickName');
    this.setData({ nickName: e.detail?.value ?? '' });
  },

  onImagesChange(e: WechatMiniprogram.CustomEvent<{ value?: string[] }>) {
    const raw = e.detail?.value;
    const list = Array.isArray(raw) ? raw.filter((x): x is string => typeof x === 'string') : [];
    this.setData({ images: list.slice(0, 1) });
  },

  onUploadStateChange(
    e: WechatMiniprogram.CustomEvent<{ uploading?: boolean; hasError?: boolean }>,
  ) {
    this.setData({
      uploading: e.detail?.uploading === true,
      uploadError: e.detail?.hasError === true,
    });
  },

  onIdentityCampus() {
    this.setData({ identityType: 1 });
  },

  onIdentityAlumni() {
    this.setData({ identityType: 2 });
  },

  onRealNameInput(e: WechatMiniprogram.CustomEvent<{ value?: string }>) {
    this.clearFieldError('realName');
    this.setData({ realName: e.detail?.value ?? '' });
  },

  onSchoolIdInput(e: WechatMiniprogram.CustomEvent<{ value?: string }>) {
    this.clearFieldError('schoolId');
    this.setData({ schoolId: e.detail?.value ?? '' });
  },

  onBatchInput(e: WechatMiniprogram.CustomEvent<{ value?: string }>) {
    this.clearFieldError('quantaBatch');
    this.setData({ quantaBatch: e.detail?.value ?? '' });
  },

  onDepartmentPickerChange(e: WechatMiniprogram.PickerChange) {
    const idx = Number(e.detail.value);
    const range = this.data.departmentPickerRange;
    const safe = Number.isFinite(idx) ? Math.max(0, Math.min(idx, range.length - 1)) : 0;
    this.clearFieldError('department');
    this.setData({
      departmentPickerIndex: safe,
      departmentPickerLabel: range[safe] ?? range[0],
      departmentLegacyRaw: safe > 0 ? '' : this.data.departmentLegacyRaw,
    });
  },

  async onSubmitProfile() {
    if (this.data.submitting || this.data.uploading) {
      return;
    }
    const nick = this.data.nickName.trim();
    if (!nick) {
      this.setFieldError('nickName', '昵称不能为空');
      return;
    }
    if (nick.length > 20) {
      this.setFieldError('nickName', '昵称不能超过 20 字');
      return;
    }
    if (this.data.uploadError) {
      wx.showToast({ title: '头像上传失败，请点击重试后再保存', icon: 'none' });
      return;
    }
    const payload: UserInfoDTO = { nickName: nick };
    const av = (this.data.images[0] || '').trim();
    if (av) {
      if (!av.startsWith('http://') && !av.startsWith('https://')) {
        wx.showToast({ title: '头像上传未完成，请等待上传成功后再保存', icon: 'none' });
        return;
      }
      payload.avatarUrl = av;
    }
    this.setData({ submitting: true });
    const res = await updateUserInfo(payload);
    this.setData({ submitting: false });
    if (!res.ok) {
      wx.showToast({ title: res.message || '保存失败', icon: 'none' });
      return;
    }
    const refreshed = await getUserInfo();
    if (refreshed.ok) {
      this.setData({
        userInfo: refreshed.data,
        nickName: displayNick(refreshed.data),
        images: (refreshed.data.avatarUrl || '').trim()
          ? [(refreshed.data.avatarUrl || '').trim()]
          : [],
      });
    }
    wx.showToast({ title: '已保存', icon: 'success' });
    setTimeout(() => wx.navigateBack(), 400);
  },

  async onSubmitAuth() {
    if (this.data.authSubmitting) {
      return;
    }
    const st = this.data.authStatus?.auditStatus;
    if (st === AUTH_PENDING) {
      wx.showToast({ title: '审核中，请耐心等待', icon: 'none' });
      return;
    }
    if (st === AUTH_APPROVED) {
      wx.showToast({ title: '已完成认证', icon: 'none' });
      return;
    }
    const identityType = this.data.identityType;
    if (identityType !== 1 && identityType !== 2) {
      wx.showToast({ title: '请选择身份类型', icon: 'none' });
      return;
    }
    const realName = this.data.realName.trim();
    const schoolId = this.data.schoolId.trim();
    const quantaBatch = this.data.quantaBatch.trim();
    let hasError = false;
    if (!realName) {
      this.setFieldError('realName', '请填写真实姓名');
      hasError = true;
    }
    if (!schoolId) {
      this.setFieldError('schoolId', '请填写学号或校友编号');
      hasError = true;
    }
    if (!quantaBatch) {
      this.setFieldError('quantaBatch', '请填写 Quanta 届');
      hasError = true;
    }
    if (this.data.departmentPickerIndex <= 0) {
      this.setFieldError('department', '请选择所属部门');
      hasError = true;
    }
    if (hasError) {
      wx.showToast({ title: '身份信息未填完整', icon: 'none' });
      return;
    }
    const quantaDepartment = QUANTA_DEPARTMENT_OPTIONS[this.data.departmentPickerIndex - 1];
    this.setData({ authSubmitting: true });
    const res = await submitUserAuth({
      identityType,
      realName,
      schoolId,
      quantaBatch,
      quantaDepartment,
    });
    this.setData({ authSubmitting: false });
    if (!res.ok) {
      wx.showToast({ title: res.message || '提交失败', icon: 'none' });
      return;
    }
    wx.showToast({ title: '已提交，待审核', icon: 'success' });
    await this.loadVerify();
  },

  async reload() {
    this.setData({
      pageLoading: true,
      profileErrorType: '',
      profileErrorMessage: '',
      stateTitle: '',
      stateActionText: '',
    });
    if (this.data.verifyMode) {
      await this.loadVerify();
    } else {
      await this.loadProfile();
    }
  },

  async loadProfile() {
    const res = await getUserInfo();
    if (!res.ok) {
      const err = feedFullScreenError(res);
      this.setData({
        pageLoading: false,
        userInfo: null,
        profileErrorType: err.errorType,
        stateTitle: err.stateTitle,
        profileErrorMessage: err.errorMessage,
        stateActionText: err.stateActionText,
      });
      return;
    }
    const u = res.data;
    const nick = displayNick(u);
    const av = (u.avatarUrl || '').trim();
    this.setData({
      pageLoading: false,
      profileErrorType: '',
      profileErrorMessage: '',
      stateTitle: '',
      stateActionText: '',
      userInfo: u,
      nickName: nick,
      images: av ? [av] : [],
    });
  },

  async loadVerify() {
    const res = await getAuthStatus();
    if (!res.ok) {
      const err = feedFullScreenError(res);
      this.setData({
        pageLoading: false,
        authStatus: null,
        profileErrorType: err.errorType,
        stateTitle: err.stateTitle,
        profileErrorMessage: err.errorMessage,
        stateActionText: err.stateActionText,
        authStatusText: '',
      });
      return;
    }
    const authStatus = res.data;
    const st = authStatus.auditStatus ?? AUTH_NONE;
    let approvedDetail: UserAuthVO | null = null;
    if (st === AUTH_APPROVED) {
      const d = await getAuthDetail();
      if (d.ok) {
        approvedDetail = d.data;
      }
    }
    let deptPrefill = '';
    if (this.data.verifyMode && (st === AUTH_NONE || st === AUTH_REJECTED)) {
      const ui = await getUserInfo();
      if (ui.ok) {
        deptPrefill = (ui.data.quantaDepartment ?? '').trim();
      }
    }
    const deptState = resolveDepartmentPickerState(deptPrefill, DEPARTMENT_PICKER_RANGE);
    this.setData({
      pageLoading: false,
      profileErrorType: '',
      profileErrorMessage: '',
      stateTitle: '',
      stateActionText: '',
      authStatus,
      authStatusText: authStatusTitle(st),
      approvedDetail,
      ...deptState,
    });
  },
});
