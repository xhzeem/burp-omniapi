# Security policy

## Deployment

Burp OmniAPI can perform security-sensitive actions inside the active Burp project. Treat its API
key like a privileged local credential.

Use the `X-API-Key` header whenever possible. OmniAPI also accepts an `apiKey` query parameter for
browser-based GET requests, but URLs can be retained in browser history, proxy history, bookmarks,
screenshots, and access logs. Keep query-key URLs on loopback, do not share them, and regenerate the
key after any suspected disclosure.

The supported default is `127.0.0.1:31337`. If the server is bound to a non-loopback interface:

1. restrict inbound access with host and network controls;
2. terminate TLS using a trusted reverse proxy;
3. rotate the API key after any suspected disclosure;
4. never expose the Swagger UI or API directly to the public internet.

The extension deliberately excludes Montoya's shell execution, application shutdown, extension
unload, option import/export, AI prompt, and persistence primitives.

## Reporting

Do not include real target traffic, Collaborator secrets, API keys, or Burp project data in a public
issue. Provide a minimal reproduction with synthetic HTTP messages and the Burp/OmniAPI versions.
