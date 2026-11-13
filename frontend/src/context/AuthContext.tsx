import { createContext, Fragment, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import * as authApi from "../api/auth";
import { getToken, setToken, TOKEN_KEY } from "../api/client";
import type { LoginRequest, RegisterRequest, Role } from "../types";

interface AuthUser {
  username: string;
  email: string;
  role: Role;
}

interface AuthContextValue {
  user: AuthUser | null;
  isAuthenticated: boolean;
  isAdmin: boolean;
  login: (request: LoginRequest) => Promise<void>;
  register: (request: RegisterRequest) => Promise<void>;
  logout: () => void;
}

const USER_KEY = "event-manager.user";

function loadStoredUser(): AuthUser | null {
  if (!getToken()) return null;
  const raw = localStorage.getItem(USER_KEY);
  return raw ? (JSON.parse(raw) as AuthUser) : null;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(loadStoredUser);
  // Bumped when another window switches the signed-in user; keys the subtree below so every page
  // remounts and refetches its data as the new user.
  const [session, setSession] = useState(0);
  const userRef = useRef(user);
  userRef.current = user;

  // The token and user live in localStorage, which every window of this origin shares, and every
  // API request reads the token at send time. So a login or logout in another window changes who
  // this window's requests are made as. Follow it: the "storage" event fires in every *other*
  // window of the origin when a key changes (never in the one that made the change).
  useEffect(() => {
    function onStorage(e: StorageEvent) {
      // key is null when storage was cleared entirely.
      if (e.key !== null && e.key !== TOKEN_KEY && e.key !== USER_KEY) return;
      const current = userRef.current;
      const next = loadStoredUser();
      if (current?.username === next?.username) {
        // Same person (e.g. logged in again): keep the page, just pick up any role change.
        if (current?.role !== next?.role) setUser(next);
        return;
      }
      setUser(next);
      setSession((n) => n + 1);
    }
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, []);

  const applyAuthResponse = useCallback((response: { token: string; username: string; email: string; role: Role }) => {
    setToken(response.token);
    const nextUser: AuthUser = { username: response.username, email: response.email, role: response.role };
    localStorage.setItem(USER_KEY, JSON.stringify(nextUser));
    setUser(nextUser);
  }, []);

  const login = useCallback(
    async (request: LoginRequest) => {
      const response = await authApi.login(request);
      applyAuthResponse(response);
    },
    [applyAuthResponse],
  );

  const register = useCallback(
    async (request: RegisterRequest) => {
      const response = await authApi.register(request);
      applyAuthResponse(response);
    },
    [applyAuthResponse],
  );

  const logout = useCallback(() => {
    setToken(null);
    localStorage.removeItem(USER_KEY);
    setUser(null);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      isAuthenticated: user !== null,
      isAdmin: user?.role === "ROLE_ADMIN",
      login,
      register,
      logout,
    }),
    [user, login, register, logout],
  );

  return (
    <AuthContext.Provider value={value}>
      <Fragment key={session}>{children}</Fragment>
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used within an AuthProvider");
  return context;
}
