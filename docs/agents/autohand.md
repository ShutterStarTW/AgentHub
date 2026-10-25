# Autohand Code - by Autohand

## Installation

```shell
# Install with npm
npm install -g autohand-cli

# Update to latest version
npm update --quiet --no-fund -g autohand-cli

# Uninstall
npm uninstall -g autohand-cli
```

> via [autohand.ai/cli](https://autohand.ai/cli/) &middot; [github.com/autohandai/code-cli](https://github.com/autohandai/code-cli)


## Get Version

```
% autohand --version
```

## Usage

Autohand Code is a terminal coding agent. The package installs several command names:
`autohand` (the one AgentHub uses), `autohand-code`, `ah` and `agent`.

```
% cd your-project
% autohand
# describe a task in natural language and let the agent edit your codebase
```

## Features

- Requires Node.js 22 or newer
- Apache-2.0; the repository adds commercial terms for companies above a revenue threshold
- The `agent` command name can collide with other tools that install an `agent` binary
