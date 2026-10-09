import { defineStore } from 'pinia';
import { authApi } from '@/api/auth.js';
import { readToken, writeToken } from '@/services/apiClient.js';
import { isBackendUser } from '@/utils/userType.js';

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: readToken(), user: null, restoring: null, restoreError: '',
    // 会话过期后原地重新登录（ZT-29）：页面和已填内容保留，弹窗挂在后台布局里，reloginHosts 记录弹窗是否在场。
    sessionExpired: false, expiredWhileSubmitting: false, reloginHosts: 0
  }),
  getters: {
    authenticated: state => Boolean(state.token && state.user),
    mustChangePassword: state => Boolean(state.user?.must_change_password),
    hasPermission: state => code => Boolean(state.user?.permission_codes?.includes(code)),
    hasMenu: state => key => Boolean(state.user?.menu_keys?.includes(key)),
    canReloginInPlace: state => Boolean(state.user?.account && state.reloginHosts > 0)
  },
  actions: {
    clear() {
      this.token = '';
      this.user = null;
      this.restoreError = '';
      this.sessionExpired = false;
      this.expiredWhileSubmitting = false;
      writeToken('');
    },
    async loadCurrentUser() {
      const user = await authApi.me();
      if (!isBackendUser(user)) {
        this.clear();
        const error = new Error('该账号是前台用户，不能登录后台管理系统');
        error.code = 'BACKEND_ACCESS_DENIED';
        throw error;
      }
      this.user = user;
      this.restoreError = '';
      return this.user;
    },
    async restore() {
      this.token = readToken();
      if (!this.token) { this.user = null; return null; }
      if (!this.restoring) {
        this.restoring = this.loadCurrentUser().catch(error => {
          // 已就地标记过期的会话留给重新登录弹窗处理，不清掉页面（ZT-29）。
          if (error.code === 'BACKEND_ACCESS_DENIED') this.clear();
          else if (error.status === 401) { if (!this.sessionExpired) this.clear(); }
          else this.restoreError = error.message;
          return null;
        }).finally(() => { this.restoring = null; });
      }
      return this.restoring;
    },
    async login(credentials) {
      const result = await authApi.login(credentials);
      this.token = result.session_id;
      writeToken(result.session_id);
      try { return await this.loadCurrentUser(); }
      catch (error) { this.clear(); throw error; }
    },
    /** 会话被服务端拒绝；submitting 表示被拒的是一次提交，提示里要说明没有保存。 */
    markSessionExpired({ submitting = false } = {}) {
      this.sessionExpired = true;
      if (submitting) this.expiredWhileSubmitting = true;
    },
    /** 用同一账号原地重新登录；旧会话已失效，失败时保持过期状态，页面内容不动。 */
    async relogin(password) {
      const previousUserId = this.user?.user_id;
      const result = await authApi.login({ account: this.user?.account || '', password });
      this.token = result.session_id;
      writeToken(result.session_id);
      const user = await this.loadCurrentUser();
      this.sessionExpired = false;
      this.expiredWhileSubmitting = false;
      // 新会话已写入；实时推送连接按存储里的会话重连，页面可据此补读过期期间错过的变化。
      window.dispatchEvent(new CustomEvent('admin:session-restored'));
      return { user, sameUser: user.user_id === previousUserId };
    },
    async logout() {
      try { if (this.token) await authApi.logout(); }
      finally { this.clear(); }
    },
    /** 本人只改姓名和电话；带上当前版本号，别处改过时服务端返回冲突。 */
    async updateProfile({ name, phone }) {
      this.user = await authApi.updateProfile({ name, phone, expected_version: this.user?.version ?? 0 });
      return this.user;
    },
    async changePassword(currentPassword, newPassword) {
      await authApi.changePassword({ current_password: currentPassword, new_password: newPassword });
      this.clear();
    }
  }
});
