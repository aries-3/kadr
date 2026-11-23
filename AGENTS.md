# Rules for AI agents

The single source of rules for every AI agent working in this repository. Tools that look for a file of their own (e.g. `CLAUDE.md` for Claude Code) only include this one; rules are written here.

## Language

Everything committed to the repository is written in English:

- documentation (README, AGENTS.md, docs);
- code comments;
- commit messages.

## Code

### Null safety

Nullness is declared with [JSpecify](https://jspecify.dev) (`org.jspecify.annotations`), non-null by default:

- Every package has a `package-info.java` annotated `@NullMarked`. A new package gets one in the same change.
- Only what may be null is annotated: `@Nullable` on a parameter, return type, field or type argument (`List<@Nullable String>`). It is a type-use annotation: `String @Nullable []`, `Map.@Nullable Entry<K, V>`.
- Never write `@NonNull` in marked code — it is the default.
- No other nullness annotations (`javax.annotation`, `jakarta.annotation`, `org.springframework.lang`, `org.jetbrains.annotations`).
- Bean Validation `@NotNull` is not a nullness annotation: it checks external input (request bodies, configuration properties) at runtime and stays where such input may be missing.
- Code generated from the API contract is not marked.

### Javadoc

- Every record component, in every module, is described by a `@param` tag in the record's Javadoc.
- In `domain` — the model and the ports the other modules build on — every public method and constructor has Javadoc with a `@param` for each parameter, a `@return` for the result and a `@throws` for each checked exception. A method without parameters may use `{@return ...}` alone.
- Code generated from the API contract is documented by the contract.

## Git

### Every file change goes to the index

The agent immediately reflects every change it makes to files in the repository (code, configuration, documentation) in the git index:

| Action | Command |
|--------|---------|
| created or modified a file | `git add <path>` |
| renamed or moved a file | `git mv <from> <to>` |
| deleted a file | `git rm <path>` |

- Stage by name only what the agent itself created or changed. Never `git add -A` or `git add .` — they pull in other people's changes and junk.
- Never stage build artifacts, IDE settings, secrets, data or model files (`target/`, `node_modules/`, `.idea/`, `*.onnx`, the pages and scripts the web build writes to `web/src/main/resources/static`). They belong in `.gitignore`; if a rule is missing there, add it.
- Files ignored by git (`.gitignore`, `.git/info/exclude`) are local on purpose — never force-add them with `git add -f`.
- Before finishing a task, check `git status`: everything the agent did is staged, nothing extra is.

### Commits only on explicit request

- The agent **never** runs `git commit` (including `--amend`) or `git push` unless explicitly asked to.
- A request covers one commit; the next one needs a new request.
- When done, the agent reports that the changes are staged and ready to commit.
