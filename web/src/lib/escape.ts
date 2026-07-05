/**
 * Shared HTML escape helper.
 *
 * Used by client-side code before assigning to innerHTML. Escapes the characters that matter for
 * both text content and double-quoted attribute values: <, >, &, ", '.
 *
 * null/undefined are coerced to the empty string; numbers and booleans are stringified.
 */
export function esc(value: unknown): string {
  if (value == null) return '';
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
