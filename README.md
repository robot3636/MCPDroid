# MCPDroid

An Android app that runs an [MCP](https://modelcontextprotocol.io) (Model Context Protocol) server directly on your phone, so an AI assistant such as Claude can work with your device's files and Termux.

Built with Kotlin, Jetpack Compose (Material 3) and Ktor.

## Features

- **MCP server on-device**: JSON-RPC handler running as a foreground service.
- **File tools**: list, read, write, create, delete and download files inside user-approved workspaces.
- **Termux integration**: run shell commands in Termux and get the output back.
- **Remote access**: optional Cloudflare tunnel so the server is reachable from the internet.
- **Authentication**: OAuth flow with token storage; secrets are kept in encrypted storage.
- **Dashboard UI**: server status, activity log, logs and per-tool settings.

## Project structure

```
app/src/main/java/com/miku/mcp/
├── mcp/        # JSON-RPC and MCP protocol handling
├── server/     # HTTP routes, OAuth and token stores
├── tools/      # File tools, workspace filesystem, Termux runner
├── tunnel/     # Cloudflare tunnel provider
├── security/   # Secret storage
├── service/    # Foreground service and server state
└── ui/         # Compose screens and theme
```

## Build

1. Clone the repository.
2. Provide the `cloudflared` binary as `app/src/main/jniLibs/arm64-v8a/libcloudflared.so` (it is not committed).
3. (Optional, for release builds) create a `release.properties` file with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. This file and your keystore are git-ignored and must never be committed.
4. Build with `./gradlew assembleDebug`.

## Status

Work in progress.
