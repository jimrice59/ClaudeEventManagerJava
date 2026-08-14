import { useEffect, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import * as eventsApi from "../api/events";
import { extractErrorMessage } from "../api/client";
import { ErrorBanner } from "../components/ErrorBanner";
import { useAuth } from "../context/AuthContext";
import type { EventResponse } from "../types";

export function EventDetailPage() {
  const { id } = useParams<{ id: string }>();
  const eventId = Number(id);
  const navigate = useNavigate();
  const { isAuthenticated, isAdmin } = useAuth();

  const [event, setEvent] = useState<EventResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [ticketCount, setTicketCount] = useState(1);
  const [actionPending, setActionPending] = useState(false);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      setEvent(await eventsApi.getEvent(eventId));
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [eventId]);

  async function handleReserve(evt: FormEvent) {
    evt.preventDefault();
    setActionPending(true);
    setError(null);
    try {
      setEvent(await eventsApi.reserveTickets(eventId, ticketCount));
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setActionPending(false);
    }
  }

  async function handleRelease(evt: FormEvent) {
    evt.preventDefault();
    setActionPending(true);
    setError(null);
    try {
      setEvent(await eventsApi.releaseTickets(eventId, ticketCount));
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setActionPending(false);
    }
  }

  async function handleDelete() {
    if (!confirm("Delete this event? This cannot be undone.")) return;
    setActionPending(true);
    setError(null);
    try {
      await eventsApi.deleteEvent(eventId);
      navigate("/events", { replace: true });
    } catch (err) {
      setError(extractErrorMessage(err));
      setActionPending(false);
    }
  }

  if (loading) return <p>Loading...</p>;
  if (!event) return <ErrorBanner message={error ?? "Event not found."} />;

  return (
    <div className="card detail-card">
      <div className="page-header">
        <h1>{event.name}</h1>
        {isAuthenticated && (
          <div className="button-row">
            <Link to={`/events/${event.id}/edit`} className="button">
              Edit
            </Link>
            {isAdmin && (
              <button onClick={handleDelete} disabled={actionPending} className="button danger">
                Delete
              </button>
            )}
          </div>
        )}
      </div>

      <ErrorBanner message={error} />

      {event.description && <p>{event.description}</p>}
      <dl className="detail-grid">
        <dt>Date</dt>
        <dd>{new Date(event.eventDate).toLocaleString()}</dd>
        <dt>Venue</dt>
        <dd>
          {event.venue.name} &mdash; {event.venue.city}, {event.venue.state}
        </dd>
        <dt>Ticket price</dt>
        <dd>${event.ticketPrice.toFixed(2)}</dd>
        <dt>Tickets available</dt>
        <dd>{event.ticketsAvailable}</dd>
        <dt>Performers</dt>
        <dd>
          {event.performers.length === 0
            ? "None"
            : event.performers.map((p) => p.name).join(", ")}
        </dd>
      </dl>

      {isAuthenticated && (
        <form className="ticket-form" onSubmit={handleReserve}>
          <label>
            Ticket count
            <input
              type="number"
              min={1}
              value={ticketCount}
              onChange={(e) => setTicketCount(Number(e.target.value))}
            />
          </label>
          <button type="submit" disabled={actionPending}>
            Reserve
          </button>
          <button type="button" onClick={handleRelease} disabled={actionPending}>
            Release
          </button>
        </form>
      )}
    </div>
  );
}
