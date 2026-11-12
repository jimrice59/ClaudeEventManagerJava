import { formatTicketId } from "../utils/format";

/**
 * "Ticket 00007 Open Seating": the ticket id zero-padded to its event's width (see ticketIdWidth),
 * in tabular figures so numbers line up, followed by the description when there is one. Used on
 * the event page, My Tickets, and My Transactions so a ticket reads the same everywhere.
 */
export function TicketLabel({ id, width, description }: { id: number; width: number; description?: string | null }) {
  return (
    <span className="ticket-label">
      Ticket <span className="ticket-number">{formatTicketId(id, width)}</span>
      {description && <span className="ticket-description"> {description}</span>}
    </span>
  );
}
