import { ticketLabelText } from "../utils/format";

/**
 * "Ticket 00007 Open Seating": the ticket's description, which the backend writes at creation as
 * the id zero-padded to its event's width plus the seating ("00007 Open Seating"), so no
 * formatting happens here. Falls back to "Ticket #7" for a ticket with no description. Used on the
 * event page, My Tickets (including the checkout panel), and My Transactions.
 */
export function TicketLabel({ id, description }: { id: number; description?: string | null }) {
  return <span className="ticket-label">{ticketLabelText(id, description)}</span>;
}
