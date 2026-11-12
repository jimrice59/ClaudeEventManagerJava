// Mirrors the DTOs in com.eventmanager.dto (backend: src/main/java/com/eventmanager/dto)

export type Role = "ROLE_USER" | "ROLE_ADMIN";

export interface AuthResponse {
  token: string;
  type: string;
  username: string;
  email: string;
  role: Role;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
}

export interface VenueDto {
  id: number | null;
  name: string;
  address: string;
  city: string;
  state: string;
  zipCode?: string;
  capacity?: number;
}

export interface PerformerDto {
  id: number | null;
  name: string;
  genre?: string;
  bio?: string;
  videoUrls?: string[];
}

export interface EventRequest {
  name: string;
  description?: string;
  eventDate: string; // ISO 8601, e.g. 2025-08-15T19:00:00
  ticketPrice: number;
  ticketsTotal: number; // fixed at creation — ignored on update
  venueId: number;
  performerIds?: number[];
}

export interface EventResponse {
  id: number;
  name: string;
  description?: string;
  eventDate: string;
  ticketPrice: number;
  ticketsTotal: number; // fixed capacity; see getNumAvailableTickets for the live available count
  venue: VenueDto;
  performers: PerformerDto[];
  createdAt: string;
  updatedAt: string;
}

export interface VideoRequest {
  url: string;
}

/**
 * Query params for GET /api/v1/events. Every field is optional and the ones set are ANDed by the
 * backend; start/end are inclusive and each works on its own. Undefined fields aren't sent.
 */
export interface EventListFilters {
  venueId?: number;
  performerId?: number;
  start?: string;
  end?: string;
}

export type TicketStatus = "AVAILABLE" | "RESERVED" | "PAYING" | "SOLD";

/**
 * GET /events/{id}/tickets/available: a page of AVAILABLE tickets plus the event's largest ticket
 * id (any status), used to pad ticket numbers to one width for the whole event.
 */
export interface AvailableTicketsPage extends PagedResponse<TicketResponse> {
  maxTicketId: number | null;
}

/**
 * GET /tickets/me and /tickets/me/transactions: a page that can span several events, plus each
 * listed event's largest ticket id (keyed by event id), for padding ticket numbers per event.
 */
export interface TicketListPage<T> extends PagedResponse<T> {
  maxTicketIdByEvent: Record<string, number>;
}

export type TicketOperationType = "PURCHASE" | "PAYMENT_DECLINED" | "CANCEL" | "PAYMENT_ERROR";

/**
 * One of the caller's ticket transactions (GET /api/v1/tickets/me/transactions). The server never
 * sends the payment idempotency token. operationDate is ISO-8601 with a UTC offset.
 */
export interface TicketTransaction {
  id: number;
  ticketId: number;
  ticketDescription: string | null;
  eventId: number;
  eventName: string | null;
  operation: TicketOperationType;
  purchasePrice: number;
  paymentService: string;
  paymentConfirmationDetails: string;
  paymentRequestId: string | null;
  operationDate: string;
}

export interface PerformerSummary {
  id: number;
  name: string;
}

export interface TicketResponse {
  id: number;
  eventId: number;
  eventName: string;
  eventDate: string;
  description?: string;
  venueId: number;
  venueName: string;
  performers: PerformerSummary[];
  status: TicketStatus;
  userId: number | null;
  paymentRequestId: string | null;
  datePurchased: string | null;
}

export interface PurchaseTicketRequest {
  paymentToken: string;
  paymentRequestId?: string;
}

export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export interface ApiErrorBody {
  message?: string;
  errors?: Record<string, string>;
  [key: string]: unknown;
}
