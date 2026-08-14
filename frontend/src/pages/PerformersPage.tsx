import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import * as performersApi from "../api/performers";
import { extractErrorMessage } from "../api/client";
import { ErrorBanner } from "../components/ErrorBanner";
import { useAuth } from "../context/AuthContext";
import type { PerformerDto } from "../types";

export function PerformersPage() {
  const { isAdmin } = useAuth();
  const [performers, setPerformers] = useState<PerformerDto[]>([]);
  const [name, setName] = useState("");
  const [genre, setGenre] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function load(filters: { name?: string; genre?: string } = {}) {
    setLoading(true);
    setError(null);
    try {
      setPerformers(await performersApi.getPerformers(filters));
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
    if (name) {
      load({ name });
      return;
    }
    if (genre) {
      load({ genre });
      return;
    }
    load();
  }

  function clearFilters() {
    setName("");
    setGenre("");
    load();
  }

  return (
    <div>
      <div className="page-header">
        <h1>Performers</h1>
        {isAdmin && (
          <Link to="/performers/new" className="button">
            New performer
          </Link>
        )}
      </div>

      <form className="filter-bar" onSubmit={handleFilter}>
        <label>
          Name contains
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Beatles" />
        </label>
        <label>
          Genre
          <input value={genre} onChange={(e) => setGenre(e.target.value)} placeholder="e.g. Rock" />
        </label>
        <button type="submit">Filter</button>
        <button type="button" onClick={clearFilters}>
          Clear
        </button>
      </form>

      <ErrorBanner message={error} />

      {loading ? (
        <p>Loading...</p>
      ) : performers.length === 0 ? (
        <p>No performers found.</p>
      ) : (
        <div className="card-grid">
          {performers.map((p) => (
            <Link to={`/performers/${p.id}`} key={p.id} className="card event-card">
              <h3>{p.name}</h3>
              {p.genre && <p className="muted">{p.genre}</p>}
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
