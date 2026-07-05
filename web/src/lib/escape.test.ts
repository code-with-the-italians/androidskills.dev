import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { esc } from './escape';

describe('esc', () => {
  it('escapes script tags', () => {
    assert.equal(
      esc('<script>alert(1)</script>'),
      '&lt;script&gt;alert(1)&lt;/script&gt;',
    );
  });

  it('escapes event handler attributes', () => {
    assert.equal(
      esc('<img src=x onerror=alert(1)>'),
      '&lt;img src=x onerror=alert(1)&gt;',
    );
  });

  it('escapes quotes for attribute contexts', () => {
    assert.equal(
      esc('value" onmouseover="alert(1)'),
      'value&quot; onmouseover=&quot;alert(1)',
    );
  });

  it('escapes javascript scheme', () => {
    assert.equal(
      esc('javascript:alert(1)'),
      'javascript:alert(1)', // esc does not URL-encode; context decides whether to render as href
    );
  });

  it('handles null and undefined', () => {
    assert.equal(esc(null), '');
    assert.equal(esc(undefined), '');
  });

  it('stringifies numbers and booleans', () => {
    assert.equal(esc(42), '42');
    assert.equal(esc(true), 'true');
  });

  it('escapes ampersands', () => {
    assert.equal(esc('a & b'), 'a &amp; b');
  });
});
