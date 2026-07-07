import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { renderMarkdown } from './markdown.ts';

describe('renderMarkdown — XSS sanitization', () => {
  it('strips <script> tags', async () => {
    const html = await renderMarkdown(
      '# Hello\n\n<script>alert(1)</script>\n\nWorld',
    );
    assert.ok(!html.includes('<script'), `<script> survived: ${html}`);
    assert.ok(!html.includes('alert(1)'), `alert() survived: ${html}`);
    assert.ok(html.includes('Hello'));
    assert.ok(html.includes('World'));
  });

  it('strips <img onerror> event handlers', async () => {
    const html = await renderMarkdown('<img src=x onerror=alert(1)>');
    assert.ok(!html.includes('onerror'), `onerror survived: ${html}`);
    assert.ok(!html.includes('alert(1)'), `alert() survived: ${html}`);
  });

  it('strips javascript: URIs in links', async () => {
    const html = await renderMarkdown('[click me](javascript:alert(1))');
    assert.ok(!html.includes('javascript:'), `javascript: survived: ${html}`);
    assert.ok(!html.includes('alert(1)'), `alert() survived: ${html}`);
  });

  it('strips javascript: URIs in raw <a> tags', async () => {
    const html = await renderMarkdown(
      '<a href="javascript:alert(1)">click</a>',
    );
    assert.ok(!html.includes('javascript:'), `javascript: survived: ${html}`);
    assert.ok(!html.includes('alert(1)'), `alert() survived: ${html}`);
  });

  it('strips inline event-handler attributes', async () => {
    const html = await renderMarkdown('<div onclick="alert(1)">text</div>');
    assert.ok(!html.includes('onclick'), `onclick survived: ${html}`);
    assert.ok(!html.includes('alert(1)'), `alert() survived: ${html}`);
    assert.ok(html.includes('text'));
  });

  it('preserves safe markdown (headings, code, links)', async () => {
    const html = await renderMarkdown(
      '# Title\n\nSome **bold** text.\n\n[link](https://example.com)\n\n`code`',
    );
    assert.ok(html.includes('<h2>'), 'heading lost');
    assert.ok(html.includes('<strong>bold</strong>'), 'bold lost');
    assert.ok(html.includes('href="https://example.com"'), 'safe link lost');
    assert.ok(html.includes('<code>code</code>'), 'code lost');
  });
});
