// S-A shared helpers (repair-260916). Pure utilities: no DB / HTTP here.
// Written from 问题说明.md ⑥ + api.md §5 only; no implementation source was read.
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';

export const WT = '/home/joii/project/cpq/.claude/worktrees/repair-260916-subtotal-suffix';
export const MAIN = '/home/joii/project/cpq';
export const SERIALIZE_REL = 'cpq-frontend/src/pages/component/formulaSerialize.ts';
export const ENGINE_REL = 'cpq-frontend/src/utils/formulaEngine.ts';

// ---------- assertion collector ----------
type Res = { id: string; ok: boolean; detail: string };
const results: Res[] = [];
export function check(id: string, ok: boolean, detail: string): boolean {
  results.push({ id, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'} [${id}] ${detail}`);
  return ok;
}
export function warn(msg: string) { console.log(`WARN ${msg}`); }
/** Precondition failure: abort (exit 2) - the run proves nothing either way. */
export function precondition(ok: boolean, msg: string): void {
  console.log(`${ok ? 'PRE-OK' : 'PRE-FAIL'} ${msg}`);
  if (!ok) { console.log('ABORT: 前置不成立，本次运行不构成证据'); process.exit(2); }
}
export function finish(): never {
  const fail = results.filter((r) => !r.ok);
  console.log(`\n==== SUMMARY: ${results.length} checks, ${results.length - fail.length} pass, ${fail.length} fail ====`);
  for (const f of fail) console.log(`  FAIL [${f.id}] ${f.detail}`);
  // guard against an "all green because nothing ran" outcome
  if (results.length === 0) { console.log('ABORT: 0 条断言被执行（空验证）'); process.exit(2); }
  process.exit(fail.length ? 1 : 0);
}

// ---------- canonical compare ----------
export function canon(v: unknown): string {
  const norm = (x: any): any => {
    if (Array.isArray(x)) return x.map(norm);
    if (x && typeof x === 'object') {
      const o: any = {};
      for (const k of Object.keys(x).sort()) if (x[k] !== undefined) o[k] = norm(x[k]);
      return o;
    }
    return x;
  };
  return JSON.stringify(norm(v));
}
export const same = (a: unknown, b: unknown) => canon(a) === canon(b);

/** First differing path between two JSON-ish values (for diagnostics). */
export function firstDiff(a: any, b: any, p = '$'): string | null {
  if (canon(a) === canon(b)) return null;
  if (a && b && typeof a === 'object' && typeof b === 'object' && Array.isArray(a) === Array.isArray(b)) {
    const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
    for (const k of keys) {
      const d = firstDiff(a[k], b[k], `${p}.${k}`);
      if (d) return d;
    }
  }
  return `${p}: ${JSON.stringify(a)} ≠ ${JSON.stringify(b)}`;
}

// ---------- token tree walk ----------
export type Path = (string | number)[];
/** Yields every token object, recursing into any array property whose items look like tokens. */
export function* walk(tokens: any[], prefix: Path = []): Generator<{ path: Path; token: any }> {
  if (!Array.isArray(tokens)) return;
  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i];
    const p = [...prefix, i];
    yield { path: p, token: t };
    if (t && typeof t === 'object') {
      for (const k of Object.keys(t)) {
        const v = t[k];
        if (Array.isArray(v) && v.some((x) => x && typeof x === 'object' && 'type' in x)) yield* walk(v, [...p, k]);
      }
    }
  }
}
export function getAt(root: any, path: Path): any {
  let cur = root;
  for (const k of path) { if (cur == null) return undefined; cur = cur[k as any]; }
  return cur;
}
export function setAt(root: any, path: Path, val: any): void {
  let cur = root;
  for (let i = 0; i < path.length - 1; i++) { if (cur == null) return; cur = cur[path[i] as any]; }
  if (cur != null) cur[path[path.length - 1] as any] = val;
}
export const clone = <T>(x: T): T => JSON.parse(JSON.stringify(x));
export const countOf = (s: string, sub: string) => (sub ? s.split(sub).length - 1 : 0);

// ---------- 9-digit display rounding (half-up), via decimal.js from cpq-frontend node_modules ----------
const req = createRequire(`${WT}/cpq-frontend/package.json`);
const Decimal: any = req('decimal.js');
export function r9(v: unknown): string {
  return new Decimal(String(v)).toDecimalPlaces(9, Decimal.ROUND_HALF_UP).toFixed(9);
}

// ---------- git provenance of the master copy in the main workspace ----------
export function git(args: string[], cwd: string): string {
  return execFileSync('git', ['-C', cwd, ...args], { encoding: 'utf8' }).trim();
}
/**
 * Verifies MAIN/<rel> equals master and has no local modification.
 * Hard-fails (precondition) on the file itself; lists other modified files under cpq-frontend/src as WARN.
 */
export function assertMainIsMaster(rel: string): void {
  const branch = git(['rev-parse', '--abbrev-ref', 'HEAD'], MAIN);
  const masterSha = git(['rev-parse', '--short', 'master'], MAIN);
  const fileSha = git(['log', '-1', '--format=%h', 'master', '--', rel], MAIN);
  const own = git(['status', '--porcelain', '--', rel], MAIN);
  let diffClean = true;
  try { execFileSync('git', ['-C', MAIN, 'diff', '--quiet', 'master', '--', rel]); } catch { diffClean = false; }
  console.log(`[master-copy] ${MAIN}/${rel}  主工作区分支=${branch} master=${masterSha} 该文件最后提交=${fileSha} status='${own}' 与master一致=${diffClean}`);
  precondition(branch === 'master', `主工作区当前分支为 master（实际 ${branch}）`);
  precondition(own === '' && diffClean, `主工作区 ${rel} 等于 master 且无本地改动`);
  const others = git(['status', '--porcelain', '--untracked-files=no', '--', 'cpq-frontend/src'], MAIN);
  if (others) warn(`主工作区 cpq-frontend/src 下另有已跟踪文件本地改动（若被 formulaSerialize 引入会污染 master 对照，需主线判定）：\n${others}`);
  else console.log('[master-copy] 主工作区 cpq-frontend/src 无已跟踪文件本地改动');
}
export function branchProvenance(): void {
  const head = git(['rev-parse', '--short', 'HEAD'], WT);
  const st = git(['status', '--porcelain', '--', SERIALIZE_REL, ENGINE_REL], WT);
  console.log(`[branch-copy] ${WT} HEAD=${head} 未提交改动(formulaSerialize/formulaEngine)='${st}'`);
}
