# TypeScript vs. JavaScript in an SPA

Can the React SPA be written in TypeScript instead of JavaScript? Yes — and this project's SPA
already is written in TypeScript (`frontend/src/*.ts`/`.tsx`), not plain JavaScript.

## TypeScript

**Pros:**
- Catches type errors at compile time rather than at runtime — e.g., a typo in a DTO field name
  fails `tsc -b` instead of silently returning `undefined` in production.
- `src/types.ts` in this project mirrors every backend DTO, so a backend field rename immediately
  shows every frontend call site that needs updating.
- Better IDE autocomplete/refactoring, since the tooling knows the actual shape of your data.

**Cons:**
- Extra build step (`tsc -b` before `vite build`) and a bit more upfront ceremony — you have to
  define/import types, which is friction for small or throwaway scripts.
- Type definitions can drift from reality if not kept in sync (this project mitigates that by
  hand-mirroring the DTOs, which is itself a maintenance cost).
- Steeper learning curve for contributors unfamiliar with generics/interfaces.

## Plain JavaScript

**Pros:**
- Zero compile step, faster to prototype, no type-annotation overhead, one less toolchain concern.

**Cons:**
- The class of bugs TypeScript catches at compile time (wrong field name, wrong argument order,
  `null`/`undefined` handling) instead surface at runtime or in production — a meaningfully worse
  trade-off once an app has more than one contributor or more than a handful of files, which is why
  most production React codebases (including this one) default to TypeScript.
