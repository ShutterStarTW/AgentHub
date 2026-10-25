# Changelog

## 1.3.6

*2026-10-25*

- New agents: **Autohand Code**, **Codebuff**, **Hermes Agent** (Nous Research) and **Letta Code**.
- Removed agents that no longer exist or are no longer maintained: **iFlow CLI** (shut down in April 2026), **Qodo** (npm package no longer supported), **Cody CLI** (Free and Pro plans ended, Amp replaces it) and **SWE-agent** (maintenance-only; its PyPI package was not the agent).
- Pi installs from its new npm package `@earendil-works/pi-coding-agent`, OpenCode from `@opencode/cli`.
- Settings: the **Agent** and **Status** headers of the agent and companion tables sort; Status is the default.
- **Open in…** in the session context menu: Oh My Pi, Kilo Code, MiMo Code and OpenCode import a Claude Code or Codex session with their own importer. AgentHub only starts the command.
- New **Changelog** page in the docs with every release.

## 1.3.5

*2026-10-21*

- Sessions whose transcript was deleted stay listed: Codex CLI from its state database (model, tokens, prompts and tool calls where recorded), Claude Code from `history.jsonl` (prompts and times). They are marked **Database** or **History**. Codex sessions resume, Claude ones do not.
- Projects whose directory no longer exists are marked **(deleted)**. New settings **Show deleted projects** and **Show empty sessions**.
- Empty sessions show as *Empty session* for every agent; Cursor, Junie and Freebuff no longer skip them.
- **Resume** warns when the session is already open in a running process. Tab titles are one line, 30 characters.
- The info strip under the tables shows 5 lines.

## 1.3.4

*2026-10-13*

- Settings: a **status filter** (All / Installed / Not installed / Update available) on the agent and companion tables; updatable agents are listed first.
- Junie CLI installs with the official installer. Its self-updating native install shows `?` instead of a false update.
- **Update all** waits up to 30 minutes for the results.
- Opening a file from the tool window uses the IDE's normal file navigation.

## 1.3.3

*2026-10-10*

- Skill sync restores the original when an atomic replace fails instead of deleting it.
- **Resume** and **Launch** use the session's own directory and warn when it is missing.
- Detection, update and install results belong to the environment they were started in; switching between Windows and WSL cannot apply a stale result. The tool window index is refreshed per environment and drops outdated completions.
- A hung WSL distro lookup times out, and large output from background installs no longer blocks them.
- Update checks validate the npm/pip report and show `?` when the query fails. **Update all** clears the outdated flag only for agents that were updated, and update commands with pipes or fallbacks run as written.
- Junie sessions beyond about 1,700 are no longer cut off. Session ids starting with `-` are refused.
- Devin, ForgeCode and Junie CLI install through npm; ForgeCode is no longer hidden on Windows.
- Mistral Vibe updates no longer use `--upgrade-strategy eager`. The one-time Kimi migration prompt was removed.

## 1.3.2

*2026-10-08*

- **WSL mode covers the tool window.** Projects, sessions, skills, MCP servers, instruction files and configuration are read from the distribution's home directory. **Resume** and **Launch** start inside the distro, and skill sync works on the distro's skill folders.
- Windows (native) and every WSL distribution keep **their own settings**: enabled agents and tools, detection results and update status. **Check installed agents** / **Check installed tools** ticks what the last detection found.
- Skills: a **Redundant links / copies** section with **Remove link** / **Remove copy** on the Overview and the Agents tab. **Remove copy** also swaps an identical copy of an agent that cannot read the shared folder for a link. **Clean up redundant copies…** covers both.
- An already shared skill no longer offers *Move to Shared*. Skill icons and the agent filter show the agents a folder belongs to; system skills are grouped by agent.
- Plandex was removed. The links of Auggie, Cody CLI, Droid, Grok Build, Muse Code, OpenHands and Qodo were updated.
- A GitHub Release is built from every `v*` tag.

## 1.3.1

*2026-10-06*

- The tool window discovers **seven more agents**: Kilo Code, Kimi Code, Oh My Pi, Junie CLI, Mistral Vibe, Freebuff and MiMo Code (17 in total) — projects and sessions, Skills, MCP servers, instruction files and Config.
- **Resume** now works for all 17 agents, using each CLI's own resume command (verified against `--help`). A session id that is not safe to pass to a command line is refused rather than quoted.
- Skills: the sync engine was reorganised (`SkillSyncEngine`), and symlinked skill folders are now walked. Kilo Code, Kimi Code, Oh My Pi, Junie CLI, Mistral Vibe and MiMo Code are Share/Promote targets.
- Discovery fixes: Claude `AGENTS.md` and `CLAUDE_CONFIG_DIR`, OpenCode `.opencode` and `OPENCODE_CONFIG(_DIR)`, Qwen `.qwen/rules` and nested `QWEN.md`, and same-named Antigravity MCP servers are listed separately. An instruction file that an agent does not apply is still listed, with an info line saying why.
- The Agents tab lists every installed agent, also before its first session: Projects and Sessions are greyed out until then, and Environment already shows the agent's global skills, MCP servers, instructions and configuration.
- Fewer needless rescans: AgentHub's own saves no longer look like changes made by another IDE, unchanged results are not written again, decoded indexes are cached, and the tool window no longer re-reads and re-scans on every click.
- Docs: per-agent source tables in the discovery paths page.

## 1.3.0

*2026-10-04*

- New **Skills** tab: browse global or project skills, search and filter them, and open or reveal their files.
- Share skills between agents. **Share…** is one checklist: check an agent to share with it, uncheck it to stop. An agent-specific skill can be moved to the shared folder from the same button. **Resync / Repair**, **Stop Sharing** and conflict resolution (keep the shared version, the agent's version, or both) are included.
- Every change shows a plan first and takes a backup before replacing anything. **Undo** and **Restore Backup…** still work after an IDE restart.
- **Migrate duplicates…** and **Clean up redundant copies…** tidy identical copies in one reviewed batch.
- Settings: preferred Link or Copy mode, backups, an option to review plans before applying them, and **Manage existing skills** (off by default), which lets Stop Sharing also remove links and identical copies AgentHub did not create.
- Skill ownership, history, settings and backups are shared by all JetBrains IDEs in `~/.agenthub`. Existing data is imported automatically.
- The Environment tab also lists agent configuration files (Config), with warnings for approval-bypass or unrestricted sandbox modes. Credentials and raw file contents are never stored.
- Projects, Agents and Skills share one layout. Sessions show the agent's title (or your first prompt), the prompt count and the time range, plus token, model, cost and tool statistics where the agent records them. **Resume** works for Claude Code, Codex CLI and OpenCode; **Transcript** opens the session file.
- The tool window shows only agents whose CLI was found by the latest detection. Detection now runs on every IDE start.
- Also: many smaller UI, layout, keyboard and accessibility fixes in the tool window and Settings. JaCoCo updated to 0.8.14 for Java 25.

## 1.2.0

*2026-09-04*

- New **AgentHub Projects tool window** with read-only project and session metadata discovery for Antigravity, Claude Code, Cline, Codex CLI, GitHub Copilot CLI, Cursor CLI, Grok Build, Kiro CLI, OpenCode and Qwen Code.
- Unified Projects and Agents views with search, per-agent session totals, last activity, normalized Git identity and a persistent cached index.
- Open a discovered project in a detected JetBrains IDE or launch a participating coding agent directly in the project's working directory.
- Inspect normalized Skills, MCP servers and instruction sources in the new read-only project and agent **Environment** tabs, with availability comparison, duplicate and conflict indicators for Antigravity, Claude, Cline, Codex, Copilot, Cursor, Grok, Kiro, OpenCode and Qwen Code.
- MCP secrets and executable configuration are never rendered in the UI.
- Discovery runs outside the UI thread, isolates malformed provider records, bounds project and environment scans, and is covered by a 10,000-session performance regression test.

## 1.1.2

*2026-08-29*

- New agent: **Oh My Pi** (can1357) — coding-first fork of Pi with LSP/DAP integration, browser and desktop tools, subagents and 60+ providers.
- Fix: **Update all agents** ran every installed agent's update command regardless of whether a newer version was available. It now only updates agents flagged outdated by the last "Check for updates" run, matching the per-agent Update button in Settings, and shows a notice instead of doing nothing silently when no updates are known.
- Fix (Windows): a failing version-check command (for example a missing runtime dependency) was reported as "up to date" instead of a warning, because the PowerShell helper never checked the process exit code.

## 1.1.1

*2026-08-13*

- New agent: **Muse Code** (Meta) — terminal coding agent for multi-agent, repo-scale software engineering tasks, powered by the Muse Spark model family (WSL-only on Windows; the installer has no native Windows build).
- New companion tool: **Semgrep CLI** — static analysis security scanner.

## 1.1.0

*2026-07-04*

- **WSL mode** (Windows): Settings → Behavior → "Run agents in:" runs every agent and companion tool inside a WSL distribution instead of natively. Detection is distro-native (it ignores Windows binaries exposed through WSL interop), and the Linux/WSL-only agents ForgeCode, LeanCTL, Plandex and Command Code become available. A missing pip/npm in the distro produces an install hint instead of failing silently.
- New agent: **Pi** (Mario Zechner) — minimalist, multi-provider terminal coding agent harness.
- New companion tools: **Claude Code Router**, **Claude-Code-Usage-Monitor**, **code2prompt**, **CodeGrab**, **CodeRabbit CLI** (hidden on Windows), **LiteLLM** and **Tokscale**.

## 1.0.0

*2026-06-30*

First stable release.

- New **Companion Tools** section — optional CLI utilities that work alongside the agents (usage tracking, context packing, skills): **ccusage**, **Repomix**, **Skills** and **TokenTracker**. Off by default; enable in Settings. They reuse the agent install, detect, update and launch flow but appear in their own section.
- New agents: **CodeBuddy** (Tencent), **Command Code**, **Devin** (Cognition), **iFlow CLI**, **Junie CLI** (JetBrains), **Kode** and **OpenClaw**. Command Code is hidden on Windows: its launch command `cmd` collides with the built-in Windows command.

## 0.9.1

*2026-06-23*

- New agent: **Freebuff** (Codebuff) — the free, ad-supported terminal coding agent.
- Fix: toolbar install of a not-installed agent now honors the "Run operations in background" setting, and a background-installed agent launches automatically once ready.
- Lowered the minimum platform to JetBrains IDE 2024.1+ (build 241+).

## 0.9.0

*2026-06-17*

Initial release as **AgentHub**.

- 30 built-in CLI coding agents with auto-detection and per-agent favicon icons.
- One-click launch from the toolbar; not-installed agents prompt to install with confirmation.
- Auto-detect on first install and after each plugin update, with results persisted across IDE restarts.
- Background update availability check on IDE startup.
- Install / Update / Remove directly from the Settings panel, with background result polling.
- Custom agent support: configure your own CLI tool with name, command and URL.
- Cross-platform: macOS, Linux, Windows (PowerShell helper script).
