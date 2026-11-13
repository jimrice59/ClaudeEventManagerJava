import { useAuth } from "../context/AuthContext";

/**
 * Shown in the last minute before the inactivity sign-out (see AuthContext). Any activity in any
 * window dismisses it, as does "Stay signed in"; it then reappears only after another stretch of
 * inactivity.
 */
export function IdleWarning() {
  const { idleSecondsLeft, stayActive, logout } = useAuth();
  if (idleSecondsLeft === null) return null;
  return (
    <div className="idle-overlay">
      <div className="idle-dialog card" role="alertdialog" aria-modal="true" aria-labelledby="idle-title" aria-describedby="idle-text">
        <h2 id="idle-title">Still there?</h2>
        <p id="idle-text">
          You'll be signed out in <strong>{idleSecondsLeft}</strong> second{idleSecondsLeft === 1 ? "" : "s"} because of inactivity.
        </p>
        <div className="button-row">
          <button type="button" className="button primary" onClick={stayActive} autoFocus>
            Stay signed in
          </button>
          <button type="button" className="button" onClick={logout}>
            Log out now
          </button>
        </div>
      </div>
    </div>
  );
}
