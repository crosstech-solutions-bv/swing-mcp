# Publishing to the MCP Registry

This project is published to the [MCP Registry](https://registry.modelcontextprotocol.io) as
**`io.github.crosstech-solutions-bv/swing-mcp`**, following the
[registry quickstart](https://github.com/modelcontextprotocol/registry/blob/main/docs/modelcontextprotocol-io/quickstart.mdx).

Because this is a Java/Maven project (the registry does not host Maven Central packages), the
server is distributed as an **[MCPB](https://github.com/anthropics/mcpb) bundle** attached to
GitHub releases — see the
[supported package types](https://github.com/modelcontextprotocol/registry/blob/main/docs/modelcontextprotocol-io/package-types.mdx).

## Table of Contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Files involved](#files-involved)
- [Automated publishing (recommended)](#automated-publishing-recommended)
- [Manual publishing (Windows PowerShell)](#manual-publishing-windows-powershell)
  - [Step 1 — Build the `.mcpb` bundle](#step-1--build-the-mcpb-bundle)
  - [Step 2 — Upload to the GitHub release and compute the SHA-256](#step-2--upload-to-the-github-release-and-compute-the-sha-256)
  - [Step 3 — Install the `mcp-publisher` CLI](#step-3--install-the-mcp-publisher-cli)
  - [Step 4 — Create `server.json`](#step-4--create-serverjson)
  - [Step 5 — Authenticate (GitHub device flow)](#step-5--authenticate-github-device-flow)
  - [Step 6 — Publish](#step-6--publish)
  - [Step 7 — Verify](#step-7--verify)
- [Troubleshooting](#troubleshooting)
- [Future releases checklist](#future-releases-checklist)
- [References](#references)

## Overview

The MCP Registry (<https://registry.modelcontextprotocol.io>) hosts **metadata only** — it never
stores artifacts. Creating a GitHub release does **not** automatically add a server to the
registry; you must explicitly publish a `server.json` using the `mcp-publisher` CLI.

**Package type — MCPB:** The registry supports npm, PyPI, NuGet, Cargo, OCI (Docker), and MCPB.
Maven Central is **not** supported. Because this is a Java project whose server must run on the
host JVM alongside the Swing application, Docker is unsuitable. Instead, the JARs are distributed
as an **MCPB** bundle — a zip archive renamed to `.mcpb` that contains a `manifest.json` plus the
JARs — attached to a GitHub release.

**Key rules for MCPB:**

- The artifact URL (`identifier` in `server.json`) must contain the string `mcp`. The repo name
  `swing-mcp` satisfies this.
- `server.json` must include a `fileSha256` field (lowercase hex SHA-256 of the `.mcpb` file).
  The registry does not validate the hash, but MCP clients validate it before installation.

**Case-sensitive server name:** When publishing with GitHub authentication, the server `name`
must start with `io.github.<username>/` and the username portion is **case-sensitive** — it must
exactly match the GitHub login. For this repository the correct value is
`io.github.crosstech-solutions-bv/swing-mcp` (capital `T` and `J`).

**Public repository required:** The registry validator fetches the artifact URL anonymously;
a private repository returns 404 to unauthenticated requests, which causes a 400 error.

**Downstream registries:** Registries such as the GitHub MCP registry at github.com/mcp sync from
the official registry with some lag; one publish covers them.

## Prerequisites

- **Windows PowerShell** (5.1 or 7; see [Troubleshooting](#troubleshooting) for important
  BOM differences between the two versions)
- **GitHub CLI** (`gh`), installable via `winget install GitHub.cli`
- **Java 21 + Maven** to build the release JARs
- A **public** GitHub repository with a published release
- A **GitHub account** (used for registry device-flow authentication)

## Files involved

| File | Purpose |
| --- | --- |
| [`server.json`](../server.json) | MCP Registry server metadata. `name` uses the `io.github.crosstech-solutions-bv/` namespace required for GitHub-based authentication. |
| [`mcpb/manifest.json`](../mcpb/manifest.json) | MCPB bundle manifest. Tells MCP clients to run `java -jar server/swing-mcp-server.jar` with `SWING_MCP_AGENT_JAR` pointing at the bundled agent jar. |
| [`.github/workflows/publish-mcp.yml`](../.github/workflows/publish-mcp.yml) | Release workflow that builds the bundle and publishes to the registry. |

## Automated publishing (recommended)

Publishing is automated by the `Publish to MCP Registry` workflow. To release a new version:

1. Bump the Maven project version (e.g. `mvn versions:set -DnewVersion=1.1.0`).
2. Create and publish a GitHub release with a matching tag, e.g. `v1.1.0`.

The workflow then:

1. Builds the jars with Maven (JDK 21).
2. Assembles `swing-mcp.mcpb` (a zip of `manifest.json` plus the server and agent jars, with the
   manifest version set from the tag).
3. Uploads `swing-mcp.mcpb` as a release asset. MCPB verification requires the artifact URL to
   contain the string `mcp`, which the `.mcpb` extension satisfies.
4. Rewrites `server.json` in the workspace with the release version, download URL, and the
   artifact's SHA-256 (`fileSha256`), which MCP clients use to verify file integrity.
5. Authenticates with the registry via GitHub OIDC (`mcp-publisher login github-oidc`) — no
   secrets needed, and OIDC from this repo grants publish rights to the
   `io.github.crosstech-solutions-bv/*` namespace.
6. Runs `mcp-publisher publish`.

## Manual publishing (Windows PowerShell)

These are the exact commands used for the 1.0.0 release. Run all commands from the repository
root (e.g. `D:\...\swing-mcp`).

### Step 1 — Build the `.mcpb` bundle

Create a staging folder and copy the release JARs:

```powershell
# Staging folder with the release JARs + manifest
New-Item -ItemType Directory -Path .\mcpb-build -Force
Copy-Item .\swing-mcp-server\target\swing-mcp-server-1.0.0.jar .\mcpb-build\
Copy-Item .\swing-mcp-agent\target\swing-mcp-agent-1.0.0.jar .\mcpb-build\
```

Write the MCPB manifest (`mcpb-build\manifest.json`):

```json
{
  "manifest_version": "0.2",
  "name": "swing-mcp",
  "version": "1.0.0",
  "description": "MCP server for inspecting and automating Java Swing applications.",
  "author": { "name": "CrossTech Solutions" },
  "server": {
    "type": "binary",
    "mcp_config": {
      "command": "java",
      "args": ["-jar", "${__dirname}/swing-mcp-server-1.0.0.jar"]
    }
  }
}
```

> **CRITICAL — UTF-8 BOM:** JSON files consumed by Go-based tooling must be **UTF-8 without BOM**.
> In Windows PowerShell 5.1, `Set-Content -Encoding UTF8` silently prepends a BOM (`EF BB BF`).
> PowerShell 7 writes BOM-less UTF-8 by default. Always use the following technique:

```powershell
$manifestJson = @'
{
  "manifest_version": "0.2",
  "name": "swing-mcp",
  "version": "1.0.0",
  "description": "MCP server for inspecting and automating Java Swing applications.",
  "author": { "name": "CrossTech Solutions" },
  "server": {
    "type": "binary",
    "mcp_config": {
      "command": "java",
      "args": ["-jar", "${__dirname}/swing-mcp-server-1.0.0.jar"]
    }
  }
}
'@
[System.IO.File]::WriteAllText("$PWD\mcpb-build\manifest.json", $manifestJson, [System.Text.UTF8Encoding]::new($false))
```

Zip and rename (the manifest must be at the archive root, not in a subdirectory):

```powershell
Compress-Archive -Path .\mcpb-build\* -DestinationPath .\swing-mcp.zip -Force
Rename-Item .\swing-mcp.zip swing-mcp.mcpb -Force
```

**Alternative:** If Node.js is installed, `npx @anthropic-ai/mcpb pack` builds the bundle.

### Step 2 — Upload to the GitHub release and compute the SHA-256

```powershell
gh release upload V1.0.0 .\swing-mcp.mcpb --repo crosstech-solutions-bv/swing-mcp --clobber
$sha256 = (Get-FileHash -Algorithm SHA256 .\swing-mcp.mcpb).Hash.ToLowerInvariant()
$sha256
```

Note the SHA-256 output — you will need it in Step 4.

> `--clobber` replaces an existing asset with the same name. Any rebuild of the `.mcpb` produces
> a different hash, so `server.json` must be updated to match before republishing.

### Step 3 — Install the `mcp-publisher` CLI

```powershell
$arch = if ([System.Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture -eq "Arm64") { "arm64" } else { "amd64" }
Invoke-WebRequest -Uri "https://github.com/modelcontextprotocol/registry/releases/latest/download/mcp-publisher_windows_$arch.tar.gz" -OutFile "mcp-publisher.tar.gz"
tar -xzf mcp-publisher.tar.gz mcp-publisher.exe
.\mcp-publisher.exe --help
```

### Step 4 — Create `server.json`

Create `server.json` in the repository root. Replace `<lowercase sha-256>` with the hash computed
in Step 2:

```json
{
  "$schema": "https://static.modelcontextprotocol.io/schemas/2025-12-11/server.schema.json",
  "name": "io.github.crosstech-solutions-bv/swing-mcp",
  "description": "MCP server for inspecting and automating Java Swing applications.",
  "repository": {
    "url": "https://github.com/crosstech-solutions-bv/swing-mcp",
    "source": "github"
  },
  "version": "1.0.0",
  "packages": [
    {
      "registryType": "mcpb",
      "identifier": "https://github.com/crosstech-solutions-bv/swing-mcp/releases/download/V1.0.0/swing-mcp.mcpb",
      "fileSha256": "<lowercase sha-256 of swing-mcp.mcpb>",
      "transport": { "type": "stdio" }
    }
  ]
}
```

> **Note:** The `name` field is **case-sensitive** — use `io.github.crosstech-solutions-bv/swing-mcp`, not
> `solutions.crosstech/swing-mcp`. See [Troubleshooting](#troubleshooting) for the 403 error this
> causes.

Write it BOM-less (same technique as the manifest), interpolating the `$sha256` variable from
Step 2:

```powershell
$serverJson = @"
{
  "`$schema": "https://static.modelcontextprotocol.io/schemas/2025-12-11/server.schema.json",
  "name": "io.github.crosstech-solutions-bv/swing-mcp",
  "description": "MCP server for inspecting and automating Java Swing applications.",
  "repository": {
    "url": "https://github.com/crosstech-solutions-bv/swing-mcp",
    "source": "github"
  },
  "version": "1.0.0",
  "packages": [
    {
      "registryType": "mcpb",
      "identifier": "https://github.com/crosstech-solutions-bv/swing-mcp/releases/download/V1.0.0/swing-mcp.mcpb",
      "fileSha256": "$sha256",
      "transport": { "type": "stdio" }
    }
  ]
}
"@
[System.IO.File]::WriteAllText("$PWD\server.json", $serverJson, [System.Text.UTF8Encoding]::new($false))
```

Verify no BOM — the first byte must be `123` (`{`), not `239 187 191` (`ï»¿`):

```powershell
Get-Content .\server.json -Encoding Byte -TotalCount 3   # PowerShell 5.1
# Get-Content .\server.json -AsByteStream -TotalCount 3  # PowerShell 7
```

Commit `server.json` to the repository so the file is available for `mcp-publisher publish` and
serves as a source of truth for future updates:

```powershell
git add server.json
git commit -m "chore: update server.json for v1.0.0 release"
git push
```

### Step 5 — Authenticate (GitHub device flow)

```powershell
.\mcp-publisher.exe login github
```

Visit https://github.com/login/device, enter the code shown, and authorize while logged in as
**CrossTech Solutions**.

> Registry JWTs are short-lived. Log in immediately before publishing — see
> [Troubleshooting](#troubleshooting) for the 401 error caused by an expired token.

### Step 6 — Publish

```powershell
.\mcp-publisher.exe publish
```

Expected output:

```
✓ Server io.github.crosstech-solutions-bv/swing-mcp version 1.0.0
```

### Step 7 — Verify

Query the registry API:

```powershell
Invoke-RestMethod "https://registry.modelcontextprotocol.io/v0.1/servers?search=io.github.crosstech-solutions-bv/swing-mcp" | ConvertTo-Json -Depth 10
```

Confirm the artifact URL is publicly accessible:

```powershell
Invoke-WebRequest -Uri "https://github.com/crosstech-solutions-bv/swing-mcp/releases/download/V1.0.0/swing-mcp.mcpb" -Method Head
```

## Troubleshooting

These are the four real errors encountered during the 1.0.0 publish.

### 1. `invalid character 'ï' looking for beginning of value`

**Full error:**

```
Error: invalid server.json: invalid character 'ï' looking for beginning of value
```

**Cause:** Windows PowerShell 5.1's `Set-Content -Encoding UTF8` prepends a UTF-8 BOM
(`EF BB BF`). The Go JSON decoder rejects the BOM (`0xEF` is rendered as `ï`). PowerShell 7
writes BOM-less UTF-8 by default.

**Fix:** Rewrite the file without the BOM:

```powershell
$json = Get-Content .\server.json -Raw
[System.IO.File]::WriteAllText("$PWD\server.json", $json, [System.Text.UTF8Encoding]::new($false))
```

> This error also affects `manifest.json` inside the `.mcpb` bundle. The registry won't catch it,
> but MCP clients may fail to parse the manifest at install time. Rebuilding the bundle changes the
> SHA-256, so re-upload with `--clobber` and update `fileSha256` in `server.json`.

### 2. 403 Forbidden — case-sensitive namespace

**Full error:**

```
403 Forbidden: You do not have permission to publish this server.
You have permission to publish: io.github.crosstech-solutions-bv/*.
Attempting to publish: solutions.crosstech/swing-mcp
```

**Cause:** The `name` field in `server.json` used the all-lowercase form `solutions.crosstech/`, but
the registry namespace is case-sensitive and must exactly match the GitHub login (`CrossTech Solutions`).

**Fix:** Correct the `name` field to `io.github.crosstech-solutions-bv/swing-mcp` and rewrite without a BOM:

```powershell
$json = (Get-Content .\server.json -Raw) -replace '"solutions.crosstech/swing-mcp"', '"io.github.crosstech-solutions-bv/swing-mcp"'
[System.IO.File]::WriteAllText("$PWD\server.json", $json, [System.Text.UTF8Encoding]::new($false))
```

Then republish.

### 3. 400 Bad Request — artifact not publicly accessible

**Full error:**

```
400 Bad Request: MCPB package '...' is not publicly accessible (status: 404)
```

**Cause:** The repository was private. Release assets on private repositories return 404 to
unauthenticated requests, and the registry validator fetches the artifact URL anonymously.

**Fix:** Make the repository public:

```powershell
gh repo edit crosstech-solutions-bv/swing-mcp --visibility public --accept-visibility-change-consequences
```

Confirm the asset is accessible before republishing:

```powershell
Invoke-WebRequest -Uri "https://github.com/crosstech-solutions-bv/swing-mcp/releases/download/V1.0.0/swing-mcp.mcpb" -Method Head
```

> **Note:** Making a repository public exposes all code, history, issues, and pull requests.
> A `LICENSE` file is expected for publicly-listed MCP servers.

### 4. 401 Unauthorized — expired JWT token

**Full error:**

```
401 Unauthorized: Invalid or expired Registry JWT token ... token is expired
```

**Cause:** Registry JWTs are short-lived. The token expired during troubleshooting between
`mcp-publisher login` and `mcp-publisher publish`.

**Fix:** Log in again immediately before publishing:

```powershell
.\mcp-publisher.exe login github
.\mcp-publisher.exe publish
```

## Future releases checklist

For each new release (replacing `1.0.0` / `V1.0.0` with the new version and tag):

1. Build the new JARs: `mvn clean package` (or trigger the release workflow by pushing a tag).
2. Update JAR filenames/versions in `mcpb-build\manifest.json` (BOM-less) and rebuild
   `swing-mcp.mcpb`.
3. Create the new GitHub release/tag and upload the `.mcpb` asset with `gh release upload`.
4. Recompute the SHA-256 (lowercase) and update `server.json`:
   - `version`
   - `packages[0].identifier` (new tag in the download URL)
   - `packages[0].fileSha256`
5. Run `mcp-publisher login github` — tokens are short-lived, log in right before publishing.
6. Run `mcp-publisher publish`.
7. Verify the new version appears in the registry API search.
8. Commit the updated `server.json`.

> **Tip:** These steps can be fully automated with GitHub Actions using the registry's OIDC-based
> flow, which avoids manual device-flow logins entirely. See the official guide on automating
> publishing with GitHub Actions under
> [`docs/modelcontextprotocol-io/`](https://github.com/modelcontextprotocol/registry/tree/main/docs/modelcontextprotocol-io)
> in the registry repository. The `publish-mcp.yml` workflow in this repository already implements
> this automated flow.

## References

- [Official MCP Registry](https://registry.modelcontextprotocol.io)
- [Registry repository & docs](https://github.com/modelcontextprotocol/registry) — quickstart,
  package types, authentication, and GitHub Actions guides under `docs/modelcontextprotocol-io/`
- [MCPB tooling](https://github.com/anthropics/mcpb)
- [This project's registry entry](https://registry.modelcontextprotocol.io/v0.1/servers?search=io.github.crosstech-solutions-bv/swing-mcp)
