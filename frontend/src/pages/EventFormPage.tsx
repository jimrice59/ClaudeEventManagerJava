import { useEffect, useState, type FormEvent } from "react";
import { useNavigate, useParams } from "react-router-dom";
import * as eventsApi from "../api/events";
import * as venuesApi from "../api/venues";
import * as performersApi from "../api/performers";
import { extractErrorMessage } from "../api/client";
import { ErrorBanner } from "../components/ErrorBanner";
import type { EventRequest, PerformerDto, VenueDto } from "../types";

function toDateTimeLocal(iso: string): string {
  // "2025-08-15T19:00:00" -> already compatible with <input type="datetime-local">
  return iso.slice(0, 16);
}

export function EventFormPage() {
  const { id } = useParams<{ id: string }>();
  const isEdit = id !== undefined;
  const navigate = useNavigate();

  const [venues, setVenues] = useState<VenueDto[]>([]);
  const [performers, setPerformers] = useState<PerformerDto[]>([]);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [eventDate, setEventDate] = useState("");
  const [ticketPrice, setTicketPrice] = useState("");
  const [ticketsAvailable, setTicketsAvailable] = useState("");
  const [venueId, setVenueId] = useState("");
  const [performerIds, setPerformerIds] = useState<Set<number>>(new Set());
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(isEdit);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    venuesApi.getVenues().then(setVenues).catch(() => undefined);
    performersApi.getPerformers().then(setPerformers).catch(() => undefined);
  }, []);

  useEffect(() => {
    if (!isEdit) return;
    eventsApi
      .getEvent(Number(id))
      .then((event) => {
        setName(event.name);
        setDescription(event.description ?? "");
        setEventDate(toDateTimeLocal(event.eventDate));
        setTicketPrice(String(event.ticketPrice));
        setTicketsAvailable(String(event.ticketsAvailable));
        setVenueId(String(event.venue.id));
        setPerformerIds(new Set(event.performers.map((p) => p.id).filter((pid): pid is number => pid !== null)));
      })
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setLoading(false));
  }, [id, isEdit]);

  function togglePerformer(pid: number) {
    setPerformerIds((prev) => {
      const next = new Set(prev);
      if (next.has(pid)) next.delete(pid);
      else next.add(pid);
      return next;
    });
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    const request: EventRequest = {
      name,
      description: description || undefined,
      eventDate: eventDate.length === 16 ? `${eventDate}:00` : eventDate,
      ticketPrice: Number(ticketPrice),
      ticketsAvailable: Number(ticketsAvailable),
      venueId: Number(venueId),
      performerIds: Array.from(performerIds),
    };
    try {
      const saved = isEdit
        ? await eventsApi.updateEvent(Number(id), request)
        : await eventsApi.createEvent(request);
      navigate(`/events/${saved.id}`, { replace: true });
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) return <p>Loading...</p>;

  return (
    <div className="card form-card">
      <h1>{isEdit ? "Edit event" : "New event"}</h1>
      <ErrorBanner message={error} />
      <form onSubmit={handleSubmit}>
        <label>
          Name
          <input value={name} onChange={(e) => setName(e.target.value)} required />
        </label>
        <label>
          Description
          <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={3} />
        </label>
        <label>
          Date &amp; time
          <input
            type="datetime-local"
            value={eventDate}
            onChange={(e) => setEventDate(e.target.value)}
            required
          />
        </label>
        <label>
          Ticket price
          <input
            type="number"
            min={0}
            max={10000}
            step="0.01"
            value={ticketPrice}
            onChange={(e) => setTicketPrice(e.target.value)}
            required
          />
        </label>
        <label>
          Tickets available
          <input
            type="number"
            min={0}
            value={ticketsAvailable}
            onChange={(e) => setTicketsAvailable(e.target.value)}
            required
          />
        </label>
        <label>
          Venue
          <select value={venueId} onChange={(e) => setVenueId(e.target.value)} required>
            <option value="" disabled>
              Select a venue
            </option>
            {venues.map((v) => (
              <option key={v.id} value={v.id ?? undefined}>
                {v.name}
              </option>
            ))}
          </select>
        </label>
        <fieldset>
          <legend>Performers</legend>
          {performers.length === 0 && <p className="muted">No performers yet.</p>}
          {performers.map((p) => (
            <label key={p.id} className="checkbox-label">
              <input
                type="checkbox"
                checked={p.id !== null && performerIds.has(p.id)}
                onChange={() => p.id !== null && togglePerformer(p.id)}
              />
              {p.name}
            </label>
          ))}
        </fieldset>
        <button type="submit" disabled={submitting}>
          {submitting ? "Saving..." : "Save"}
        </button>
      </form>
    </div>
  );
}
