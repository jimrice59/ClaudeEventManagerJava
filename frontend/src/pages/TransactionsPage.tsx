import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import * as ticketsApi from "../api/tickets";
import { extractErrorMessage } from "../api/client";
import { ErrorBanner } from "../components/ErrorBanner";
import { formatDateTime, formatMoney, formatRelative } from "../utils/format";
import type { PagedResponse, TicketOperationType, TicketTransaction } from "../types";

const PAGE_SIZE = 20;

const LABELS: Record<TicketOperationType, string> = {
  PURCHASE: "Purchase",
  PAYMENT_DECLINED: "Payment declined",
  CANCEL: "Cancellation",
  PAYMENT_ERROR: "Payment error",
};

/** The caller's purchases, declined payments, and cancellations, newest first. */
export function TransactionsPage() {
  const [transactions, setTransactions] = useState<PagedResponse<TicketTransaction> | null>(null);
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  async function load(targetPage: number) {
    setLoading(true);
    setError(null);
    try {
      setTransactions(await ticketsApi.getMyTransactions(targetPage, PAGE_SIZE));
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

  return (
    <div>
      <div className="page-header">
        <h1>My Transactions</h1>
        <button onClick={() => load(page)} disabled={loading} className="button small">
          Refresh
        </button>
      </div>

      <ErrorBanner message={error} />

      {loading ? (
        <p>Loading...</p>
      ) : !transactions || transactions.content.length === 0 ? (
        <p className="muted">No transactions yet. Purchases and cancellations will show up here.</p>
      ) : (
        <>
          <ul className="transaction-list">
            {transactions.content.map((t) => (
              <li key={t.id}>
                <div className="transaction-main">
                  <span className={`badge op-${t.operation.toLowerCase().replace("_", "-")}`}>
                    {LABELS[t.operation] ?? t.operation}
                  </span>
                  <div>
                    <Link to={`/events/${t.eventId}`} className="transaction-event">
                      {t.eventName ?? `Event #${t.eventId}`}
                    </Link>
                    <div className="muted small">Ticket #{t.ticketId}</div>
                  </div>
                </div>
                <div className="transaction-when">
                  <time dateTime={t.operationDate}>{formatDateTime(t.operationDate)}</time>
                  <div className="muted small">{formatRelative(t.operationDate)}</div>
                </div>
                <div className="transaction-amount">
                  <span className={t.operation === "PAYMENT_DECLINED" ? "amount-void" : undefined}>
                    {formatMoney(t.purchasePrice)}
                  </span>
                  {t.operation === "PAYMENT_DECLINED" && <div className="muted small">Not charged</div>}
                  {t.operation === "PURCHASE" && (
                    <div className="muted small confirmation" title={t.paymentConfirmationDetails}>
                      Conf. {t.paymentConfirmationDetails}
                    </div>
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
              Page {transactions.page + 1} of {Math.max(transactions.totalPages, 1)}
            </span>
            <button onClick={() => setPage((p) => p + 1)} disabled={transactions.last || loading} className="button small">
              Next
            </button>
          </div>
        </>
      )}
    </div>
  );
}
