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
              {/* Speed cues: motion streaks trailing behind, a sporty italic, and a lightning bolt.
                  The SVGs are decorative, so screen readers just read the text. */}
              <span className="brand-tagline">
                <svg className="speed-streaks" width="26" height="14" viewBox="0 0 26 14" aria-hidden="true">
                  <path d="M2 3 H20" />
                  <path d="M8 7 H24" />
                  <path d="M4 11 H18" />
                </svg>
                <span className="tagline-text">Find a show. Grab a seat.</span>
                <svg className="speed-bolt" width="14" height="18" viewBox="0 0 14 18" aria-hidden="true">
                  <path d="M8.5 0 L1 10.5 H6.2 L4.8 18 L13 6.8 H7.8 Z" />
                </svg>
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
