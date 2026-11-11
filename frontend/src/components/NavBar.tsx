import { useEffect, useRef } from "react";
import { Link, NavLink, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { FoxMascot } from "./FoxMascot";
import { TicketStack } from "./TicketStack";

/** Site header shown on every page: brand band (mascot + title + account) above the section nav. */
export function NavBar() {
  const { user, isAuthenticated, logout } = useAuth();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const tabsRef = useRef<HTMLDivElement>(null);

  // On narrow screens the tab strip scrolls sideways; keep the active tab in view after navigating
  // (otherwise e.g. "Transactions" can be highlighted but off-screen).
  useEffect(() => {
    const strip = tabsRef.current;
    const active = strip?.querySelector<HTMLElement>(".nav-tab.active");
    if (strip && active) {
      const left = active.offsetLeft - strip.offsetLeft;
      const right = left + active.offsetWidth;
      if (left < strip.scrollLeft || right > strip.scrollLeft + strip.clientWidth) {
        strip.scrollTo({ left: Math.max(0, right - strip.clientWidth + 16) });
      }
    }
  }, [pathname]);

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
              <span className="brand-tagline">
                Find a show. Grab a seat.
                <TicketStack width={54} />
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
        <div className="navbar-inner" ref={tabsRef}>
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
            <>
              <NavLink to="/my-tickets" className={tabClass}>
                My Tickets
              </NavLink>
              <NavLink to="/transactions" className={tabClass}>
                Transactions
              </NavLink>
            </>
          )}
        </div>
      </nav>
    </header>
  );
}
