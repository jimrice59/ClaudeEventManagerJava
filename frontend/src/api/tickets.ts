import { apiClient } from "./client";
import type { AvailableTicketsPage, PagedResponse, TicketResponse, TicketTransaction } from "../types";

export async function getTicket(id: number): Promise<TicketResponse> {
  const { data } = await apiClient.get<TicketResponse>(`/tickets/${id}`);
  return data;
}

/** Paginated AVAILABLE tickets for an event; page is 0-based. */
export async function getAvailableTickets(
  eventId: number,
  page = 0,
  size = 20,
): Promise<AvailableTicketsPage> {
  const { data } = await apiClient.get<AvailableTicketsPage>(
    `/events/${eventId}/tickets/available`,
    { params: { page, size } },
  );
  return data;
}

/** Paginated list of every ticket owned by the logged-in user, across all events. */
export async function getMyTickets(page = 0, size = 20): Promise<PagedResponse<TicketResponse>> {
  const { data } = await apiClient.get<PagedResponse<TicketResponse>>("/tickets/me", {
    params: { page, size },
  });
  return data;
}

export async function reserveTicket(id: number): Promise<TicketResponse> {
  const { data } = await apiClient.post<TicketResponse>(`/tickets/${id}/reserve`);
  return data;
}

/** Paginated list of the caller's ticket transactions, newest first. */
export async function getMyTransactions(page = 0, size = 20): Promise<PagedResponse<TicketTransaction>> {
  const { data } = await apiClient.get<PagedResponse<TicketTransaction>>("/tickets/me/transactions", {
    params: { page, size },
  });
  return data;
}

/**
 * Reserves the next AVAILABLE ticket for an event — the server picks which one. 409 if none are
 * left (the backend's message says so; surface it via extractErrorMessage).
 */
export async function reserveAvailableTicket(eventId: number): Promise<TicketResponse> {
  const { data } = await apiClient.post<TicketResponse>(`/events/${eventId}/tickets/reserve`);
  return data;
}

export async function releaseTicket(id: number): Promise<TicketResponse> {
  const { data } = await apiClient.post<TicketResponse>(`/tickets/${id}/release`);
  return data;
}

/**
 * Charges the ticket via the backend's payment-service integration. `paymentRequestId` is an
 * optional idempotency key for the purchase attempt; the backend generates one if omitted.
 */
export async function purchaseTicket(
  id: number,
  paymentToken: string,
  paymentRequestId?: string,
): Promise<TicketResponse> {
  const { data } = await apiClient.post<TicketResponse>(`/tickets/${id}/purchase`, {
    paymentToken,
    paymentRequestId,
  });
  return data;
}

export async function cancelTicket(id: number): Promise<TicketResponse> {
  const { data } = await apiClient.post<TicketResponse>(`/tickets/${id}/cancel`);
  return data;
}
