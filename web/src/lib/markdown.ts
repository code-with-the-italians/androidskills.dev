import { marked } from 'marked';
import { unified } from 'unified';
import rehypeParse from 'rehype-parse';
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize';
import rehypeStringify from 'rehype-stringify';

/**
 * Render markdown to sanitized HTML.
 *
 * Security: `readme_md` is attacker-controlled (from contributor SKILL.md).
 * We parse with `marked` (which produces HTML), then sanitize with
 * `rehype-sanitize` to strip `<script>`, `onerror`, `javascript:` URIs, etc.
 * The result is safe for `set:html`.
 */
export async function renderMarkdown(markdown: string): Promise<string> {
  const renderer = new marked.Renderer();
  renderer.heading = ({ text, depth }: { text: string; depth: number }) => {
    const shifted = Math.min(depth + 1, 6);
    return `<h${shifted}>${text}</h${shifted}>`;
  };
  const rawHtml = await marked.parse(markdown, { async: true, renderer });

  const sanitized = await unified()
    .use(rehypeParse, { fragment: true })
    .use(rehypeSanitize, defaultSchema)
    .use(rehypeStringify)
    .process(rawHtml);

  return String(sanitized);
}
