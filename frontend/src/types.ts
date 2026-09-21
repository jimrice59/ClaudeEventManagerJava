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

export interface EventListFilters {
  venueId?: number;
  start?: string;
  end?: string;
}

export type TicketStatus = "AVAILABLE" | "RESERVED" | "SOLD";

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
}

export interface PurchaseTicketRequest {
  userCredentials: string;
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
