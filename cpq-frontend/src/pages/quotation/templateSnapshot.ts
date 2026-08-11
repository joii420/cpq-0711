import { tryParseSnapshotJsonLossless } from '../../utils/losslessJson';

export function parseTemplateComponentsSnapshot(value: unknown): any[] {
  if (Array.isArray(value)) return value;
  if (typeof value !== 'string') return [];
  const parsed = tryParseSnapshotJsonLossless<unknown>(value);
  return Array.isArray(parsed) ? parsed : [];
}
