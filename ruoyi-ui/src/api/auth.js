import { request } from '@/services/apiClient.js';

export const authApi = {
  login: body => request({ method: 'post', url: '/v1/auth/login', data: body }),
  me: () => request({ url: '/v1/auth/me' }),
  logout: () => request({ method: 'post', url: '/v1/auth/logout' }),
  changePassword: body => request({ method: 'post', url: '/v1/auth/change-password', data: body })
};
