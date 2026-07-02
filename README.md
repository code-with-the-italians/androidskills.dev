# androidskills.dev

A searchable index of AI coding skills for Android & Kotlin development.

> **Branch model:** [`main`](https://github.com/CWTI-Ltd/androidskills.dev/tree/main)
> serves the public landing page (GitHub Pages). All product development happens on
> [`develop`](https://github.com/CWTI-Ltd/androidskills.dev/tree/develop) until the app
> is ready to go live.

## Repository layout

```
api/    Kotlin/Ktor backend — REST API, SQLite (WAL), skill-review worker
web/    Astro frontend — static public pages + SSR for admin/auth   (coming next)
docs/   Architecture & design records
```

The full stack, hosting and data-model decisions live in
**[docs/architecture.md](docs/architecture.md)**.

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

Coming next — an Astro app reusing the design system, with a dev proxy to the API.
