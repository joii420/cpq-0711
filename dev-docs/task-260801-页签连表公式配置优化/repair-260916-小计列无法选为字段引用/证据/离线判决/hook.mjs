import { registerHooks } from 'node:module';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
registerHooks({
  resolve(spec, ctx, next) {
    if ((spec.startsWith('.') || spec.startsWith('/')) && !/\.[cm]?[jt]sx?$/.test(spec)) {
      for (const ext of ['.ts', '.tsx', '/index.ts']) {
        try {
          const u = new URL(spec + ext, ctx.parentURL);
          if (existsSync(fileURLToPath(u))) return next(u.href, ctx);
        } catch {}
      }
    }
    return next(spec, ctx);
  },
});
