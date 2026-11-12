import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import * as ticketsApi from "../api/tickets";
import { extractErrorMessage } from "../api/client";
import { ErrorBanner } from "../components/ErrorBanner";
import { PurchasePanel, type PurchaseResult } from "../components/PurchasePanel";
import { TicketLabel } from "../components/TicketLabel";
import { ticketLabelText } from "../utils/format";
import type { PagedResponse, TicketResponse } from "../types";

const PAGE_SIZE = 20;

export function MyTicketsPage() {
  const [tickets, setTickets] = useState<PagedResponse<TicketResponse> | null>(null);
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionPending, setActionPending] = useState(false);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [purchasingId, setPurchasingId] = useState<number | null>(null);

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

  async function handlePurchaseDone(result: PurchaseResult) {
    setPurchasingId(null);
    setError(null);
    setSuccessMessage(null);
    switch (result.kind) {
      case "approved":
        setSuccessMessage(
          `Payment approved — ${ticketLabelText(result.ticket.id, result.ticket.description)} for ${result.ticket.eventName} is yours.`,
        );
        break;
      case "declined":
        setError("Payment was declined. The ticket has been released; reserve it again to try another payment method.");
        break;
      case "pending":
        setError(
          "We couldn't confirm the payment with the payment service, so the ticket is held as PAYING. " +
            "It can't be retried or released from here (that could double-charge or double-sell it) — " +
            "contact support to resolve it.",
        );
        break;
      case "changed":
        setError(result.message);
        break;
    }
    await load(page);
  }

  async function handleRelease(id: number) {
    setActionPending(true);
    setError(null);
    setSuccessMessage(null);
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
    if (!confirm("Cancel this ticket? It will be released back to the event.")) return;
    setActionPending(true);
    setError(null);
    setSuccessMessage(null);
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
        <button onClick={() => load(page)} disabled={loading} className="button small">
          Refresh
        </button>
      </div>

      <ErrorBanner message={error} />
      {successMessage && <div className="success-banner">{successMessage}</div>}

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
                  <div>
                    <TicketLabel id={ticket.id} description={ticket.description} />
                  </div>
                  <Link to={`/events/${ticket.eventId}`}>{ticket.eventName}</Link>
                  <div className="muted small">
                    {new Date(ticket.eventDate).toLocaleString()} &middot; {ticket.venueName}
                  </div>
                </div>
                <span className={`badge status-${ticket.status.toLowerCase()}`}>{ticket.status}</span>
                <div className="button-row">
                  {ticket.status === "RESERVED" && (
                    <>
                      <button
                        onClick={() => setPurchasingId(ticket.id)}
                        disabled={actionPending || purchasingId !== null}
                        className="button small"
                      >
                        Purchase
                      </button>
                      <button
                        onClick={() => handleRelease(ticket.id)}
                        disabled={actionPending || purchasingId !== null}
                        className="button small"
                      >
                        Release
                      </button>
                    </>
                  )}
                  {ticket.status === "PAYING" && (
                    <span className="muted small">Payment unconfirmed — contact support</span>
                  )}
                  {ticket.status === "SOLD" && (
                    <button onClick={() => handleCancel(ticket.id)} disabled={actionPending} className="button danger small">
                      Cancel
                    </button>
                  )}
                </div>
                {purchasingId === ticket.id && (
                  <PurchasePanel
                    ticket={ticket}
                    onDone={handlePurchaseDone}
                    onCancel={() => setPurchasingId(null)}
                  />
                )}
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
