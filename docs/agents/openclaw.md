# OpenClaw

## Installation

```shell
# Install with npm and set up the Gateway service
npm install -g openclaw && openclaw gateway install && openclaw gateway start

# Update to latest version
npm update --quiet --no-fund -g openclaw

# Uninstall (Gateway service first)
openclaw gateway uninstall && npm uninstall -g openclaw
```

> via [openclaw.ai](https://openclaw.ai) &middot; [github.com/openclaw/openclaw](https://github.com/openclaw/openclaw)

## Get Version

```
% openclaw --version
```

## Usage

OpenClaw is an open-source, Claude Code–style AI coding assistant that runs on any OS and platform.

```
% cd your-project
% openclaw
# describe a task in natural language and let the agent work
```

## Features

- Open-source, cross-platform AI coding assistant
- Optional Gateway daemon (`openclaw onboard --install-daemon`) to stay running
- Plugin ecosystem and multi-agent setups
