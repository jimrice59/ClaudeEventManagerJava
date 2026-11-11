import { Link, NavLink, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { FoxMascot } from "./FoxMascot";

/** Site header shown on every page: brand band (mascot + title + account) above the section nav. */
export function NavBar() {
  const { user, isAuthenticated, logout } = useAuth();
  const navigate = useNavigate();
  const { pathname } = useLocation();

  function handleLogout() {
    logout();
    navigate("/");
  }

  // "/" also shows the events list, so the Events tab counts as active there too.
  const eventsActive = pathname === "/" || pathname.startsWith("/events");
  const tabClass = ({ isActive }: { isActive: boolean }) => (isActive ? "nav-tab active" : "nav-tab");

  return (
    <header className="site-header">
      <div className="brand-band">
        <div className="brand-band-inner">
          <Link to="/" className="brand">
            <span className="mascot-badge">
              <FoxMascot size={52} />
            </span>
            <span className="brand-text">
              <span className="brand-title">Foxy Ticket Service</span>
              {/* Speed cue: a speedometer with its needle in the red zone (the needle sweeps up
                  into it once on load). Decorative, so screen readers just read the text. */}
              <span className="brand-tagline">
                <svg className="speedometer" width="28" height="24" viewBox="0 0 24 20.5" aria-hidden="true">
                  <path className="gauge" d="M3 17 A9 9 0 0 1 15.6 8.7" />
                  <path className="gauge-redline" d="M15.6 8.7 A9 9 0 0 1 21 17" />
                  <path className="needle" d="M12 17 L19.2 10.9" />
                  <circle className="hub" cx="12" cy="17" r="2.5" />
                </svg>
                <span className="tagline-text">Find a show. Grab a seat.</span>
              </span>
            </span>
          </Link>
          <div className="nav-auth">
            {isAuthenticated ? (
              <>
                <span className="nav-user">
                  {user?.username}
                  {user?.role === "ROLE_ADMIN" && <span className="role-chip">admin</span>}
                </span>
                <button onClick={handleLogout} className="button on-band small">
                  Log out
                </button>
              </>
            ) : (
              <>
                <Link to="/login" className="band-link">
                  Log in
                </Link>
                <Link to="/register" className="button on-band small">
                  Register
                </Link>
              </>
            )}
          </div>
        </div>
      </div>
      <nav className="navbar" aria-label="Main">
        <div className="navbar-inner">
          <NavLink to="/events" className={eventsActive ? "nav-tab active" : "nav-tab"}>
            Events
          </NavLink>
          <NavLink to="/performers" className={tabClass}>
            Performers
          </NavLink>
          <NavLink to="/venues" className={tabClass}>
            Venues
          </NavLink>
          {isAuthenticated && (
            <NavLink to="/my-tickets" className={tabClass}>
              My Tickets
            </NavLink>
          )}
        </div>
      </nav>
    </header>
  );
}
