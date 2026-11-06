# MCPDroid

An Android app that runs an [MCP](https://modelcontextprotocol.io) (Model Context Protocol) server directly on your phone, with a **built-in Cloudflare Tunnel** (the official `cloudflared`, patched to run on Android: see [cloudflared-android](https://github.com/robot3636/cloudflared-android)) that lets you create channels to connect the MCP server to AI assistants such as Claude.

Built with Kotlin, Jetpack Compose (Material 3) and Ktor.

> **Built entirely on an Android phone using [Code on the Go](https://github.com/appdevforall/CodeOnTheGo).**

## Features

- **Built-in Cloudflare Tunnel**: no separate setup needed; create tunnels (channels) that expose the MCP server so AI assistants can reach it from anywhere.
- **MCP server on-device**: JSON-RPC handler running as a foreground service.
- **File tools**: list, read, write, create, delete and download files inside user-approved workspaces.
- **Termux integration**: run shell commands in Termux and get the output back.
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
2. Create the patched `cloudflared` binary (it is not committed because it is 36 MB). It is the official release with a 16-byte DNS patch so it can run on Android; the script and a full explanation are in [robot3636/cloudflared-android](https://github.com/robot3636/cloudflared-android):
   ```bash
   git clone https://github.com/robot3636/cloudflared-android
   bash cloudflared-android/patch-cloudflared.sh -o app/src/main/jniLibs/arm64-v8a/libcloudflared.so
   ```
3. (Optional, for release builds) create a `release.properties` file with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. This file and your keystore are git-ignored and must never be committed.
4. Build with `./gradlew assembleDebug`.

## License

Released under the [MIT License](LICENSE): free to use, modify and distribute.

Third-party components keep their own licenses, see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Status

Work in progress.
