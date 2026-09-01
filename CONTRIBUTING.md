# Contributing to swing-mcp

Thanks for your interest in contributing! This document explains how to set up a development environment and how to submit a pull request.

## Prerequisites

- JDK 21+
- Maven 3.9+
- A desktop environment for GUI integration tests (they are skipped automatically in headless environments; CI runs them under `xvfb`)

## Getting started

1. Fork the repository and clone your fork:

   ```bash
   git clone https://github.com/<your-username>/swing-mcp.git
   cd swing-mcp
   ```

2. Build and run the tests:

   ```bash
   mvn verify
   ```

3. Try your build against the demo app: register the server with your MCP client (see [docs/installation.md](docs/installation.md)), then `launch_app` with `java -jar swing-mcp-demo/target/swing-mcp-demo-<version>.jar`.

## Making changes

1. Create a branch from `main` with a descriptive name:

   ```bash
   git checkout -b fix/dialog-timeout
   ```

   Use a prefix that matches the change type: `feat/`, `fix/`, `docs/`, `refactor/`, `test/`, or `chore/`.

2. Keep changes focused — one logical change per pull request. Put the change in the right module:

   - `swing-mcp-server` — MCP tools and server-side services
   - `swing-mcp-agent` — code running inside the target JVM (keep dependencies minimal; everything UI-touching must run on the EDT)
   - `swing-mcp-common` — shared command/DTO types (changes here affect the server–agent protocol; keep it backward compatible where possible)
   - `swing-mcp-demo` — demo app used by integration tests

3. Add or update tests for your change. New tools need coverage in the server module and, where behavior is visible in a UI, an integration test against the demo app.

4. Update documentation in the same PR: the relevant page under `docs/tools/`, `docs/tool-reference.md`, and the README if user-facing behavior changed. Add an entry under `[Unreleased]` in [CHANGELOG.md](CHANGELOG.md).

5. Write clear commit messages in imperative mood, ideally following [Conventional Commits](https://www.conventionalcommits.org/):

   ```
   feat(server): add wait_for_window tool
   fix(agent): handle modal dialogs blocking the EDT
   docs: document multi-session usage
   ```

## Submitting a pull request

1. Make sure `mvn verify` passes locally.
2. Push your branch and open a PR against `main`.
3. In the PR description, include:
   - **What** — a short summary of the change
   - **Why** — the problem it solves, linking any related issue (`Fixes #123`)
   - **How** — anything non-obvious about the approach
   - **Testing** — how you verified it (tests added, manual steps against the demo app, screenshots/snapshots where useful)
4. Keep the PR up to date with `main` and respond to review feedback; maintainers may ask for changes before merging.

## Reporting bugs and requesting features

Please use the [issue templates](https://github.com/crosstech-solutions-bv/swing-mcp/issues/new/choose). For security issues, do **not** open a public issue — see [SECURITY.md](SECURITY.md).

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE.md).
