import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import * as eventsApi from "../api/events";
import * as ticketsApi from "../api/tickets";
import { extractErrorMessage } from "../api/client";
import { ErrorBanner } from "../components/ErrorBanner";
import { useAuth } from "../context/AuthContext";
import type { EventResponse, PagedResponse, TicketResponse } from "../types";

const TICKETS_PAGE_SIZE = 10;

export function EventDetailPage() {
  const { id } = useParams<{ id: string }>();
  const eventId = Number(id);
  const navigate = useNavigate();
  const { isAdmin, isAuthenticated } = useAuth();

  const [event, setEvent] = useState<EventResponse | null>(null);
  const [numAvailable, setNumAvailable] = useState<number | null>(null);
  const [availableTickets, setAvailableTickets] = useState<PagedResponse<TicketResponse> | null>(null);
  const [ticketsPage, setTicketsPage] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionPending, setActionPending] = useState(false);

  async function loadTickets() {
    setAvailableTickets(await ticketsApi.getAvailableTickets(eventId, ticketsPage, TICKETS_PAGE_SIZE));
  }

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const [eventData, availableCount] = await Promise.all([
        eventsApi.getEvent(eventId),
        eventsApi.getNumAvailableTickets(eventId),
      ]);
      setEvent(eventData);
      setNumAvailable(availableCount);
      await loadTickets();
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [eventId, ticketsPage]);

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

  async function handleReserve(ticketId: number) {
    setActionPending(true);
    setError(null);
    setSuccessMessage(null);
    try {
      await ticketsApi.reserveTicket(ticketId);
      setSuccessMessage("Ticket reserved! Find it under My Tickets to purchase or release it.");
      setNumAvailable(await eventsApi.getNumAvailableTickets(eventId));
      await loadTickets();
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setActionPending(false);
    }
  }

  if (loading) return <p>Loading...</p>;
  if (!event) return <ErrorBanner message={error ?? "Event not found."} />;

  return (
    <div className="card detail-card">
      <div className="page-header">
        <h1>{event.name}</h1>
        {isAdmin && (
          <div className="button-row">
            <Link to={`/events/${event.id}/edit`} className="button">
              Edit
            </Link>
            <button onClick={handleDelete} disabled={actionPending} className="button danger">
              Delete
            </button>
          </div>
        )}
      </div>

      <ErrorBanner message={error} />
      {successMessage && <div className="success-banner">{successMessage}</div>}

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
        <dt>Tickets total</dt>
        <dd>{event.ticketsTotal}</dd>
        <dt>Available now</dt>
        <dd>{numAvailable ?? "—"}</dd>
        <dt>Performers</dt>
        <dd>
          {event.performers.length === 0
            ? "None"
            : event.performers.map((p) => p.name).join(", ")}
        </dd>
      </dl>

      <h2>Available Tickets</h2>
      {!availableTickets || availableTickets.content.length === 0 ? (
        <p className="muted">No tickets currently available.</p>
      ) : (
        <>
          <ul className="ticket-list">
            {availableTickets.content.map((ticket) => (
              <li key={ticket.id}>
                <span>Ticket #{ticket.id}</span>
                {isAuthenticated ? (
                  <button onClick={() => handleReserve(ticket.id)} disabled={actionPending} className="button small">
                    Reserve
                  </button>
                ) : (
                  <Link to="/login" className="button small">
                    Log in to reserve
                  </Link>
                )}
              </li>
            ))}
          </ul>
          <div className="pagination">
            <button
              onClick={() => setTicketsPage((p) => p - 1)}
              disabled={ticketsPage === 0 || loading}
              className="button small"
            >
              Previous
            </button>
            <span className="muted">
              Page {availableTickets.page + 1} of {Math.max(availableTickets.totalPages, 1)}
            </span>
            <button
              onClick={() => setTicketsPage((p) => p + 1)}
              disabled={availableTickets.last || loading}
              className="button small"
            >
              Next
            </button>
          </div>
        </>
      )}
    </div>
  );
}
