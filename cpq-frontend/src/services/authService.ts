import api from './api';

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  id: string;
  username: string;
  fullName: string;
  role: string;
  forceChangePassword: boolean;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export const authService = {
  login: (data: LoginRequest) => api.post('/auth/login', data) as Promise<any>,
  logout: () => api.post('/auth/logout') as Promise<any>,
  me: () => api.get('/auth/me') as Promise<any>,
  changePassword: (data: ChangePasswordRequest) => api.post('/auth/change-password', data) as Promise<any>,
  forgotPassword: (email: string) => api.post('/auth/forgot-password', { email }) as Promise<any>,
  resetPassword: (token: string, newPassword: string) =>
    api.post('/auth/reset-password', { token, newPassword }) as Promise<any>,
};
