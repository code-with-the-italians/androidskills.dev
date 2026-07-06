import { createFocusTrap } from './focus-trap';

export function initDrawer() {
  const openBtn = document.querySelector(
    '[data-menu-open]',
  ) as HTMLElement | null;
  const backdrop = document.querySelector(
    '.drawer-backdrop',
  ) as HTMLElement | null;
  const panel = document.querySelector('.drawer-panel') as HTMLElement | null;
  const closeBtn = panel?.querySelector(
    '[data-menu-close]',
  ) as HTMLElement | null;

  if (!openBtn || !backdrop || !panel) return;

  const trap = createFocusTrap(panel);
  const reducedMotion = window.matchMedia(
    '(prefers-reduced-motion: reduce)',
  ).matches;

  function open() {
    backdrop!.classList.add('open');
    panel!.classList.add('open');
    document.body.style.overflow = 'hidden';
    trap.activate();
  }

  function close() {
    backdrop!.classList.remove('open');
    panel!.classList.remove('open');
    document.body.style.overflow = '';
    trap.deactivate();
  }

  openBtn.addEventListener('click', open);
  closeBtn?.addEventListener('click', close);
  backdrop.addEventListener('click', close);
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && panel.classList.contains('open')) close();
  });

  // Close on route change (Astro does not fire a full page load, but a click on a link does)
  panel.querySelectorAll('a').forEach((a) => {
    a.addEventListener('click', () => {
      if (!reducedMotion) {
        setTimeout(close, 100);
      } else {
        close();
      }
    });
  });
}

initDrawer();
