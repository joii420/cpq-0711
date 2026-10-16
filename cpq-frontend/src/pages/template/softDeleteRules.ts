/** UI-only predicates for the product-card template soft-delete state. */

export const canEditTemplate = (status?: string): boolean => status !== 'DELETED';

export const canDeleteTemplate = (status?: string): boolean =>
  status === 'DRAFT' || status === 'PUBLISHED';

export const canCreateNewDraftFromTemplate = (status: string | undefined, isSystemAdmin: boolean): boolean =>
  status !== 'DELETED' || isSystemAdmin;

export const deletedTemplateStatusOption = { value: 'DELETED', label: '已删除（历史）' } as const;
