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
  ticketsAvailable: number;
  venueId: number;
  performerIds?: number[];
}

export interface EventResponse {
  id: number;
  name: string;
  description?: string;
  eventDate: string;
  ticketPrice: number;
  ticketsAvailable: number;
  venue: VenueDto;
  performers: PerformerDto[];
  createdAt: string;
  updatedAt: string;
}

export interface TicketRequest {
  count: number;
}

export interface VideoRequest {
  url: string;
}

export interface EventListFilters {
  venueId?: number;
  start?: string;
  end?: string;
}

export interface ApiErrorBody {
  message?: string;
  errors?: Record<string, string>;
  [key: string]: unknown;
}
