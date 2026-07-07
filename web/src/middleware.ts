import { defineMiddleware } from 'astro:middleware';

export const onRequest = defineMiddleware(async (_ctx, next) => {
  const res = await next();
  res.headers.set('X-Frame-Options', 'DENY');
  res.headers.set('X-Content-Type-Options', 'nosniff');
  res.headers.set('Referrer-Policy', 'strict-origin-when-cross-origin');
  res.headers.set(
    'Content-Security-Policy',
    "script-src 'self' 'unsafe-inline' 'unsafe-eval' http://localhost:8400; " +
      "connect-src 'self' http://localhost:8400; " +
      "frame-ancestors 'none'; object-src 'none'; base-uri 'none'",
  );
  return res;
});
