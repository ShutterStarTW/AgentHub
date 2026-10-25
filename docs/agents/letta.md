# Letta Code - by Letta

## Installation

```shell
# Install with npm
npm install -g @letta-ai/letta-code

# Update to latest version (the CLI also self-updates through npm)
npm update --quiet --no-fund -g @letta-ai/letta-code

# Uninstall
npm uninstall -g @letta-ai/letta-code
```

> via [docs.letta.com/letta-code](https://docs.letta.com/letta-code) &middot; [github.com/letta-ai/letta-code](https://github.com/letta-ai/letta-code)


## Get Version

```
% letta --version
```

## Usage

Letta Code is a terminal coding agent with persistent memory: the agent keeps what it
learned across sessions. Run it from your project directory.

```
% cd your-project
% letta
# start or continue a session with the project's agent
```

## Features

- Persistent, cross-session memory
- Needs a recent Node.js (22 or newer); the global npm prefix must be user-owned for self-updates
- `uv tool install letta` or `pipx install letta` is an alternative that bundles Node.js
