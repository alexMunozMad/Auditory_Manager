# CLAUDE.md

Audit-scheduling MVP for the Qualifyze backend challenge. The repo holds a **frozen design**
(`docs/00`–`docs/07`, `docs/adr/`, `docs/diagrams/`) plus a **thin implementation slice** around
assignment concurrency.

## Hard rules

Do not break these without the user's explicit approval **in the same message**.

1. **Never commit or merge on `main`.** Every task = a branch off `main` + a PR. The user reviews
   and squash-merges on GitHub. You never merge.
2. **Never** `git push --force`, `git push` to `main`, `git reset --hard`, `git rebase`,
   `git clean -f`, `git branch -D`, `git checkout/restore .`, or `git add -A` / `git add .`.
   A `PreToolUse` hook enforces this (`.claude/hooks/block-dangerous-git.js`).
3. **Never edit `docs/00-*`…`docs/07-*`, the ADRs, or the diagrams** during an implementation task.
   If the code shows the design is wrong, **stop and say so** — a design change is its own
   conversation, not a side effect.
4. **Never add a dependency, change the build config, or change a public API / DB shape** beyond
   what the current ticket specifies without asking first.
5. **No scope expansion.** If a ticket hides complexity, stop, report it, wait. No "while I'm here".
6. **TDD always.** A failing test first, then the minimum code to pass, then refactor.
7. **Simplest thing that passes.** MVP. No speculative abstraction. An interface with a single
   implementation → stop and ask.
8. **Every line defensible in one sentence**, or it does not go in.
9. **Do not chase coverage.** JaCoCo is measured, not a target (`docs/06`).
10. **Add files by explicit path.** These gitignored personal notes must never be staged:
    `GUION_DEFENSA.md`, `docs/99-defense-notes.md`, `QUALIFYZE_CONTEXT.md`,
    `PLAN_2_DIAS_QUALIFYZE.md`.

## Stack (locked)

Java 25 · Spring Boot 4 · Gradle · Liquibase 5 · PostgreSQL 18.

Tests: JUnit 5 + AssertJ · Mockito (use-case seam only, never on domain objects) · Testcontainers
(PostgreSQL 18) · Awaitility. No in-memory database — `docs/06` explains why.

## Workflow per task

1. `git switch main && git pull`
2. `git switch -c <type>/<n>-<slug>`  — type ∈ {feat, fix, test, refactor, chore}
3. Implement TDD, following `docs/06-testing-strategy.md`
4. Full test suite green
5. Review the diff (`/code-review` or a careful self-review)
6. Commit (format below)
7. `git push -u origin <branch>` and hand the user the PR compare link
8. **Stop.** The user reviews and squash-merges on GitHub.
9. After merge: `git switch main && git pull`, clear context, next task.

## Commit messages

`<type> : <lowercase summary>` — type ∈ {feat, fix, test, refactor, docs, chore}.

## Read before implementing

`docs/01` domain model · `docs/02` data model · `docs/05` concurrency · `docs/06` testing strategy.
The one-paragraph version of the whole system is `docs/05 §10`.
