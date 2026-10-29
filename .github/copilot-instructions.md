# Copilot instructions

The full instructions for coding agents are in [AGENTS.md](../AGENTS.md) at the repository root. Read it before you change anything. The rules that matter most:

- Sonara is a Lavalink 4 plugin. Source managers live in `main/`, the plugin wiring and config in `plugin/`, client types in `protocol/`.
- Never add default URLs for third-party servers. Sonara only talks to servers the user configured.
- Never commit or log `sp_dc` cookies, Deezer `arl` values, client secrets, API keys or tokens.
- A new setting goes under `plugins.sonara` and gets documented in `application.example.yml` and the README.
- Some files mix CRLF and LF line endings. Don't rewrite them: check that `git diff --stat` matches the size of your change.
- Match the indentation of the file you edit. Build with JDK 17: `./gradlew build`.
- Change only what the task needs. Commits use Conventional Commits (`fix: ...`, `feat: ...`). Don't bump the version.
- A person reviews and runs the change before a PR goes up, and the PR description says that AI helped.
