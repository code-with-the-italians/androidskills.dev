import type { APIRoute } from 'astro';

const API_URL = (process.env.API_URL || 'http://127.0.0.1:8080').replace(
  /\/$/,
  '',
);

export function createProxy(prefix: string): APIRoute {
  return async ({ request, params }) => {
    const path = (params.path as string | undefined) || '';
    if (path.includes('..') || path.startsWith('/')) {
      return new Response('Bad request', { status: 400 });
    }
    const upstream = new URL(prefix + '/' + path, API_URL);
    upstream.search = new URL(request.url).search;
    return proxy(upstream, request);
  };
}

async function proxy(upstream: URL, request: Request): Promise<Response> {
  const headers = new Headers(request.headers);
  headers.delete('host');
  headers.delete('cf-connecting-ip');
  headers.delete('x-real-ip');

  const init: RequestInit & { duplex?: 'half' } = {
    method: request.method,
    headers,
    redirect: 'manual',
  };

  if (request.method !== 'GET' && request.method !== 'HEAD') {
    init.body = request.body;
    init.duplex = 'half';
  }

  let upstreamResponse: Response;
  try {
    upstreamResponse = await fetch(upstream.toString(), init);
  } catch (err) {
    console.error('Proxy fetch failed:', err);
    return new Response('Bad gateway', { status: 502 });
  }

  const responseHeaders = new Headers();
  upstreamResponse.headers.forEach((value, key) => {
    if (key.toLowerCase() !== 'set-cookie') {
      responseHeaders.append(key, value);
    }
  });
  const setCookies = upstreamResponse.headers.getSetCookie();
  setCookies.forEach((c) => responseHeaders.append('Set-Cookie', c));

  return new Response(upstreamResponse.body, {
    status: upstreamResponse.status,
    statusText: upstreamResponse.statusText,
    headers: responseHeaders,
  });
}
