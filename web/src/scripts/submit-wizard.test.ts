import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { esc } from '../lib/escape';

describe('submit-wizard esc()', () => {
  it('escapes HTML metacharacters', () => {
    assert.equal(esc('<script>'), '&lt;script&gt;');
    assert.equal(esc('a & b'), 'a &amp; b');
    assert.equal(esc('"quoted"'), '&quot;quoted&quot;');
    assert.equal(esc("it's"), 'it&#39;s');
  });

  it('neutralizes event-handler payloads', () => {
    const input = "<img src=x onerror=alert('xss')>";
    const out = esc(input);
    assert.ok(out.startsWith('&lt;img'));
    assert.ok(out.endsWith('&gt;'));
    assert.ok(!out.includes('<img'));
  });

  it('neutralizes javascript: URI payloads', () => {
    const input = '<a href="javascript:alert(1)">click</a>';
    const out = esc(input);
    assert.ok(!out.includes('<a'));
    assert.ok(!out.includes('"'));
    assert.ok(out.includes('javascript:'));
  });

  it('handles null/undefined/numbers', () => {
    assert.equal(esc(null), '');
    assert.equal(esc(undefined), '');
    assert.equal(esc(42), '42');
  });
});
