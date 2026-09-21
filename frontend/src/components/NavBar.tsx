import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

export function NavBar() {
  const { user, isAuthenticated, logout } = useAuth();
  const navigate = useNavigate();

  function handleLogout() {
    logout();
    navigate("/");
  }

  return (
    <nav className="navbar">
      <Link to="/" className="brand">
        Event Manager
      </Link>
      <div className="nav-links">
        <Link to="/events">Events</Link>
        <Link to="/performers">Performers</Link>
        <Link to="/venues">Venues</Link>
        {isAuthenticated && <Link to="/my-tickets">My Tickets</Link>}
      </div>
      <div className="nav-auth">
        {isAuthenticated ? (
          <>
            <span className="muted">
              {user?.username} ({user?.role === "ROLE_ADMIN" ? "admin" : "user"})
            </span>
            <button onClick={handleLogout}>Log out</button>
          </>
        ) : (
          <>
            <Link to="/login">Log in</Link>
            <Link to="/register">Register</Link>
          </>
        )}
      </div>
    </nav>
  );
}
