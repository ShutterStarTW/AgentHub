# Usage limits

The **Usage** tab of the AgentHub tool window shows, for each installed agent, how much of its limits you have used and what you consumed recently. Select an agent to see its limit windows, reset times and local activity.

## What you see

- **Limit windows** — used percentage, reset time and the time left. Bars turn amber from 70% and red from 90%.
- **Pace** — a marker on the bar shows the even-pace point. The details say whether you are ahead of pace or on track.
- **Saved resets** — Codex and Claude Code accounts that hold saved resets list them with their expiry. AgentHub only shows them and never redeems one.
- **Local activity** — tokens, prompts and recorded cost over 5 hours, 24 hours, 7 days and 30 days, measured from the sessions AgentHub indexed.
- **Open Usage Dashboard** opens the page where the account's usage is shown (Claude Code, Codex CLI, GitHub Copilot CLI, Cursor CLI). **Open Usage Guide** opens the official page about the agent's own usage command (Antigravity, Hermes Agent, Kiro CLI, OpenClaw). Other agents have neither button. **Show in Agents** jumps to the agent.

Sort by highest usage, name or recent activity. Only installed agents are listed. **Only with limits** is on by default and hides agents that report no usage limit; an agent that needs a sign-in stays listed. The choice is remembered. The same details also open as the **Usage** tab of an agent on the Agents tab.

## Where the numbers come from

| Source | Needs Live quotas | What it does |
|---|---|---|
| Local sessions | No | Sums the indexed sessions of every discovered agent. |
| Codex rollout files | No | Reads the rate limits Codex logged in its latest session. |
| Claude Code cache | No | Reads the usage Claude Code cached in `~/.claude.json`, only when it belongs to the signed-in account. |
| Agent CLI | Yes | Runs the agent's own usage command (below). |
| Provider API | Yes | Asks the provider with the token of the agent's own sign-in. |

Agent CLI commands:

| Agent | Command |
|---|---|
| Claude Code | `claude -p "/usage"` |
| Antigravity | `agy -p "/usage"` |
| Kiro CLI | `kiro-cli chat --no-interactive "/usage"` (native Windows only) |
| Codex CLI | `codex app-server`, JSON-RPC `account/rateLimits/read` |
| Hermes Agent | `hermes usage --json` |
| Oh My Pi | `omp usage --json --redact` |
| OpenClaw | `openclaw status --usage --json` (takes about a minute; its row updates when it finishes) |

Provider APIs are used for Claude Code, Codex CLI, GitHub Copilot CLI, Cursor CLI and Grok Build, when the commands above are unavailable or give no limits. If Cursor or Grok is signed out or its token expired, the row offers a **Sign in** button that starts the CLI's login.

## Live quotas

**Live quotas** is on by default. Turn it off with the switch on the Usage tab or in **Settings > Tools > AgentHub**.

- Tokens are read for the request only and kept in memory. They are never saved, logged or refreshed.
- The cache stores numbers, plan names and times only. No tokens, emails or prompts.
- Refreshing is limited to once a minute; the Refresh button and the switch update at once.
- If a live query returns no limits, the row keeps the last live reading, marked stale, until that window resets. A window without a reset time is kept for 6 hours.
- AgentHub never runs a command that could call a model. Only usage commands that were verified not to are used.

With Live quotas off AgentHub reads local files. The only commands it runs are `cursor-agent about` and `kiro-cli whoami`, for the plan and sign-in status.
