function getSettings() {
  try {
    return JSON.parse(localStorage.getItem('androidskills') || '{}');
  } catch {
    return {};
  }
}

function saveSettings(partial: Record<string, string>) {
  const s = getSettings();
  Object.assign(s, partial);
  localStorage.setItem('androidskills', JSON.stringify(s));
  applySettings();
}

function applySettings() {
  const s = getSettings();
  const r = document.documentElement;
  if (s.theme && s.theme !== 'system') r.setAttribute('data-theme', s.theme);
  else r.removeAttribute('data-theme');
  if (s.accent && s.accent !== 'green') r.setAttribute('data-accent', s.accent);
  else r.removeAttribute('data-accent');
  if (s.density && s.density !== 'regular')
    r.setAttribute('data-density', s.density);
  else r.removeAttribute('data-density');
  syncToggles();
}

function resolvedDark() {
  const s = getSettings();
  if (s.theme === 'dark') return true;
  if (s.theme === 'light') return false;
  return window.matchMedia('(prefers-color-scheme: dark)').matches;
}

function syncToggles() {
  const s = getSettings();
  document
    .querySelectorAll<HTMLInputElement>('[data-tweak]')
    .forEach((input) => {
      const key = input.getAttribute('data-tweak')!;
      input.value = s[key] || 'system';
    });
}

function initTweaks() {
  document
    .querySelectorAll<HTMLInputElement>('[data-tweak]')
    .forEach((input) => {
      input.addEventListener('change', () => {
        const key = input.getAttribute('data-tweak')!;
        saveSettings({ [key]: input.value });
      });
    });

  document.querySelectorAll('[data-theme-toggle]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const next = resolvedDark() ? 'light' : 'dark';
      saveSettings({ theme: next });
    });
  });

  applySettings();
}

initTweaks();
