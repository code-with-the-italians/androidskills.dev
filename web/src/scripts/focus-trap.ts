export function createFocusTrap(container: HTMLElement) {
  const focusableSelectors = [
    'a[href]',
    'button:not([disabled])',
    'input:not([disabled])',
    'select:not([disabled])',
    'textarea:not([disabled])',
    '[tabindex]:not([tabindex="-1"])',
  ];

  function getFocusable() {
    return Array.from(
      container.querySelectorAll<HTMLElement>(focusableSelectors.join(',')),
    ).filter((el) => {
      const style = window.getComputedStyle(el);
      return style.display !== 'none' && style.visibility !== 'hidden';
    });
  }

  let lastFocused: HTMLElement | null = null;

  function handleKey(e: KeyboardEvent) {
    if (e.key !== 'Tab') return;
    const focusable = getFocusable();
    if (focusable.length === 0) return;

    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement as HTMLElement | null;

    if (e.shiftKey && active === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && active === last) {
      e.preventDefault();
      first.focus();
    }
  }

  function activate() {
    lastFocused = document.activeElement as HTMLElement | null;
    container.addEventListener('keydown', handleKey);
    const focusable = getFocusable();
    if (focusable.length > 0) focusable[0].focus();
  }

  function deactivate(returnFocus = true) {
    container.removeEventListener('keydown', handleKey);
    if (returnFocus && lastFocused) lastFocused.focus();
  }

  return { activate, deactivate };
}
