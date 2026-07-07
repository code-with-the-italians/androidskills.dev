import type { components } from './api-admin-types';
import { ApiError, buildQueryString } from './api';

type Schemas = components['schemas'];

export type AdminStatsResponse = Schemas['AdminStatsResponse'];
export type AdminQueueItem = Schemas['AdminQueueItem'];
export type AdminQueueDetail = Schemas['AdminQueueDetail'];
export type AdminSkillListItem = Schemas['AdminSkillListItem'];
export type AdminSkillPatch = Schemas['AdminSkillPatch'];
export type AdminBulkActionRequest = Schemas['AdminBulkActionRequest'];
export type AdminBulkActionResponse = Schemas['AdminBulkActionResponse'];
export type AdminUserListItem = Schemas['AdminUserListItem'];
export type AdminUserPatch = Schemas['AdminUserPatch'];
export type AdminCategoryDto = Schemas['AdminCategoryDto'];
export type AdminCategoryRenameRequest = Schemas['AdminCategoryRenameRequest'];
export type AdminPlatformSettings = Schemas['AdminPlatformSettings'];
export type AdminDecisionRequest = Schemas['AdminDecisionRequest'];
export type AuditLogEntry = Schemas['AuditLogEntry'];
export type ErrorResponse = Schemas['ErrorResponse'];

interface ClientConfig {
  baseUrl: string;
  cookie?: string;
}

export class AdminApiClient {
  constructor(private config: ClientConfig) {}

  private async fetchJson<T>(path: string, init?: RequestInit): Promise<T> {
    const url = `${this.config.baseUrl}${path}`;
    const headers: Record<string, string> = {
      Accept: 'application/json',
      ...(init?.headers as Record<string, string>),
    };
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

  getStats(): Promise<AdminStatsResponse> {
    return this.fetchJson('/api/admin/stats');
  }

  getQueue(filter?: string, q?: string): Promise<AdminQueueItem[]> {
    return this.fetchJson(`/api/admin/queue${buildQueryString({ filter, q })}`);
  }

  getQueueDetail(id: string): Promise<AdminQueueDetail> {
    return this.fetchJson(`/api/admin/queue/${encodeURIComponent(id)}`);
  }

  queueDecision(
    id: string,
    decision: AdminDecisionRequest,
  ): Promise<{ ok: boolean }> {
    return this.fetchJson(
      `/api/admin/queue/${encodeURIComponent(id)}/decision`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(decision),
      },
    );
  }

  getSkills(params?: {
    filter?: string;
    q?: string;
    sort?: string;
    page?: number;
  }): Promise<AdminSkillListItem[]> {
    return this.fetchJson(`/api/admin/skills${buildQueryString(params ?? {})}`);
  }

  patchSkill(id: string, patch: AdminSkillPatch): Promise<{ ok: boolean }> {
    return this.fetchJson(`/api/admin/skills/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(patch),
    });
  }

  bulkSkills(
    request: AdminBulkActionRequest,
  ): Promise<AdminBulkActionResponse> {
    return this.fetchJson('/api/admin/skills/bulk', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  }

  getUsers(params?: {
    filter?: string;
    q?: string;
    page?: number;
  }): Promise<AdminUserListItem[]> {
    return this.fetchJson(`/api/admin/users${buildQueryString(params ?? {})}`);
  }

  exportUsersUrl(): string {
    return `${this.config.baseUrl}/api/admin/users/export`;
  }

  patchUser(id: string, patch: AdminUserPatch): Promise<{ ok: boolean }> {
    return this.fetchJson(`/api/admin/users/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(patch),
    });
  }

  getCategories(): Promise<AdminCategoryDto[]> {
    return this.fetchJson('/api/admin/categories');
  }

  createCategory(slug: string, name: string): Promise<AdminCategoryDto> {
    return this.fetchJson(
      `/api/admin/categories${buildQueryString({ slug, name })}`,
      { method: 'POST' },
    );
  }

  renameCategory(
    slug: string,
    request: AdminCategoryRenameRequest,
  ): Promise<AdminCategoryDto> {
    return this.fetchJson(`/api/admin/categories/${encodeURIComponent(slug)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  }

  mergeCategory(slug: string, to: string): Promise<{ ok: boolean }> {
    return this.fetchJson(
      `/api/admin/categories/${encodeURIComponent(slug)}/merge${buildQueryString({ to })}`,
      { method: 'POST' },
    );
  }

  getSettings(): Promise<AdminPlatformSettings> {
    return this.fetchJson('/api/admin/settings');
  }

  putSettings(settings: AdminPlatformSettings): Promise<AdminPlatformSettings> {
    return this.fetchJson('/api/admin/settings', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(settings),
    });
  }

  getAudit(params?: {
    action?: string;
    target?: string;
    page?: number;
  }): Promise<AuditLogEntry[]> {
    return this.fetchJson(`/api/admin/audit${buildQueryString(params ?? {})}`);
  }
}

export function createAdminApiClient(request?: Request): AdminApiClient {
  const baseUrl =
    process.env.API_URL?.replace(/\/$/, '') || 'http://127.0.0.1:8080';
  const cookie = request?.headers.get('cookie') ?? undefined;
  return new AdminApiClient({ baseUrl, cookie });
}

export const browserAdminApi: AdminApiClient = new AdminApiClient({
  baseUrl: '',
});
