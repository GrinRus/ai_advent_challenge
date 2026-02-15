# AGENTS.md (backend)

## Scope
- Applies only to `/Users/griogrii_riabov/grigorii_projects/ai_advent_challenge/backend/**`.
- Overrides repository root rules for backend-specific decisions.

## Architecture Anchors
- Base package: `com.aiadvent.backend`.
- Layering: controller -> service -> domain -> persistence.
- Liquibase changelogs: `src/main/resources/db/changelog`.

## Fast Checks
- Run targeted backend tests:
  - `./gradlew test --tests "<affected test class pattern>"`
- Run formatting gate:
  - `./gradlew spotlessCheck`
- Run smoke tests only when changing chat/provider/stream behavior:
  - `./gradlew smokeTest --tests "<pattern>"`

## Change Rules
- Any DB schema change must include a Liquibase changeSet.
- API/DTO contract changes must include matching tests in `src/test/java`.
- Changes in `application*.yaml` must keep env-placeholder compatibility and defaults coherent.

## Do Not
- Do not edit `backend/build/**`.
- Do not introduce provider-specific hardcoding that bypasses existing provider registry/configuration flows.
