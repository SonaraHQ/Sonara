# AGENTS.md

Notes for AI coding agents working on Sonara. Humans are welcome to read it too.

We're happy to get contributions written with AI, and just as happy to get ones written by hand. Both are held to the same bar: the change works, it's small enough to review, and a person stands behind it. This file is about meeting that bar when an agent does part of the work. If the person you're working for or a maintainer tells you something different, do what they say.

Codex, Cursor, Windsurf, Zed, Jules and most other agents read this file directly. `CLAUDE.md` (Claude Code), `GEMINI.md` (Gemini CLI) and `.github/copilot-instructions.md` (GitHub Copilot) point here. Keep the rules in this file so they don't drift apart.

## What Sonara is

Sonara is a [Lavalink](https://github.com/lavalink-devs/Lavalink) 4 plugin that adds music sources (Spotify, Deezer, Apple Music, Tidal and more) and lyrics sources. It's a fork of [PulseLink](https://github.com/ItzRandom23/PulseLink), which is based on [LavaSrc](https://github.com/topi314/LavaSrc). Spotify playlists, recommendations and lyrics go through [Sonara-API](https://github.com/SonaraHQ/Sonara-API), which users host themselves.

- `main/`: the source managers, one package per service (`spotify/`, `deezer/`, `genius/`, ...).
- `plugin/`: the Lavalink plugin: config classes, registration (`SonaraPlugin`) and the REST endpoints.
- `protocol/`: the Kotlin types clients use for the plugin's API.
- `application.example.yml`: every config option, with comments.

## Before you start

- Read the code around what you're changing and copy how it does things. Each source manager follows the same pattern; a new one should look like its neighbours.
- Check open issues and PRs first. If someone is already working on the same thing, say so instead of building a second version.
- Base your branch on current `main`.
- Look at the working tree. Changes you didn't make belong to someone else: don't commit, reformat or discard them.

## Rules that matter here

- **No built-in third-party servers.** Sonara never talks to a server the user didn't configure. Don't add default URLs for proxies, resolvers or token services.
- **Secrets stay out of the repo and the logs.** Never commit or log `sp_dc` cookies, Deezer `arl` values, client secrets, API keys or tokens. Credentials in a configured URL are sent as headers, not left in the logged URL.
- **New options are documented.** A new setting goes under `plugins.sonara` and gets an entry in `application.example.yml` and the README.
- **Keep the credits.** Don't remove LICENSE, NOTICE or the PulseLink and LavaSrc credits.
- **Don't touch line endings.** Some files inherited from upstream mix CRLF and LF. Editing them with a tool that rewrites every line ending turns a two-line fix into a diff of the whole file. Check `git diff --stat` before you commit: if the numbers are much bigger than your change, fix that first.
- **Match the file's indentation.** Most files use tabs (see `.editorconfig`), but some use spaces. Follow the file you're editing.
- **Don't guess APIs.** Music services change their private APIs often. Check the real response (or an existing parser in the repo) instead of writing a parser from memory.

## Checking your work

You need JDK 17. Newer JDKs are too new for the Gradle version this project uses.

```bash
./gradlew build
```

A build that passes doesn't prove a source works. When you change how a source loads tracks, playlists or lyrics, run it in a real Lavalink with the plugin and try the URLs or queries you changed. If you couldn't do that, say which parts you didn't test.

Only add tests that would catch a real bug: parsers, URL matching, edge cases that are easy to break. Check that a regression test fails without your fix. Don't add tests that repeat the implementation or only test getters.

## Keep it small

- Change what the task needs and nothing else. Mention unrelated bugs you notice instead of fixing them in the same diff.
- One fix or feature per PR.
- No reformatting of code you didn't change, no unrelated dependency or lockfile updates, no new abstractions "for later".
- Comments explain why, not what the next line does.

## Commits and pull requests

- Commit messages use [Conventional Commits](https://www.conventionalcommits.org/) (`fix: ...`, `feat: ...`, `docs: ...`), like the existing history. Write them like a person: short and plain.
- Don't bump the version. Maintainers do that when they release.
- **The person you work for is responsible for the PR.** Before it goes up, they should have read the diff, run the change and be able to answer review questions about it. Only open a PR, or post comments and reviews, when they ask you to.
- **Say that AI helped.** Add one line at the top of the PR description, for example: `Written with help from <tool and model>.` This isn't a penalty, it tells reviewers how to read the PR. Commit trailers like `Co-Authored-By` are fine too.
- In the description, say what changed, why, and how it was tested. Keep it short.
- Don't open many PRs at once, and don't open a PR just to get a contribution in.

## When you hand over

Tell the person you work for, in your final message:

1. What you changed and what you tested.
2. What you couldn't test, so they can check it themselves.
3. That they need to read the diff before the PR goes up.
