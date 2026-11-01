# MiniMax Code - by MiniMax

## Installation

```shell
# Install with npm
npm install -g @minimax-ai/code

# Update to latest version
npm update --quiet --no-fund -g @minimax-ai/code

# Uninstall
npm uninstall -g @minimax-ai/code
```

Alternatively, the official installer sets up a compatible Node.js too:

```shell
# macOS / Linux / WSL
curl -fsSL https://filecdn.minimax.chat/public/install.sh | bash

# Windows (PowerShell)
irm https://filecdn.minimax.chat/public/install.ps1 | iex
```

> via [agent.minimax.io/docs/cli/quick-start](https://agent.minimax.io/docs/cli/quick-start)

Requires Node.js 22.19+ (22.x), 24, 25 or 26. The installer does not support Alpine and other musl-based Linux distributions.

## Get Version

```
% mcode --version
```

## Usage

MiniMax Code is a terminal coding agent. Sign in with your MiniMax account, or use your own model by setting
`MCODE_PROVIDER_API_KEY` and adding a provider.

```
% mcode
# launches the interactive terminal interface

% mcode logout
# signs out
```

Not to be confused with `mmx-cli`, MiniMax's separate multimodal and search CLI.
