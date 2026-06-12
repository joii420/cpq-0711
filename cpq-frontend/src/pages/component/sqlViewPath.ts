/** 从 driver 路径解析 $视图名。`$cp_view.品名`→`cp_view`；`$cp_view`→`cp_view`；非 $ 形态→null。 */
export function extractSqlViewName(path?: string): string | null {
  if (!path) return null;
  const t = path.trim();
  if (!t.startsWith('$')) return null;
  const body = t.slice(1);
  const dot = body.indexOf('.');
  const name = dot >= 0 ? body.slice(0, dot) : body;
  return name.length > 0 ? name : null;
}
