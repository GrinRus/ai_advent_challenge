# AGENTS.md (frontend)

## Scope
- Applies only to `/Users/griogrii_riabov/grigorii_projects/ai_advent_challenge/frontend/**`.
- Overrides repository root rules for frontend-specific decisions.

## Architecture Anchors
- Stack: React + Vite + TypeScript.
- Main source: `src/`.
- Unit tests: `src/**/*.{test,spec}.{ts,tsx}`.
- E2E tests: `tests/`.

## Fast Checks
- Lint:
  - `npm run lint`
- Targeted unit tests:
  - `npm run test:unit -- <target test file or pattern>`
- Targeted e2e tests only when route/UI-flow behavior changes:
  - `npm run test:e2e -- <target spec>`

## Change Rules
- Keep API contract alignment with `src/lib/types/*` and `src/lib/apiClient.ts`.
- Preserve strict TypeScript hygiene (including enforced `no-explicit-any` policy).
- Update or add tests when changing form adapters, schemas, payload adapters, or route-level behavior.

## Do Not
- Do not edit `frontend/dist/**` or `frontend/node_modules/**`.
- Do not bypass typed adapters with ad-hoc payload shaping inside page components.
