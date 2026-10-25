# Hermes Agent - by Nous Research

## Installation

```shell
# Install with curl (macOS / Linux / WSL2)
curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash

# Windows (PowerShell)
irm https://hermes-agent.nousresearch.com/install.ps1 | iex

# Update to latest version
hermes update

# Uninstall
hermes uninstall
```

> via [hermes-agent.nousresearch.com](https://hermes-agent.nousresearch.com/docs/getting-started/installation/) &middot; [github.com/NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent)


## Get Version

```
% hermes --version
```

## Usage

Hermes Agent is Nous Research's terminal agent. The installer creates a `hermes` launcher
(`~/.local/bin/hermes` on Linux and macOS, `%LOCALAPPDATA%\hermes\bin` on Windows).

```
% hermes
# interactive session in the current directory

% hermes update --check
# check for a new release without applying it
```

## Features

- Installs natively on Linux, macOS and Windows; WSL2 is also supported
- `hermes update` follows the stable release channel; `hermes uninstall --dry-run` previews a removal
- Needs a model with at least 64k context
