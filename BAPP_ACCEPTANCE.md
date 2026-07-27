# BApp Store acceptance audit

This document maps Burp OmniBridge to PortSwigger's
[BApp Store acceptance criteria](https://portswigger.net/burp/documentation/desktop/extend-burp/extensions/creating/bapp-store-acceptance-criteria).
Acceptance remains a PortSwigger review decision, particularly for the subjective uniqueness
criterion, but the implementation is designed to satisfy every applicable technical criterion.

## Criteria mapping

| Criterion | OmniBridge evidence |
|---|---|
| Unique function | Provides authenticated, module-gated Montoya workflows over both versioned REST and MCP, binary-safe message transport, live server controls, capability discovery, and generated OpenAPI documentation in one extension. |
| Clear name | Extension name is **Burp OmniBridge** and its suite tab is **OmniBridge**. |
| Secure operation | A fresh 256-bit API key protects every operational route; comparison is constant-time; CORS is disabled; JSON is strict; request bodies, registries, pagination, and regexes are bounded. Dangerous ShellUtils, shutdown, unload, persistence, and AI routes are excluded. Configuration tools are separately gated, disabled by default, and carry a code-execution warning. |
| All dependencies | `burp-omnibridge.jar` is a reproducible Shadow JAR containing Javalin, Jetty, Jackson, RE2/J, OpenAPI, and Swagger WebJar assets. Only the Burp-provided Montoya artifact is `compileOnly`. `verifyShadowJar` enforces this layout. |
| Responsive threading | Server startup, shutdown, and restart run on the dedicated `omnibridge-server-lifecycle` executor. Requests run on Jetty workers. No target HTTP or other slow operation runs on Swing's EDT. |
| Clean unloading | `OmniBridgeUnloadHandler` implements `ExtensionUnloadingHandler`; it idempotently stops Javalin, closes extension WebSockets, clears registries, and shuts down the lifecycle executor. An automated test verifies that the port can immediately be rebound. |
| Burp networking | Outbound HTTP uses Montoya `api.http().sendRequest(...)`, the modern equivalent of legacy `issueHttpRequest()`. WebSockets and Collaborator use their Montoya APIs. Production code contains no raw HTTP client. |
| Offline working | The extension downloads no definitions or runtime assets. Swagger UI is bundled as a WebJar and served locally. Collaborator and target traffic occur only after an authenticated user request. |
| Large projects | History/site-map/issue responses are paginated with a maximum page of 500; only the selected page is converted or base64-encoded; safe native filters are used where Montoya provides them; HTTP messages are not retained by handlers. |
| GUI parenting | Dialogs use `api.userInterface().swingUtils().suiteFrame()` as their parent, and all Swing component work is marshaled to the EDT. |
| Montoya artifact | Gradle references `net.portswigger.burp.extensions:montoya-api:2026.7` as `compileOnly`. Legacy Extender APIs are not used. |
| Burp AI | Not applicable. OmniBridge contains no AI integration. |

## REST and MCP architecture

- The Javalin/Jetty listener is instantiated inside the extension and defaults to
  `127.0.0.1:31337`.
- Bind address and port can be changed explicitly in the OmniBridge tab and only take effect after a
  controlled restart.
- REST and MCP can be enabled independently in the suite tab and share the same listener, API key,
  module gates, and operation limits.
- Operational endpoints require either the recommended `X-API-Key` header or the `apiKey` query
  parameter intended for browser GET requests; both use the same constant-time credential check.
  `/health`, `/api/v1/swagger`, `/api/v1/openapi`, and the bundled Swagger UI assets under
  `/webjars/swagger-ui/` are the only public resources.
- Versioned REST routes live under `/api/v1`; `/mcp` is an authenticated stateless Streamable HTTP
  endpoint. Legacy REST paths remain deprecated aliases during the 0.x transition.
- The fat JAR is the sole runtime installation artifact. No external server, framework, or asset
  installation is required.

## Verification commands

```shell
./gradlew clean build
./gradlew verifyShadowJar
```

The test suite covers authentication and key rotation, independent interface switches, MCP
initialization and tool discovery, disabled modules, strict JSON,
unsupported-capability responses, pagination and binary handling, background lifecycle transitions,
Swagger/OpenAPI availability, and listener-port release during unload.
