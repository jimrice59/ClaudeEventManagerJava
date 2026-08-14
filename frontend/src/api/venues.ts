import { apiClient } from "./client";
import type { VenueDto } from "../types";

export async function getVenues(city?: string): Promise<VenueDto[]> {
  const { data } = await apiClient.get<VenueDto[]>("/venues", { params: city ? { city } : {} });
  return data;
}

export async function getVenue(id: number): Promise<VenueDto> {
  const { data } = await apiClient.get<VenueDto>(`/venues/${id}`);
  return data;
}

export async function createVenue(dto: VenueDto): Promise<VenueDto> {
  const { data } = await apiClient.post<VenueDto>("/venues", dto);
  return data;
}

export async function updateVenue(id: number, dto: VenueDto): Promise<VenueDto> {
  const { data } = await apiClient.put<VenueDto>(`/venues/${id}`, dto);
  return data;
}

export async function deleteVenue(id: number): Promise<void> {
  await apiClient.delete(`/venues/${id}`);
}
