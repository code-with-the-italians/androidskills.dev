import { defineConfig } from 'astro/config';
import node from '@astrojs/node';

// https://astro.build/config
export default defineConfig({
  output: 'server',
  adapter: node({
    mode: 'standalone',
  }),
  server: {
    port: 4321,
    host: '0.0.0.0',
  },
  security: {
    // Astro's checkOrigin compares the request Origin to the Host header. Behind our reverse proxy
    // (Cloudflare Tunnel / kamal-proxy) the Node server sees the internal host (e.g. localhost:4321),
    // not the public origin, so every proxied POST (scan, drafts, submit) is wrongly rejected as
    // cross-site while GETs pass. CSRF is already covered by the session cookie's SameSite=Lax
    // attribute (a cross-site POST can't carry it -> Ktor 401), so this check is redundant here.
    // (Safe while the session cookie stays host-only, the default; if SESSION_COOKIE_DOMAIN is ever
    // set to a shared parent domain, restore an origin/Host check.)
    checkOrigin: false,
  },
});
