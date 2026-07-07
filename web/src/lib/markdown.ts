import { marked } from 'marked';
import { unified } from 'unified';
import rehypeParse from 'rehype-parse';
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize';
import rehypeStringify from 'rehype-stringify';

interface RenderOptions {
  /**
   * Shift heading levels down by one (h1→h2, h2→h3, etc.) so the page title
   * remains the only h1. Useful for embedded readme content.
   */
  shiftHeadings?: boolean;
}

/**
 * Render markdown to sanitized HTML.
 *
 * Security: `readme_md` is attacker-controlled (from contributor SKILL.md).
 * We parse with `marked` (which produces HTML), then sanitize with
 * `rehype-sanitize` to strip `<script>`, `onerror`, `javascript:` URIs, etc.
 * The result is safe for `set:html`.
 */
export async function renderMarkdown(
  markdown: string,
  options: RenderOptions = {},
): Promise<string> {
  const rawHtml = await marked.parse(markdown, { async: true });

  const sanitized = await unified()
    .use(rehypeParse, { fragment: true })
    .use(rehypeSanitize, defaultSchema)
    .use(rehypeStringify)
    .process(rawHtml);

  let html = String(sanitized);
  if (options.shiftHeadings) {
    // shift headings down by one level: h1→h2, h2→h3, ... h6→h6 (can't go higher)
    for (let level = 5; level >= 1; level--) {
      const from = new RegExp(`<h${level}\\b([^>]*)>(.*?)</h${level}>`, 'g');
      const to = `<h${level + 1}$1>$2</h${level + 1}>`;
      html = html.replace(from, to);
    }
  }

  return html;
}
