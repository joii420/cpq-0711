import { describe, expect, it } from 'vitest';
import {
  canEditTemplate,
  canDeleteTemplate,
  canCreateNewDraftFromTemplate,
  deletedTemplateStatusOption,
} from '../softDeleteRules';
import { STATUS_COLORS, STATUS_LABELS } from '../types';

describe('template soft-delete UI contract', () => {
  it('recognises DELETED as a red, non-editable status', () => {
    expect(STATUS_LABELS.DELETED).toBe('已删除');
    expect(STATUS_COLORS.DELETED).toBe('red');
    expect(canEditTemplate('DELETED')).toBe(false);
    expect(canEditTemplate('ARCHIVED')).toBe(true);
  });

  it('allows deleting DRAFT/PUBLISHED but not ARCHIVED/DELETED', () => {
    expect(canDeleteTemplate('DRAFT')).toBe(true);
    expect(canDeleteTemplate('PUBLISHED')).toBe(true);
    expect(canDeleteTemplate('ARCHIVED')).toBe(false);
    expect(canDeleteTemplate('DELETED')).toBe(false);
  });

  it('exposes deleted history filter only with the privileged option', () => {
    expect(deletedTemplateStatusOption).toEqual({ value: 'DELETED', label: '已删除（历史）' });
  });

  it('only SYSTEM_ADMIN can create a new draft from DELETED', () => {
    expect(canCreateNewDraftFromTemplate('DELETED', false)).toBe(false);
    expect(canCreateNewDraftFromTemplate('DELETED', true)).toBe(true);
    expect(canCreateNewDraftFromTemplate('ARCHIVED', false)).toBe(true);
    expect(canCreateNewDraftFromTemplate('DRAFT', false)).toBe(true);
  });
});
