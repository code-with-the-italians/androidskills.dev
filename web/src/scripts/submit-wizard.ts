import { esc } from '../lib/escape';
import { showToast, showSuccess, showError } from './toast';

interface Repo {
  owner: string;
  name: string;
  fullName: string;
  defaultBranch: string | null;
}
interface ReposResponse {
  repos: Repo[];
}
interface DetectedSkill {
  slug: string;
  name: string;
  description: string;
  license: string | null;
  tags: string[];
  version: string;
  tokenUpfront: number;
  tokenOndemand: number;
  fileCount: number;
}
interface ScanResponse {
  slug: string;
  commitSha: string;
  skills: DetectedSkill[];
}
const CHECK =
  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg>';

/**
 * Escape helper is imported from ../lib/escape so there is one source of truth
 * across client-side scripts.
 */
const state = {
  step: 1,
  repo: null as Repo | null,
  repos: null as ReposResponse | null,
  scan: null as ScanResponse | null,
  error: null as string | null,
  checking: null as string | null,
};

const vsteps =
  typeof document !== 'undefined'
    ? (document.getElementById('vsteps') as HTMLElement)
    : null;
const steps = vsteps
  ? (Array.from(vsteps.querySelectorAll('.vstep')) as HTMLElement[])
  : [];

async function loadRepos() {
  const res = await fetch('/api/me/repos');
  if (!res.ok) throw new Error(await res.text());
  state.repos = (await res.json()) as ReposResponse;
}

function renderStep1() {
  const container = document.getElementById('step1content') as HTMLElement;
  if (!container) return;
  if (!state.repos) {
    container.innerHTML =
      '<div class="state"><span class="spinner"></span><p>Loading repositories...</p></div>';
    return;
  }
  const repos = state.repos.repos || [];
  if (repos.length === 0) {
    container.innerHTML =
      '<div class="state"><p class="muted">No repositories found. Install the GitHub App and grant access to a repo with a top-level <code>skills/</code> directory.</p></div>';
    return;
  }
  container.innerHTML = `
    <p class="hint" style="margin-bottom:12px;line-height:1.55;">Pick a repository with one or more skills in a top-level <code style="font-family:var(--mono);font-size:11px;">skills/</code> directory.</p>
    <div class="tbl-wrap" style="border:1px solid var(--border);border-radius:var(--r-md);overflow:hidden;">
      <div style="padding:10px 12px;border-bottom:1px solid var(--border);">
        <div class="search search-sm">
          <svg class="ic-search" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.2-3.2" stroke-linecap="round"/></svg>
          <input id="repoFilter" type="search" placeholder="Filter your repositories…" autocomplete="off" style="width:100%;">
        </div>
      </div>
      <div id="repoList"></div>
    </div>
    <div id="repoStatus" style="margin-top:10px;"></div>
  `;
  renderRepoList();
}

function renderRepoList() {
  const input = document.getElementById(
    'repoFilter',
  ) as HTMLInputElement | null;
  const filter = input?.value?.toLowerCase() || '';
  const list = document.getElementById('repoList') as HTMLElement;
  if (!list || !state.repos) return;
  const repos = state.repos.repos.filter((r) =>
    (r.fullName + ' ' + (r.defaultBranch || '')).toLowerCase().includes(filter),
  );
  list.innerHTML = repos
    .map(
      (r) => `
    <div class="repo ${state.repo?.fullName === r.fullName ? ' sel' : ''}" data-repo="${esc(r.fullName)}" role="button" tabindex="0" aria-pressed="${state.repo?.fullName === r.fullName ? 'true' : 'false'}" aria-label="Select repository ${esc(r.fullName)}">
      <span class="radio" aria-hidden="true"></span>
      <div style="flex:1;min-width:0;">
        <div style="font-weight:600;font-size:14px;">${esc(r.fullName)}</div>
        <div class="slug" style="font-size:11.5px;">${esc(r.defaultBranch || 'default branch')}</div>
      </div>
    </div>
  `,
    )
    .join('');
  if (repos.length === 0) {
    list.innerHTML =
      '<div class="state" style="padding:20px;"><p class="muted">No repositories match.</p></div>';
  }
}

async function scanRepo(repo: Repo) {
  state.repo = repo;
  state.error = null;
  state.checking = repo.fullName;
  renderStep1();
  try {
    const res = await fetch(
      `/api/me/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.name)}/scan`,
      { method: 'POST' },
    );
    if (!res.ok) throw new Error(await res.text());
    state.scan = (await res.json()) as ScanResponse;
    state.step = 2;
  } catch (e) {
    state.error = e instanceof Error ? e.message : 'Scan failed';
  } finally {
    state.checking = null;
  }
  render();
}

function renderStep2() {
  const container = document.getElementById('step2content') as HTMLElement;
  if (!container) return;
  if (!state.scan) {
    container.innerHTML =
      '<div class="state"><p class="muted">Select a repository to detect skills.</p></div>';
    return;
  }
  const skills = state.scan.skills || [];
  if (skills.length === 0) {
    container.innerHTML =
      '<div class="callout" style="background:var(--danger-soft);border-color:transparent;align-items:flex-start;"><svg class="ci" viewBox="0 0 24 24" fill="none" stroke="var(--danger)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 9v4M12 17h.01M10.3 3.9 2.4 18a2 2 0 0 0 1.7 3h15.8a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/></svg><div><b>No skill found.</b> A skill is a folder containing a SKILL.md inside a top-level skills/ directory.</div></div>';
    return;
  }
  const items = skills
    .map(
      (s, i) => `
    <div class="acc" data-acc>
      <div class="acc-h" data-acc-head role="button" tabindex="0" aria-expanded="false" aria-label="${esc(s.name)} skill details">
        <input type="checkbox" data-skill-idx="${i}" checked style="width:18px;height:18px;flex:none;" aria-label="Select ${esc(s.name)} for submission">
        <div style="flex:1;min-width:0;">
          <div class="nm" style="font-weight:600;font-size:14.5px;">${esc(s.name)}</div>
          <div class="sl" style="font-family:var(--mono);font-size:11.5px;color:var(--text-faint);">skills/${esc(s.slug)}/</div>
        </div>
        <span class="tag" style="flex:none;">${esc(s.tokenUpfront + s.tokenOndemand)} tok</span>
        <svg class="chev" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>
      </div>
      <div class="acc-body" role="region" aria-label="${esc(s.name)} metadata">
        <div style="overflow:hidden;">
          <div style="padding:12px 14px;">
            <div class="kv" style="display:grid;grid-template-columns:130px 1fr;gap:12px;padding:10px 0;border-bottom:1px solid var(--border);font-size:14px;"><span class="k" style="font-family:var(--mono);font-size:12px;color:var(--text-faint);">description</span><span class="v">${esc(s.description)}</span></div>
            <div class="kv" style="display:grid;grid-template-columns:130px 1fr;gap:12px;padding:10px 0;border-bottom:1px solid var(--border);font-size:14px;"><span class="k" style="font-family:var(--mono);font-size:12px;color:var(--text-faint);">version</span><span class="v">${esc(s.version)}</span></div>
            <div class="kv" style="display:grid;grid-template-columns:130px 1fr;gap:12px;padding:10px 0;border-bottom:1px solid var(--border);font-size:14px;"><span class="k" style="font-family:var(--mono);font-size:12px;color:var(--text-faint);">license</span><span class="v">${esc(s.license) || '—'}</span></div>
            <div class="kv" style="display:grid;grid-template-columns:130px 1fr;gap:12px;padding:10px 0;border-bottom:1px solid var(--border);font-size:14px;"><span class="k" style="font-family:var(--mono);font-size:12px;color:var(--text-faint);">tags</span><span class="v">${(s.tags || []).map((t) => `<span class="tag">${esc(t)}</span>`).join(' ')}</span></div>
            <div class="kv" style="display:grid;grid-template-columns:130px 1fr;gap:12px;padding:10px 0;font-size:14px;"><span class="k" style="font-family:var(--mono);font-size:12px;color:var(--text-faint);">contents</span><span class="v">${esc(s.fileCount)} files · <span style="color:var(--accent-text);">${esc(s.tokenUpfront + s.tokenOndemand)} tokens</span></span></div>
          </div>
        </div>
      </div>
    </div>
  `,
    )
    .join('');
  container.innerHTML = `
    <div class="callout" style="margin-bottom:16px;" aria-live="polite" aria-atomic="true"><svg class="ci" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><path d="M12 16v-4M12 8h.01" stroke-linecap="round"/></svg><div><b>${esc(skills.length)} skill${skills.length > 1 ? 's' : ''}</b> found. Everything below is read from each skill's SKILL.md frontmatter.</div></div>
    <div class="accs">${items}</div>
    <button class="btn btn-primary" data-continue style="margin-top:18px;">Continue with selected skills</button>
  `;
}

function renderStep3() {
  const container = document.getElementById('step3content') as HTMLElement;
  if (!container) return;
  const skills = selectedSkills();
  container.innerHTML = `
    <p class="hint" style="margin-bottom:14px;">On submit, automated checks run and the selected skills enter the review queue. Drafts you save appear under <a href="/submissions" style="color:var(--accent-text);">My submissions</a>.</p>
    <div class="card" style="margin-bottom:16px;">
      <div class="kicker" style="margin-bottom:10px;"><span class="tick">//</span> SELECTED</div>
      ${skills.map((s) => `<div style="padding:8px 0;border-bottom:1px solid var(--border);"><b>${esc(s.name)}</b> <span class="hint">skills/${esc(s.slug)}/</span></div>`).join('')}
    </div>
    <div class="row" style="gap:10px;">
      <button class="btn btn-ghost" data-save-draft>Save draft</button>
      <button class="btn btn-primary btn-lg" data-submit aria-busy="false"><svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="width:16px;height:16px;"><path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z"/></svg> Submit for review</button>
    </div>
    <div id="submitStatus" class="hint" style="margin-top:10px;" aria-live="polite" aria-atomic="true"></div>
  `;
}

function selectedSkills(): DetectedSkill[] {
  if (!state.scan) return [];
  const checked = Array.from(
    document.querySelectorAll('[data-skill-idx]:checked'),
  ) as HTMLInputElement[];
  return checked
    .map((cb) => state.scan!.skills[Number(cb.getAttribute('data-skill-idx'))])
    .filter(Boolean);
}

function render() {
  renderStep1();
  renderStep2();
  renderStep3();
  steps.forEach((el) => {
    const n = Number(el.getAttribute('data-step'));
    el.classList.remove('done', 'active', 'upcoming');
    const cls =
      n < state.step ? 'done' : n === state.step ? 'active' : 'upcoming';
    el.classList.add(cls);
    el.setAttribute('aria-current', cls === 'active' ? 'step' : 'false');
    const no = el.querySelector('.vno') as HTMLElement;
    no.innerHTML = cls === 'done' ? CHECK : String(n);
  });
  const activeStep = steps.find((s) => s.classList.contains('active'));
  if (activeStep) {
    const title = activeStep.querySelector('h2') as HTMLElement | null;
    title?.focus({ preventScroll: true });
  }
  const status = document.getElementById('repoStatus');
  if (status) {
    if (state.checking)
      status.innerHTML = `<div class="callout" style="align-items:center;"><span class="spinner" style="width:16px;height:16px;flex:none;"></span><div>Scanning <b>${esc(state.checking)}</b> for SKILL.md files…</div></div>`;
    else if (state.error)
      status.innerHTML = `<div class="callout" style="background:var(--danger-soft);border-color:transparent;align-items:flex-start;"><svg class="ci" viewBox="0 0 24 24" fill="none" stroke="var(--danger)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 9v4M12 17h.01M10.3 3.9 2.4 18a2 2 0 0 0 1.7 3h15.8a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/></svg><div><b>${esc(state.error)}</b></div></div>`;
    else status.innerHTML = '';
  }
}

async function createDrafts(submit: boolean) {
  const skills = selectedSkills();
  if (skills.length === 0 || !state.repo || !state.scan) return;
  const status = document.getElementById('submitStatus');
  const submitBtn = document.querySelector(
    '[data-submit]',
  ) as HTMLButtonElement | null;
  const saveBtn = document.querySelector(
    '[data-save-draft]',
  ) as HTMLButtonElement | null;
  if (status) status.textContent = 'Creating drafts…';
  submitBtn?.setAttribute('aria-busy', 'true');
  submitBtn?.setAttribute('disabled', 'true');
  saveBtn?.setAttribute('disabled', 'true');
  const payload = {
    repoOwner: state.repo.owner,
    repoName: state.repo.name,
    ref: state.scan.commitSha,
    skills: skills.map((s) => ({
      slug: s.slug,
      name: s.name,
      description: s.description,
      license: s.license || '',
      tags: s.tags || [],
      version: s.version,
    })),
  };
  try {
    const res = await fetch('/api/me/submissions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    if (!res.ok) throw new Error(await res.text());
    const { submissionIds } = (await res.json()) as { submissionIds: string[] };
    if (submit) {
      if (status) status.textContent = 'Submitting for review…';
      for (const id of submissionIds) {
        const r = await fetch(`/api/me/submissions/${id}/submit`, {
          method: 'POST',
        });
        if (!r.ok) throw new Error(await r.text());
      }
      showSuccess('Submitted for review');
    } else {
      showToast('Draft saved');
    }
    window.location.href = '/submissions';
  } catch (e) {
    const msg = e instanceof Error ? e.message : 'Submission failed';
    if (status) status.textContent = msg;
    showError(msg);
  } finally {
    submitBtn?.setAttribute('aria-busy', 'false');
    submitBtn?.removeAttribute('disabled');
    saveBtn?.removeAttribute('disabled');
  }
}

if (vsteps) {
  vsteps.addEventListener('click', (e) => {
    const target = e.target as HTMLElement;
    const repo = target.closest('[data-repo]');
    if (repo && state.repos) {
      const fullName = repo.getAttribute('data-repo');
      const r = state.repos.repos.find((x) => x.fullName === fullName);
      if (r) scanRepo(r);
      return;
    }
    const acc = target.closest('[data-acc-head]');
    if (acc && !target.closest('input[type="checkbox"]')) {
      const panel = acc.closest('[data-acc]') as HTMLElement | null;
      const expanded = panel?.classList.toggle('open');
      acc.setAttribute('aria-expanded', String(expanded));
      return;
    }
    const cont = target.closest('[data-continue]');
    if (cont) {
      state.step = 3;
      render();
      return;
    }
    const save = target.closest('[data-save-draft]');
    if (save) {
      createDrafts(false);
      return;
    }
    const sub = target.closest('[data-submit]');
    if (sub) {
      createDrafts(true);
      return;
    }
  });

  vsteps.addEventListener('keydown', (e) => {
    const target = e.target as HTMLElement;
    const repo = target.closest('[data-repo]');
    if (repo && (e.key === 'Enter' || e.key === ' ')) {
      e.preventDefault();
      repo.dispatchEvent(new Event('click', { bubbles: true }));
    }
    const acc = target.closest('[data-acc-head]');
    if (
      acc &&
      !target.closest('input[type="checkbox"]') &&
      (e.key === 'Enter' || e.key === ' ')
    ) {
      e.preventDefault();
      acc.dispatchEvent(new Event('click', { bubbles: true }));
    }
  });

  vsteps.addEventListener('input', (e) => {
    const target = e.target as HTMLElement;
    if (target.id === 'repoFilter') renderRepoList();
  });

  loadRepos()
    .then(render)
    .catch((e) => {
      const el = document.getElementById('step1content');
      if (el)
        el.innerHTML = `<div class="callout" style="background:var(--danger-soft);border-color:transparent;"><b>Failed to load repositories:</b> ${esc(e.message)}</div>`;
    });
}
