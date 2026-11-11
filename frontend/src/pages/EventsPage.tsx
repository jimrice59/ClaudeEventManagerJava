import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import * as eventsApi from "../api/events";
import * as performersApi from "../api/performers";
import * as venuesApi from "../api/venues";
import { extractErrorMessage } from "../api/client";
import { ErrorBanner } from "../components/ErrorBanner";
import { useAuth } from "../context/AuthContext";
import type { EventListFilters, EventResponse, PerformerDto, VenueDto } from "../types";

export function EventsPage() {
  const { isAdmin } = useAuth();
  const [events, setEvents] = useState<EventResponse[]>([]);
  const [venues, setVenues] = useState<VenueDto[]>([]);
  const [performers, setPerformers] = useState<PerformerDto[]>([]);
  const [venueId, setVenueId] = useState("");
  const [performerId, setPerformerId] = useState("");
  const [start, setStart] = useState("");
  const [end, setEnd] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Dropdown options only — if either list fails to load, that filter just offers "Any".
    venuesApi.getVenues().then(setVenues).catch(() => undefined);
    performersApi.getPerformers().then(setPerformers).catch(() => undefined);
  }, []);

  async function loadEvents(filters: EventListFilters = {}) {
    setLoading(true);
    setError(null);
    try {
      setEvents(await eventsApi.getEvents(filters));
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    loadEvents();
  }, []);

  function handleFilter(event: FormEvent) {
    event.preventDefault();
    // Send every filter that's filled in; the backend ANDs them. Empty fields become undefined,
    // which axios omits. A start after end comes back as a 400 whose message the banner shows.
    loadEvents({
      venueId: venueId ? Number(venueId) : undefined,
      performerId: performerId ? Number(performerId) : undefined,
      start: start || undefined,
      end: end || undefined,
    });
  }

  function clearFilters() {
    setVenueId("");
    setPerformerId("");
    setStart("");
    setEnd("");
    loadEvents();
  }

  return (
    <div>
      <div className="page-header">
        <h1>Events</h1>
        {isAdmin && (
          <Link to="/events/new" className="button">
            New event
          </Link>
        )}
      </div>

      <form className="filter-bar" onSubmit={handleFilter}>
        <label>
          Venue
          <select value={venueId} onChange={(e) => setVenueId(e.target.value)}>
            <option value="">Any</option>
            {venues.map((v) => (
              <option key={v.id} value={v.id ?? undefined}>
                {v.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          Performer
          <select value={performerId} onChange={(e) => setPerformerId(e.target.value)}>
            <option value="">Any</option>
            {performers.map((p) => (
              <option key={p.id} value={p.id ?? undefined}>
                {p.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          From
          <input type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} />
        </label>
        <label>
          To
          <input type="datetime-local" value={end} onChange={(e) => setEnd(e.target.value)} />
        </label>
        <div className="filter-actions">
          <button type="submit">Filter</button>
          <button type="button" onClick={clearFilters}>
            Clear
          </button>
        </div>
      </form>

      <ErrorBanner message={error} />

      {loading ? (
        <p>Loading...</p>
      ) : events.length === 0 ? (
        <p>No events found.</p>
      ) : (
        <div className="card-grid">
          {events.map((event) => (
            <Link to={`/events/${event.id}`} key={event.id} className="card event-card">
              <h3>{event.name}</h3>
              <p className="muted">{new Date(event.eventDate).toLocaleString()}</p>
              <p>{event.venue.name}</p>
              {event.performers.length > 0 && (
                <p className="muted">{event.performers.map((p) => p.name).join(", ")}</p>
              )}
              <p className="muted">
                ${event.ticketPrice.toFixed(2)} &middot; {event.ticketsTotal} tickets total
              </p>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
