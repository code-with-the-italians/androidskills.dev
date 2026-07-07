import type { components } from './api-types';

// ---- Extract schema types from the generated OpenAPI types ----

type Schemas = components['schemas'];

export type SkillCard = Schemas['SkillCard'];
export type SkillDetail = Schemas['SkillDetail'];
export type SkillSearchPage = Schemas['SkillSearchPage'];
export type StatsResponse = Schemas['StatsResponse'];
export type CategoryWithCount = Schemas['CategoryWithCount'];
export type TrendsResponse = Schemas['TrendsResponse'];
export type TimelinePage = Schemas['TimelinePage'];
export type FileTreeResponse = Schemas['FileTreeResponse'];
export type FileContentResponse = Schemas['FileContentResponse'];
export type VersionsResponse = Schemas['VersionsResponse'];
export type ReportResponse = Schemas['ReportResponse'];
export type MeResponse = Schemas['MeResponse'];

/** Settings actually persisted by the backend (only two keys; everything else is localStorage). */
export interface UserSettings {
  emailNotifications?: boolean;
  publicProfile?: boolean;
}

export type RepoDto = Schemas['RepoDto'];
export type ReposResponse = Schemas['ReposResponse'];
export type ScanResponse = Schemas['ScanResponse'];
export type DetectedSkillDto = Schemas['DetectedSkillDto'];
export type CreateDraftsRequest = Schemas['CreateDraftsRequest'];
export type CreateDraftsResponse = Schemas['CreateDraftsResponse'];
export type GroupedSubmissions = Schemas['GroupedSubmissions'];
export type SubmissionSummary = Schemas['SubmissionSummary'];
export type SubmissionDetail = Schemas['SubmissionDetail'];
export type BundleDetail = Schemas['BundleDetail'];
export type PageOfBundleSummary = Schemas['PageOfBundleSummary'];
export type AuthorProfile = Schemas['AuthorProfile'];
export type ErrorResponse = Schemas['ErrorResponse'];

// ---- Search params ----

export interface SearchParams {
  q?: string;
  cat?: string[];
  tag?: string[];
  size?: string;
  verified?: boolean;
  sort?: string;
  page?: number;
  pageSize?: number;
}

// ---- Error ----

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

// ---- Context-aware client factory ----

interface ClientConfig {
  /** Absolute base URL for SSR (e.g. http://127.0.0.1:8080); empty for browser (relative). */
  baseUrl: string;
  /** Cookie header to forward (SSR only — read from Astro.request). */
  cookie?: string;
}

export function buildQueryString(params: Record<string, unknown>): string {
  const sp = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null) continue;
    if (Array.isArray(value)) {
      for (const v of value) sp.append(key, String(v));
    } else {
      sp.append(key, String(value));
    }
  }
  const qs = sp.toString();
  return qs ? `?${qs}` : '';
}

export class ApiClient {
  constructor(private config: ClientConfig) {}

  private get baseUrl(): string {
    return this.config.baseUrl;
  }

  private async fetchJson<T>(path: string, init?: RequestInit): Promise<T> {
    const url = `${this.baseUrl}${path}`;
    const headers: Record<string, string> = {
      Accept: 'application/json',
      ...(init?.headers as Record<string, string>),
    };
    // SSR: forward the browser's session cookie so authed endpoints work.
    if (this.config.cookie) {
      headers.Cookie = this.config.cookie;
    }

    let response: Response;
    try {
      response = await fetch(url, { ...init, headers });
    } catch {
      throw new ApiError(502, 'bad_gateway', `Failed to reach API at ${url}`);
    }

    if (response.status === 204) {
      return undefined as T;
    }

    const body = await response.json().catch(() => null);

    if (!response.ok) {
      const error = body as ErrorResponse | null;
      throw new ApiError(
        response.status,
        error?.error?.code ?? 'unknown',
        error?.error?.message ?? response.statusText,
      );
    }

    return body as T;
  }

  // ---- Public endpoints ----

  getStats(): Promise<StatsResponse> {
    return this.fetchJson('/api/stats');
  }

  searchSkills(params: SearchParams = {}): Promise<SkillSearchPage> {
    return this.fetchJson(
      `/api/skills${buildQueryString(params as Record<string, unknown>)}`,
    );
  }

  getSkill(slug: string): Promise<SkillDetail> {
    return this.fetchJson(`/api/skills/${encodeURIComponent(slug)}`);
  }

  getSkillFiles(slug: string): Promise<FileTreeResponse> {
    return this.fetchJson(`/api/skills/${encodeURIComponent(slug)}/files`);
  }

  getSkillFileContent(
    slug: string,
    path: string,
  ): Promise<FileContentResponse> {
    return this.fetchJson(
      `/api/skills/${encodeURIComponent(slug)}/files/${path}`,
    );
  }

  getSkillVersions(slug: string): Promise<VersionsResponse> {
    return this.fetchJson(`/api/skills/${encodeURIComponent(slug)}/versions`);
  }

  reportSkill(slug: string, reason?: string): Promise<ReportResponse> {
    return this.fetchJson(`/api/skills/${encodeURIComponent(slug)}/report`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason }),
    });
  }

  getCategories(): Promise<CategoryWithCount[]> {
    return this.fetchJson('/api/categories');
  }

  getTrends(): Promise<TrendsResponse> {
    return this.fetchJson('/api/trends');
  }

  getTimeline(page = 1, pageSize = 20): Promise<TimelinePage> {
    return this.fetchJson(
      `/api/timeline${buildQueryString({ page, pageSize })}`,
    );
  }

  getBundles(page = 1, pageSize = 20): Promise<PageOfBundleSummary> {
    return this.fetchJson(
      `/api/bundles${buildQueryString({ page, pageSize })}`,
    );
  }

  getBundle(id: string): Promise<BundleDetail> {
    return this.fetchJson(`/api/bundles/${encodeURIComponent(id)}`);
  }

  getAuthor(handle: string): Promise<AuthorProfile> {
    return this.fetchJson(`/api/authors/${encodeURIComponent(handle)}`);
  }

  // ---- Auth ----

  getMe(): Promise<MeResponse> {
    return this.fetchJson('/api/me');
  }

  deleteAccount(): Promise<void> {
    return this.fetchJson('/api/me', { method: 'DELETE' });
  }

  logout(): Promise<{ ok: boolean }> {
    return this.fetchJson('/api/auth/logout', { method: 'POST' });
  }

  // ---- Settings ----

  getSettings(): Promise<UserSettings> {
    return this.fetchJson('/api/me/settings');
  }

  putSettings(settings: UserSettings): Promise<{ ok: boolean }> {
    return this.fetchJson('/api/me/settings', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(settings),
    });
  }

  // ---- Repos / scan ----

  getRepos(): Promise<ReposResponse> {
    return this.fetchJson('/api/me/repos');
  }

  scanRepo(owner: string, repo: string): Promise<ScanResponse> {
    return this.fetchJson(
      `/api/me/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/scan`,
      { method: 'POST' },
    );
  }

  // ---- Submissions ----

  getSubmissions(): Promise<GroupedSubmissions[]> {
    return this.fetchJson('/api/me/submissions');
  }

  createDrafts(request: CreateDraftsRequest): Promise<CreateDraftsResponse> {
    return this.fetchJson('/api/me/submissions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  }

  getSubmission(id: string): Promise<SubmissionDetail> {
    return this.fetchJson(`/api/me/submissions/${encodeURIComponent(id)}`);
  }

  deleteSubmission(id: string): Promise<void> {
    return this.fetchJson(`/api/me/submissions/${encodeURIComponent(id)}`, {
      method: 'DELETE',
    });
  }

  submitDraft(id: string): Promise<{ ok: boolean }> {
    return this.fetchJson(
      `/api/me/submissions/${encodeURIComponent(id)}/submit`,
      { method: 'POST' },
    );
  }

  withdrawSubmission(id: string): Promise<{ ok: boolean }> {
    return this.fetchJson(
      `/api/me/submissions/${encodeURIComponent(id)}/withdraw`,
      { method: 'POST' },
    );
  }

  // ---- Starred ----

  getStars(): Promise<SkillCard[]> {
    return this.fetchJson('/api/me/stars');
  }

  starSkill(slug: string): Promise<{ ok: boolean }> {
    return this.fetchJson(`/api/me/stars/${encodeURIComponent(slug)}`, {
      method: 'POST',
    });
  }

  unstarSkill(slug: string): Promise<void> {
    return this.fetchJson(`/api/me/stars/${encodeURIComponent(slug)}`, {
      method: 'DELETE',
    });
  }
}

/**
 * Create an API client for server-side rendering (SSR).
 * Pass the incoming Astro request so the session cookie is forwarded.
 *
 * ```astro
 * ---
 * import { createApiClient } from '../lib/api';
 * const api = createApiClient(Astro.request);
 * const stats = await api.getStats();
 * ---
 * ```
 */
export function createApiClient(request?: Request): ApiClient {
  const baseUrl =
    process.env.API_URL?.replace(/\/$/, '') || 'http://127.0.0.1:8080';
  const cookie = request?.headers.get('cookie') ?? undefined;
  return new ApiClient({ baseUrl, cookie });
}

/**
 * Require an admin session. Returns the caller on success; throws ApiError(404) if the caller is
 * not an admin or is anonymous. This mirrors the API's `requireAdmin()` behavior on the web side
 * so admin pages do not leak their existence.
 */
export async function requireAdmin(request: Request): Promise<MeResponse> {
  const api = createApiClient(request);
  const me = await api.getMe();
  if (me.role !== 'admin') {
    throw new ApiError(404, 'not_found', 'Not found');
  }
  return me;
}

/**
 * Browser-side singleton. Uses relative URLs so requests go through the
 * Astro proxy routes (web/src/pages/api/[...path].ts) and carry cookies
 * automatically via same-origin.
 */
export const browserApi: ApiClient = new ApiClient({ baseUrl: '' });
