import { defineStore } from 'pinia';
import { authApi } from '@/api/auth.js';
import { readToken, writeToken } from '@/services/apiClient.js';

export const useAuthStore = defineStore('auth', {
  state: () => ({ token: readToken(), user: null, restoring: null, restoreError: '' }),
  getters: {
    authenticated: state => Boolean(state.token && state.user),
    mustChangePassword: state => Boolean(state.user?.must_change_password),
    hasPermission: state => code => Boolean(state.user?.permission_codes?.includes(code)),
    hasMenu: state => key => Boolean(state.user?.menu_keys?.includes(key))
  },
  actions: {
    clear() {
      this.token = '';
      this.user = null;
      this.restoreError = '';
      writeToken('');
    },
    async loadCurrentUser() {
      this.user = await authApi.me();
      this.restoreError = '';
      return this.user;
    },
    async restore() {
      this.token = readToken();
      if (!this.token) { this.user = null; return null; }
      if (!this.restoring) {
        this.restoring = this.loadCurrentUser().catch(error => {
          if (error.status === 401) this.clear();
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
    async logout() {
      try { if (this.token) await authApi.logout(); }
      finally { this.clear(); }
    },
    async changePassword(currentPassword, newPassword) {
      await authApi.changePassword({ current_password: currentPassword, new_password: newPassword });
      this.clear();
    }
  }
});
