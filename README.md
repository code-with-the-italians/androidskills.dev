# androidskills.dev

A searchable index of AI coding skills for Android & Kotlin development.

> **Branch model:** all development targets `develop`. `main` is reserved for
> the production site once Kamal is live.

## Repository layout

```
api/      Kotlin/Ktor backend — REST API, SQLite (WAL), skill-review worker
web/      Astro frontend — static public pages + SSR for admin/auth
deploy/   Kamal 2.x deployment config + secrets templates
docs/     Architecture & design records
```

The full stack, hosting and data-model decisions live in
**[docs/architecture.md](docs/architecture.md)**. Deploy instructions live in
**[deploy/README.md](deploy/README.md)**.

## Local development

Prerequisites: a JDK 21 (the build is verified against JetBrains Runtime 21) and
[Node](https://nodejs.org) (pin via [`fnm`](https://github.com/Schniz/fnm) + the
repo's `.node-version`).

### API (`api/`)

```sh
# from the repo root, with JAVA_HOME pointing at a JDK 21
api/gradlew -p api run        # serves http://localhost:8080
api/gradlew -p api build      # compile + test
```

Health check:

```sh
curl http://localhost:8080/api/health
# {"ok":true,"version":"0.0.1-local","db":"wal","fileStore":"local-fs","llm":"stub"}
```

Cloud dependencies are abstracted for local dev: file storage runs on the local
filesystem (Cloudflare R2 in prod) and the skill-review LLM is stubbed (an
OpenAI-compatible endpoint in prod). Both swap in via configuration, not code.

### Web (`web/`)

A skeleton Astro app is present. It is not yet connected to the API; that happens
in the next step.

```sh
npm install --prefix web
npm run --prefix web build
```

For local development it can be run with `npm run --prefix web dev`; in
production it is served behind Kamal as a Node SSR app.
