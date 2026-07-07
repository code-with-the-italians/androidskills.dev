export type ToastType = 'default' | 'success' | 'error' | 'warn';

const region = document.querySelector('.toast-region');
const reducedMotion =
  typeof window !== 'undefined' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches;

function createToast(message: string, type: ToastType = 'default') {
  const el = document.createElement('div');
  el.className = `toast anim-toast ${type}`;
  el.textContent = message;
  return el;
}

export function showToast(message: string, type: ToastType = 'default') {
  if (!region) return;
  const el = createToast(message, type);
  region.appendChild(el);

  const duration = reducedMotion ? 2200 : 2200;
  setTimeout(() => {
    el.remove();
  }, duration);
}

export function showError(message: string) {
  showToast(message, 'error');
}

export function showSuccess(message: string) {
  showToast(message, 'success');
}
