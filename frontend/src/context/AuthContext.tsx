import { createContext, Fragment, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import * as authApi from "../api/auth";
import { getToken, setToken, setUnauthorizedHandler, TOKEN_KEY } from "../api/client";
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
  /** Why the user was signed out automatically (inactivity, expired token), shown on the login page. */
  signOutReason: string | null;
  /** Seconds until the inactivity sign-out, while the warning is showing; otherwise null. */
  idleSecondsLeft: number | null;
  /** Counts as activity: dismisses the inactivity warning in every window. */
  stayActive: () => void;
}

const USER_KEY = "event-manager.user";
/** Time of the user's last activity in any window (ms since epoch), shared through localStorage. */
const ACTIVITY_KEY = "event-manager.lastActivity";
/** Why the session ended, written before the token is removed so every window can show it. */
const SIGN_OUT_REASON_KEY = "event-manager.signOutReason";

/** Inactivity limit: VITE_IDLE_TIMEOUT_MINUTES (default 15). */
const IDLE_MS = minutesSetting(import.meta.env.VITE_IDLE_TIMEOUT_MINUTES, 15) * 60_000;
/** How long before the sign-out the warning appears: a minute, or half the limit if it's very short. */
const WARNING_MS = Math.min(60_000, IDLE_MS / 2);
/** Activity is written at most this often (mousemove fires constantly). */
const ACTIVITY_WRITE_INTERVAL_MS = 5_000;
const ACTIVITY_EVENTS = ["mousemove", "mousedown", "keydown", "scroll", "touchstart", "wheel", "visibilitychange"] as const;

const IDLE_MESSAGE = `You were signed out after ${IDLE_MS / 60_000} minutes of inactivity. Please log in again.`;
const EXPIRED_MESSAGE = "Your session expired. Please log in again.";

function minutesSetting(raw: unknown, fallback: number): number {
  const value = Number(raw);
  return Number.isFinite(value) && value > 0 ? value : fallback;
}

function loadStoredUser(): AuthUser | null {
  if (!getToken()) return null;
  const raw = localStorage.getItem(USER_KEY);
  return raw ? (JSON.parse(raw) as AuthUser) : null;
}

/** The token's "exp" claim in ms since epoch, or null if it can't be read. Not verified: the server does that. */
function tokenExpiresAt(token: string | null): number | null {
  if (!token) return null;
  try {
    const payload = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const exp = (JSON.parse(atob(payload)) as { exp?: number }).exp;
    return typeof exp === "number" ? exp * 1000 : null;
  } catch {
    return null;
  }
}

function lastActivity(): number | null {
  const value = Number(localStorage.getItem(ACTIVITY_KEY));
  return value > 0 ? value : null;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(loadStoredUser);
  // Bumped when another window switches the signed-in user; keys the subtree below so every page
  // remounts and refetches its data as the new user.
  const [session, setSession] = useState(0);
  const [signOutReason, setSignOutReason] = useState<string | null>(() => localStorage.getItem(SIGN_OUT_REASON_KEY));
  const [idleSecondsLeft, setIdleSecondsLeft] = useState<number | null>(null);
  const userRef = useRef(user);
  userRef.current = user;
  // Held in refs so the callbacks below keep a stable identity: navigate changes with the route.
  const navigate = useNavigate();
  const location = useLocation();
  const navigateRef = useRef(navigate);
  navigateRef.current = navigate;
  const locationRef = useRef(location);
  locationRef.current = location;

  /** After an automatic sign-out, go to the login page, coming back here after logging in. */
  const goToLogin = useCallback(() => {
    if (locationRef.current.pathname !== "/login") {
      navigateRef.current("/login", { replace: true, state: { from: locationRef.current } });
    }
  }, []);

  const clearSession = useCallback(() => {
    setToken(null);
    localStorage.removeItem(USER_KEY);
    localStorage.removeItem(ACTIVITY_KEY);
    setUser(null);
    setIdleSecondsLeft(null);
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem(SIGN_OUT_REASON_KEY);
    setSignOutReason(null);
    clearSession();
  }, [clearSession]);

  /** Automatic sign-out with a reason. The reason is written before the token is removed, so the
   *  other windows (which follow the token's removal, below) find it when they sign out too. */
  const signOut = useCallback(
    (reason: string) => {
      if (!userRef.current) return;
      localStorage.setItem(SIGN_OUT_REASON_KEY, reason);
      setSignOutReason(reason);
      clearSession();
      goToLogin();
    },
    [clearSession, goToLogin],
  );

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
      setIdleSecondsLeft(null);
      setSession((n) => n + 1);
      const reason = next ? null : localStorage.getItem(SIGN_OUT_REASON_KEY);
      setSignOutReason(reason);
      if (reason) goToLogin();
    }
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, [goToLogin]);

  // A 401 on a request that carried the current token means the server no longer accepts it
  // (expired, or signed with a since-changed secret). Requests without a token, like a failed
  // login, never get here; see client.ts.
  useEffect(() => {
    setUnauthorizedHandler(() => signOut(EXPIRED_MESSAGE));
    return () => setUnauthorizedHandler(null);
  }, [signOut]);

  // Inactivity timeout and token expiry, while signed in. Activity in any window counts, since
  // ACTIVITY_KEY is shared: each window checks the same timestamp, so they warn and sign out
  // together, and moving the mouse in one keeps all of them signed in.
  useEffect(() => {
    if (!user) return;
    if (lastActivity() === null) localStorage.setItem(ACTIVITY_KEY, String(Date.now()));

    function check() {
      const now = Date.now();
      const expiresAt = tokenExpiresAt(getToken());
      if (expiresAt !== null && now >= expiresAt) {
        signOut(EXPIRED_MESSAGE);
        return;
      }
      const idle = now - (lastActivity() ?? now);
      if (idle >= IDLE_MS) {
        signOut(IDLE_MESSAGE);
      } else if (idle >= IDLE_MS - WARNING_MS) {
        setIdleSecondsLeft(Math.ceil((IDLE_MS - idle) / 1000));
      } else {
        setIdleSecondsLeft(null);
      }
    }

    let lastWrite = 0;
    function recordActivity() {
      const now = Date.now();
      if (now - lastWrite >= ACTIVITY_WRITE_INTERVAL_MS) {
        lastWrite = now;
        localStorage.setItem(ACTIVITY_KEY, String(now));
      }
    }

    // Check first, before listening: a session reopened after a long absence (a tab restored the
    // next morning) must time out, not be revived by the first mouse movement.
    check();
    ACTIVITY_EVENTS.forEach((name) => window.addEventListener(name, recordActivity, { passive: true }));
    // Every second, so the warning can count down. Uses wall-clock time, so a laptop that slept
    // past the limit signs out on the first check after waking.
    const timer = window.setInterval(check, 1000);
    return () => {
      ACTIVITY_EVENTS.forEach((name) => window.removeEventListener(name, recordActivity));
      window.clearInterval(timer);
    };
  }, [user, signOut]);

  const stayActive = useCallback(() => {
    localStorage.setItem(ACTIVITY_KEY, String(Date.now()));
    setIdleSecondsLeft(null);
  }, []);

  const applyAuthResponse = useCallback((response: { token: string; username: string; email: string; role: Role }) => {
    localStorage.removeItem(SIGN_OUT_REASON_KEY);
    localStorage.setItem(ACTIVITY_KEY, String(Date.now()));
    setToken(response.token);
    const nextUser: AuthUser = { username: response.username, email: response.email, role: response.role };
    localStorage.setItem(USER_KEY, JSON.stringify(nextUser));
    setSignOutReason(null);
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

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      isAuthenticated: user !== null,
      isAdmin: user?.role === "ROLE_ADMIN",
      login,
      register,
      logout,
      signOutReason,
      idleSecondsLeft,
      stayActive,
    }),
    [user, login, register, logout, signOutReason, idleSecondsLeft, stayActive],
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
