# Experimental zstdnet transport rework for AutoModpack 4.0.6

This reference implementation targets Minecraft 1.20.1 Forge and was tested with zstdnet 1.4.8.

## Problem

When the Minecraft connection is made through a zstdnet local proxy, AutoModpack can complete version negotiation, TLS verification, and the initial modpack manifest request. It then saves the local endpoint (`127.0.0.1:<temporary-port>`) as the modpack address. The login connection is disconnected before the user starts the update, zstdnet releases that listener, and the later download pool tries to reconnect to the stale port. The result is `java.net.ConnectException: Connection refused`.

The affected path is:

```text
Minecraft client
  -> temporary zstdnet listener on 127.0.0.1
  -> FRP/public zstdnet endpoint
  -> zstdnet server
  -> Minecraft backend
```

## Reference implementation

- `DownloadRoutes` resolves the download route once during login and distinguishes the public game endpoint, a temporary local proxy, and an explicitly configured download service.
- `DownloadRoute` persists the endpoint, TCP/ZSTD transport, and TLS/AMMH/Minecraft handshake mode. Manifest requests, the download pool, retries, and startup updates use the same route.
- `DownloadTransport` owns socket creation, transport handshake, TLS setup, and failure cleanup.
- zstdnet RAW mode keeps the public endpoint but uses TCP plus AMMH. An explicitly configured download host or port remains an independent TCP service.
- The login handler captures the local proxy synchronously while it is alive, then moves certificate and file work off the Minecraft network event loop.
- ZSTD stream wrappers no longer recursively close their owning socket, avoiding native stream double-free during repeated reconnects.
- Protocol decoding supports fragmented input, refresh requests spanning compressed frames, and empty refresh lists. Failed connections are removed from the pool.
- The merge task depends on fresh loader and platform JARs so the distributable cannot silently contain a stale outer core.

The zstdnet-specific reflection is isolated in `ZstdNetCompatibility`. It currently targets zstdnet 1.4.8 because that version does not expose a public route-discovery API.

## Verification

- Core suite: 57 tests, 0 failures, 0 skipped.
- Tests run against the distributable JAR: 25 tests, 0 failures, 0 skipped.
- Covered shared Minecraft port, dedicated TLS download port, transparent TCP forwarding, PROXY v1, and PROXY v2.
- Each transport integration covers manifest retrieval, certificate callback, a three-connection pool, file downloads, refresh, persisted-route reload, and reconnect.
- An integration test uses the installed zstdnet 1.4.8 `ServerProxyRuntime` for handshake rewriting, forwarded IP handling, manifest retrieval, parallel downloads, refresh, shutdown, and reconnect.
- A lifecycle test opens, flushes, and closes ZSTD sockets 100 times in one JVM.
- Route tests cover IPv6 scope IDs, RAW mode, explicit download ports, loopback replacement, and migration of old address data.

The packaged test used the Minecraft 1.20.1 dependency versions Netty 4.1.82.Final and Gson 2.10. The final JAR was also inspected to confirm that the outer loader, inner mod, current login route code, certificate helper, and embedded native library are present.

## Limits

The isolated file server used in tests disables player-secret validation; production code keeps the existing authentication and TLS certificate checks. Transparent TCP and PROXY behavior was tested, but the test suite does not launch an FRP binary. HTTP/WebSocket proxies and Minecraft-aware BungeeCord/Velocity routing need a separately reachable AutoModpack download endpoint.

A complete real modpack login and unknown zstdnet versions have not been claimed as verified. This is a reference implementation for maintainers to review, adapt, or use while designing a public integration API with zstdnet.

## Build and verification

Gradle runs with Java 21 while compiled game code still targets Java 17.

```powershell
.\gradlew.bat --no-configure-on-demand :1.20.1-forge:build
python scripts/verify_release.py merged/automodpack-mc1.20.1-forge-4.0.6.jar
```

The real-proxy tests accept `-PzstdnetJar=<absolute zstdnet jar>` and `-PzstdnetLoggingJar=<absolute Mojang logging jar>`. The distributable test uses `:core:packagedTest -PpackagedJar=<absolute artifact> -PtestNettyVersion=4.1.82.Final` with the same proxy test properties.

Reference artifact SHA-256:

```text
697eb39df1b732e740f588b53c927e8a212eed13d2982bd22c0bbc45d5f7c351
```
