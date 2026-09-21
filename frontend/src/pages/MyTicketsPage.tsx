import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import * as ticketsApi from "../api/tickets";
import { extractErrorMessage } from "../api/client";
import { ErrorBanner } from "../components/ErrorBanner";
import type { PagedResponse, TicketResponse } from "../types";

const PAGE_SIZE = 20;

export function MyTicketsPage() {
  const [tickets, setTickets] = useState<PagedResponse<TicketResponse> | null>(null);
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionPending, setActionPending] = useState(false);

  async function load(targetPage: number) {
    setLoading(true);
    setError(null);
    try {
      setTickets(await ticketsApi.getMyTickets(targetPage, PAGE_SIZE));
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load(page);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  async function handlePurchase(id: number) {
    const userCredentials = window.prompt("Enter payment details to complete purchase:");
    if (!userCredentials) return;
    setActionPending(true);
    setError(null);
    try {
      await ticketsApi.purchaseTicket(id, userCredentials);
      await load(page);
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setActionPending(false);
    }
  }

  async function handleRelease(id: number) {
    setActionPending(true);
    setError(null);
    try {
      await ticketsApi.releaseTicket(id);
      await load(page);
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setActionPending(false);
    }
  }

  async function handleCancel(id: number) {
    if (!confirm("Cancel this ticket? This cannot be undone.")) return;
    setActionPending(true);
    setError(null);
    try {
      await ticketsApi.cancelTicket(id);
      await load(page);
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setActionPending(false);
    }
  }

  return (
    <div>
      <div className="page-header">
        <h1>My Tickets</h1>
      </div>

      <ErrorBanner message={error} />

      {loading ? (
        <p>Loading...</p>
      ) : !tickets || tickets.content.length === 0 ? (
        <p className="muted">
          You don't have any tickets yet. Find an event and reserve one from its detail page.
        </p>
      ) : (
        <>
          <ul className="ticket-list">
            {tickets.content.map((ticket) => (
              <li key={ticket.id}>
                <div>
                  <Link to={`/events/${ticket.eventId}`}>{ticket.eventName}</Link>
                  <div className="muted small">
                    {new Date(ticket.eventDate).toLocaleString()} &middot; {ticket.venueName}
                  </div>
                </div>
                <span className={`badge status-${ticket.status.toLowerCase()}`}>{ticket.status}</span>
                <div className="button-row">
                  {ticket.status === "RESERVED" && (
                    <>
                      <button onClick={() => handlePurchase(ticket.id)} disabled={actionPending} className="button small">
                        Purchase
                      </button>
                      <button onClick={() => handleRelease(ticket.id)} disabled={actionPending} className="button small">
                        Release
                      </button>
                    </>
                  )}
                  {ticket.status === "SOLD" && (
                    <button onClick={() => handleCancel(ticket.id)} disabled={actionPending} className="button danger small">
                      Cancel
                    </button>
                  )}
                </div>
              </li>
            ))}
          </ul>

          <div className="pagination">
            <button onClick={() => setPage((p) => p - 1)} disabled={page === 0 || loading} className="button small">
              Previous
            </button>
            <span className="muted">
              Page {tickets.page + 1} of {Math.max(tickets.totalPages, 1)}
            </span>
            <button onClick={() => setPage((p) => p + 1)} disabled={tickets.last || loading} className="button small">
              Next
            </button>
          </div>
        </>
      )}
    </div>
  );
}
