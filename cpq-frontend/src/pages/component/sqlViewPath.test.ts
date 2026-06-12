import { describe, it, expect } from 'vitest';
import { extractSqlViewName } from './sqlViewPath';

describe('extractSqlViewName', () => {
  it('$view.col → view', () => expect(extractSqlViewName('$cp_view.品名')).toBe('cp_view'));
  it('$view（仅视图） → view', () => expect(extractSqlViewName('$cp_view')).toBe('cp_view'));
  it('非 $ 形态 → null', () => expect(extractSqlViewName('mat_part.x')).toBeNull());
  it('空 → null', () => expect(extractSqlViewName('')).toBeNull());
  it('undefined → null', () => expect(extractSqlViewName(undefined)).toBeNull());
});
