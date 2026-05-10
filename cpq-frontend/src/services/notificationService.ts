import api from './api';

export const notificationService = {
  list: (params?: { page?: number; size?: number }) =>
    api.get('/notifications', { params }) as Promise<any>,
  getUnreadCount: () =>
    api.get('/notifications/unread-count') as Promise<any>,
  markRead: (id: string) =>
    api.put(`/notifications/${id}/read`) as Promise<any>,
  markAllRead: () =>
    api.put('/notifications/read-all') as Promise<any>,
};
