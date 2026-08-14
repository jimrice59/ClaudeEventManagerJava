import { useEffect, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import * as venuesApi from "../api/venues";
import { extractErrorMessage } from "../api/client";
import { ErrorBanner } from "../components/ErrorBanner";
import { useAuth } from "../context/AuthContext";
import type { VenueDto } from "../types";

export function VenuesPage() {
  const { isAdmin } = useAuth();
  const navigate = useNavigate();
  const [venues, setVenues] = useState<VenueDto[]>([]);
  const [city, setCity] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionPending, setActionPending] = useState(false);

  async function load(cityFilter?: string) {
    setLoading(true);
    setError(null);
    try {
      setVenues(await venuesApi.getVenues(cityFilter));
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  function handleFilter(event: FormEvent) {
    event.preventDefault();
    load(city || undefined);
  }

  function clearFilter() {
    setCity("");
    load();
  }

  async function handleDelete(id: number | null) {
    if (id === null) return;
    if (!confirm("Delete this venue? This cannot be undone.")) return;
    setActionPending(true);
    setError(null);
    try {
      await venuesApi.deleteVenue(id);
      await load(city || undefined);
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setActionPending(false);
    }
  }

  return (
    <div>
      <div className="page-header">
        <h1>Venues</h1>
        {isAdmin && (
          <Link to="/venues/new" className="button">
            New venue
          </Link>
        )}
      </div>

      <form className="filter-bar" onSubmit={handleFilter}>
        <label>
          City
          <input value={city} onChange={(e) => setCity(e.target.value)} placeholder="e.g. New York" />
        </label>
        <button type="submit">Filter</button>
        <button type="button" onClick={clearFilter}>
          Clear
        </button>
      </form>

      <ErrorBanner message={error} />

      {loading ? (
        <p>Loading...</p>
      ) : venues.length === 0 ? (
        <p>No venues found.</p>
      ) : (
        <div className="card-grid">
          {venues.map((v) => (
            <div className="card event-card" key={v.id}>
              <h3>{v.name}</h3>
              <p className="muted">
                {v.address}, {v.city}, {v.state} {v.zipCode}
              </p>
              {v.capacity !== undefined && <p className="muted">Capacity: {v.capacity}</p>}
              {isAdmin && (
                <div className="button-row">
                  <button
                    type="button"
                    className="button small"
                    onClick={() => navigate(`/venues/${v.id}/edit`)}
                  >
                    Edit
                  </button>
                  <button
                    type="button"
                    className="button danger small"
                    disabled={actionPending}
                    onClick={() => handleDelete(v.id)}
                  >
                    Delete
                  </button>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
