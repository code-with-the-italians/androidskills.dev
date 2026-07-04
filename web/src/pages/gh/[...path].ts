import type { APIRoute } from 'astro';

const API_URL = (process.env.API_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');

export const ALL: APIRoute = async ({ request, params }) => {
  const path = (params.path as string | undefined) || '';
  const upstream = new URL('/gh/' + path, API_URL);
  upstream.search = new URL(request.url).search;
  return proxy(upstream, request);
};

async function proxy(upstream: URL, request: Request): Promise<Response> {
  const headers = new Headers(request.headers);
  headers.delete('host');
  headers.delete('cf-connecting-ip');
  headers.delete('x-forwarded-for');
  headers.delete('x-real-ip');

  const upstreamResponse = await fetch(upstream.toString(), {
    method: request.method,
    headers,
    body: request.method === 'GET' || request.method === 'HEAD' ? null : request.body,
  });

  return new Response(upstreamResponse.body, {
    status: upstreamResponse.status,
    statusText: upstreamResponse.statusText,
    headers: upstreamResponse.headers,
  });
}
