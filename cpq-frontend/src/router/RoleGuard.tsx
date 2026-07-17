import React from 'react';
import { Result } from 'antd';
import { useAuthStore } from '../stores/authStore';

/**
 * 路由级角色守卫：当前用户角色不在 roles 白名单内 → 渲染 403 提示页（不跳转，让用户明确知道原因）。
 *
 * 使用约束：
 * - 必须嵌在 AuthGuard 内层（user 已由 fetchMe 就绪）；user 尚为 null 时放行（避免登录竞态闪 403），
 *   数据面安全仍由后端 @RoleAllowed 兜底。
 * - 菜单隐藏（MainLayout 菜单项 roles）只防"入口可见"；直链 / 书签 / 浏览器历史仍可达路由，
 *   凡"对某角色关闭的功能页"必须同时用本守卫包路由（2026-07-17 财务关闭报价单管理首用）。
 */
const RoleGuard: React.FC<{ roles: string[]; children: React.ReactNode }> = ({ roles, children }) => {
  const user = useAuthStore((s) => s.user);
  if (user && !roles.includes(user.role)) {
    return (
      <Result
        status="403"
        title="无权访问"
        subTitle="您的角色没有该功能的访问权限，如有疑问请联系系统管理员。"
      />
    );
  }
  return <>{children}</>;
};

export default RoleGuard;
