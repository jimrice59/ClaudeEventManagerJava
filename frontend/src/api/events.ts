import { apiClient } from "./client";
import type { EventListFilters, EventRequest, EventResponse } from "../types";

export async function getEvents(filters: EventListFilters = {}): Promise<EventResponse[]> {
  const { data } = await apiClient.get<EventResponse[]>("/events", { params: filters });
  return data;
}

export async function getEvent(id: number): Promise<EventResponse> {
  const { data } = await apiClient.get<EventResponse>(`/events/${id}`);
  return data;
}

export async function createEvent(request: EventRequest): Promise<EventResponse> {
  const { data } = await apiClient.post<EventResponse>("/events", request);
  return data;
}

export async function updateEvent(id: number, request: EventRequest): Promise<EventResponse> {
  const { data } = await apiClient.put<EventResponse>(`/events/${id}`, request);
  return data;
}

export async function deleteEvent(id: number): Promise<void> {
  await apiClient.delete(`/events/${id}`);
}

export async function getNumAvailableTickets(id: number): Promise<number> {
  const { data } = await apiClient.get<number>(`/events/${id}/tickets/available/count`);
  return data;
}
