import { apiClient } from "./client";
import type { PerformerDto } from "../types";

export interface PerformerListFilters {
  name?: string;
  genre?: string;
}

export async function getPerformers(filters: PerformerListFilters = {}): Promise<PerformerDto[]> {
  const { data } = await apiClient.get<PerformerDto[]>("/performers", { params: filters });
  return data;
}

export async function getPerformer(id: number): Promise<PerformerDto> {
  const { data } = await apiClient.get<PerformerDto>(`/performers/${id}`);
  return data;
}

export async function createPerformer(dto: PerformerDto): Promise<PerformerDto> {
  const { data } = await apiClient.post<PerformerDto>("/performers", dto);
  return data;
}

export async function updatePerformer(id: number, dto: PerformerDto): Promise<PerformerDto> {
  const { data } = await apiClient.put<PerformerDto>(`/performers/${id}`, dto);
  return data;
}

export async function deletePerformer(id: number): Promise<void> {
  await apiClient.delete(`/performers/${id}`);
}

export async function addVideo(id: number, url: string): Promise<PerformerDto> {
  const { data } = await apiClient.post<PerformerDto>(`/performers/${id}/videos`, { url });
  return data;
}

export async function deleteVideo(id: number, url: string): Promise<PerformerDto> {
  const { data } = await apiClient.delete<PerformerDto>(`/performers/${id}/videos`, { data: { url } });
  return data;
}
