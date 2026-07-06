import type { APIRoute } from 'astro';
import { createApiClient } from '../lib/api';

const STATIC_PATHS = [
  '/',
  '/search',
  '/trends',
  '/timeline',
  '/about',
  '/contact',
  '/cli',
  '/bundles',
];

export const GET: APIRoute = async ({ request, site }) => {
  const baseUrl = (site?.toString() || new URL(request.url).origin).replace(
    /\/$/,
    '',
  );
  const api = createApiClient(request);

  const urls: Array<{ loc: string; lastmod?: string; priority: number }> =
    STATIC_PATHS.map((path) => ({
      loc: `${baseUrl}${path}`,
      priority: path === '/' ? 1.0 : 0.7,
    }));

  try {
    const [skills, categories] = await Promise.all([
      api.searchSkills({ pageSize: 1000 }),
      api.getCategories(),
    ]);

    skills.items.forEach((skill) => {
      urls.push({
        loc: `${baseUrl}/skill/${encodeURIComponent(skill.slug)}`,
        lastmod: skill.updatedAt,
        priority: 0.8,
      });
    });

    categories.forEach((cat) => {
      urls.push({
        loc: `${baseUrl}/search?cat=${encodeURIComponent(cat.slug)}`,
        priority: 0.6,
      });
    });
  } catch {
    // Sitemap still contains static paths if API fails
  }

  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${urls
  .map(
    (u) => `  <url>
    <loc>${u.loc}</loc>
    ${u.lastmod ? `<lastmod>${u.lastmod}</lastmod>` : ''}
    <priority>${u.priority.toFixed(1)}</priority>
  </url>`,
  )
  .join('\n')}
</urlset>`;

  return new Response(xml, {
    headers: {
      'Content-Type': 'application/xml',
    },
  });
};
