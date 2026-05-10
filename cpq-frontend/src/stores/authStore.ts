import { create } from 'zustand';
import { authService } from '../services/authService';

interface User {
  id: string;
  username: string;
  fullName: string;
  role: 'SALES_REP' | 'SALES_MANAGER' | 'PRICING_MANAGER' | 'SYSTEM_ADMIN';
}

interface AuthState {
  user: User | null;
  isAuthenticated: boolean;
  forceChangePassword: boolean;
  loading: boolean;
  setUser: (user: User | null) => void;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  fetchMe: () => Promise<void>;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  isAuthenticated: false,
  forceChangePassword: false,
  loading: true,
  setUser: (user) => set({ user, isAuthenticated: !!user }),
  login: async (username, password) => {
    const res = await authService.login({ username, password });
    const data = res.data;
    set({
      user: { id: data.id, username: data.username, fullName: data.fullName, role: data.role },
      isAuthenticated: true,
      forceChangePassword: data.forceChangePassword,
    });
  },
  logout: async () => {
    try { await authService.logout(); } catch {}
    set({ user: null, isAuthenticated: false, forceChangePassword: false });
  },
  fetchMe: async () => {
    try {
      const res = await authService.me();
      const data = res.data;
      set({
        user: { id: data.id, username: data.username, fullName: data.fullName, role: data.role },
        isAuthenticated: true,
        loading: false,
      });
    } catch {
      set({ user: null, isAuthenticated: false, loading: false });
    }
  },
}));
