# CodeWhale - by CodeWhale

## Installation

```shell
# Install with npm
npm install -g codewhale

# Update to latest version
npm update --quiet --no-fund -g codewhale

# Uninstall
npm uninstall -g codewhale
```

The npm package wraps the prebuilt Rust release binaries. Other routes:

```shell
# Windows
winget install HunterBown.CodeWhale

# Rust
cargo install codewhale-cli --locked
```

> via [github.com/codewhale-hq/Codewhale](https://github.com/codewhale-hq/Codewhale)

CodeWhale is the successor of the deprecated `deepseek-tui` npm package. Windows has no OS-level sandbox yet.

## Get Version

```
% codewhale --version
```

## Usage

CodeWhale is an open-source terminal coding agent written in Rust. It reads code, makes edits and runs commands,
and works with DeepSeek and many other model providers. The first run asks you to connect a provider.

```
% codewhale
# launches the interactive terminal interface (short alias: codew)

% codewhale update
# updates the installed binary
```
