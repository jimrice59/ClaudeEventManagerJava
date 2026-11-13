import axios from "axios";

// In dev, Vite proxies /api -> http://localhost:8080 (see vite.config.ts).
// In prod, set VITE_API_BASE_URL to the deployed backend origin.
const baseURL = `${import.meta.env.VITE_API_BASE_URL ?? ""}/api/v1`;

export const apiClient = axios.create({ baseURL });

export const TOKEN_KEY = "event-manager.token";

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string | null): void {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token);
  } else {
    localStorage.removeItem(TOKEN_KEY);
  }
}

apiClient.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

let onUnauthorized: (() => void) | null = null;

/** Registered by AuthProvider: called when the server rejects the current token with a 401. */
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  onUnauthorized = handler;
}

// A 401 on a request that carried a token means the server no longer accepts it (expired, or
// signed with a since-changed secret), so the session is over. Only when that token is still the
// current one: if another window logged in as someone else meanwhile, the new session stays.
// Requests sent without a token (a failed login, say) are left to the caller.
apiClient.interceptors.response.use(undefined, (error) => {
  if (axios.isAxiosError(error) && error.response?.status === 401) {
    const sent = error.config?.headers?.Authorization;
    const token = getToken();
    if (sent && token && sent === `Bearer ${token}`) {
      onUnauthorized?.();
    }
  }
  return Promise.reject(error);
});

export function extractErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as { message?: string } | undefined;
    if (data?.message) return data.message;
    if (error.response?.status === 401) return "Invalid credentials or session expired.";
    if (error.response?.status === 403) return "You don't have permission to do that.";
    if (error.response?.status === 404) return "Not found.";
    return error.message;
  }
  return "Something went wrong.";
}
