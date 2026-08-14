import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import * as eventsApi from "../api/events";
import * as venuesApi from "../api/venues";
import { extractErrorMessage } from "../api/client";
import { ErrorBanner } from "../components/ErrorBanner";
import { useAuth } from "../context/AuthContext";
import type { EventResponse, VenueDto } from "../types";

export function EventsPage() {
  const { isAuthenticated } = useAuth();
  const [events, setEvents] = useState<EventResponse[]>([]);
  const [venues, setVenues] = useState<VenueDto[]>([]);
  const [venueId, setVenueId] = useState("");
  const [start, setStart] = useState("");
  const [end, setEnd] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    venuesApi.getVenues().then(setVenues).catch(() => undefined);
  }, []);

  async function loadEvents(filters: { venueId?: number; start?: string; end?: string } = {}) {
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
    if (venueId) {
      loadEvents({ venueId: Number(venueId) });
      return;
    }
    if (start && end) {
      loadEvents({ start, end });
      return;
    }
    loadEvents();
  }

  function clearFilters() {
    setVenueId("");
    setStart("");
    setEnd("");
    loadEvents();
  }

  return (
    <div>
      <div className="page-header">
        <h1>Events</h1>
        {isAuthenticated && (
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
          From
          <input type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} />
        </label>
        <label>
          To
          <input type="datetime-local" value={end} onChange={(e) => setEnd(e.target.value)} />
        </label>
        <button type="submit">Filter</button>
        <button type="button" onClick={clearFilters}>
          Clear
        </button>
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
              <p className="muted">
                ${event.ticketPrice.toFixed(2)} &middot; {event.ticketsAvailable} tickets left
              </p>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
