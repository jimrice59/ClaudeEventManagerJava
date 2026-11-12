import { useEffect, useState, type FormEvent } from "react";
import axios from "axios";
import * as eventsApi from "../api/events";
import * as ticketsApi from "../api/tickets";
import { extractErrorMessage } from "../api/client";
import { ErrorBanner } from "./ErrorBanner";
import { TicketLabel } from "./TicketLabel";
import type { TicketResponse } from "../types";

/** Outcome reported back to the parent so it can show a banner and reload the list. */
export type PurchaseResult =
  | { kind: "approved"; ticket: TicketResponse }
  | { kind: "declined"; ticket: TicketResponse }
  | { kind: "pending" } // 502: payment-service outcome unknown, ticket left PAYING
  | { kind: "changed"; message: string }; // 400/409: ticket is no longer RESERVED

function newPaymentRequestId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  // Non-secure contexts (plain-HTTP non-localhost origins) don't expose randomUUID.
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === "x" ? r : (r & 0x3) | 0x8).toString(16);
  });
}

interface Props {
  ticket: TicketResponse;
  /** Width to pad the ticket number to (its event's, see ticketIdWidth). */
  idWidth: number;
  onDone: (result: PurchaseResult) => void;
  onCancel: () => void;
}

/**
 * Inline checkout form for a RESERVED ticket. One paymentRequestId is generated when the panel
 * opens and reused for every submit from it, so retrying after a network failure (where the
 * request may or may not have reached the backend) is idempotent end to end — the backend and
 * the payment service see the same id rather than a fresh one per attempt.
 */
export function PurchasePanel({ ticket, idWidth, onDone, onCancel }: Props) {
  const [paymentRequestId] = useState(newPaymentRequestId);
  const [paymentToken, setPaymentToken] = useState("");
  const [price, setPrice] = useState<number | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    eventsApi
      .getEvent(ticket.eventId)
      .then((event) => !cancelled && setPrice(event.ticketPrice))
      .catch(() => !cancelled && setPrice(null));
    return () => {
      cancelled = true;
    };
  }, [ticket.eventId]);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!paymentToken.trim()) return;
    setSubmitting(true);
    setError(null);
    try {
      const result = await ticketsApi.purchaseTicket(ticket.id, paymentToken.trim(), paymentRequestId);
      onDone({ kind: result.status === "SOLD" ? "approved" : "declined", ticket: result });
    } catch (err) {
      const status = axios.isAxiosError(err) ? err.response?.status : undefined;
      if (status === 502) {
        onDone({ kind: "pending" });
      } else if (status === 400 || status === 409) {
        onDone({ kind: "changed", message: extractErrorMessage(err) });
      } else {
        // No response (network error) or something unexpected: stay open so the user can retry
        // with the same paymentRequestId.
        setError(`${extractErrorMessage(err)} You can retry safely — the same payment request will be reused.`);
        setSubmitting(false);
      }
    }
  }

  return (
    <form className="purchase-panel" onSubmit={handleSubmit}>
      <div className="purchase-summary">
        <strong>Checkout</strong>
        <span>
          <TicketLabel id={ticket.id} width={idWidth} description={ticket.description} /> &middot; {ticket.eventName}
        </span>
        <span className="purchase-price">{price === null ? "Price unavailable" : `$${price.toFixed(2)}`}</span>
      </div>

      <ErrorBanner message={error} />

      <label>
        Payment token
        <input
          value={paymentToken}
          onChange={(e) => setPaymentToken(e.target.value)}
          placeholder="e.g. tok_visa"
          autoFocus
          required
          disabled={submitting}
        />
      </label>

      <div className="button-row">
        <button type="submit" className="button small" disabled={submitting || !paymentToken.trim()}>
          {submitting ? "Processing payment…" : price === null ? "Pay" : `Pay $${price.toFixed(2)}`}
        </button>
        <button type="button" className="button small" onClick={onCancel} disabled={submitting}>
          Back
        </button>
      </div>
      {submitting && (
        <p className="muted small">This can take up to two minutes. Please keep this page open.</p>
      )}
    </form>
  );
}
