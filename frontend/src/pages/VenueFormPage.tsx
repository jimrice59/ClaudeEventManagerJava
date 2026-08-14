import { useEffect, useState, type FormEvent } from "react";
import { useNavigate, useParams } from "react-router-dom";
import * as venuesApi from "../api/venues";
import { extractErrorMessage } from "../api/client";
import { ErrorBanner } from "../components/ErrorBanner";

export function VenueFormPage() {
  const { id } = useParams<{ id: string }>();
  const isEdit = id !== undefined;
  const navigate = useNavigate();

  const [name, setName] = useState("");
  const [address, setAddress] = useState("");
  const [city, setCity] = useState("");
  const [state, setState] = useState("");
  const [zipCode, setZipCode] = useState("");
  const [capacity, setCapacity] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(isEdit);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!isEdit) return;
    venuesApi
      .getVenue(Number(id))
      .then((v) => {
        setName(v.name);
        setAddress(v.address);
        setCity(v.city);
        setState(v.state);
        setZipCode(v.zipCode ?? "");
        setCapacity(v.capacity !== undefined ? String(v.capacity) : "");
      })
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setLoading(false));
  }, [id, isEdit]);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const dto = {
        id: isEdit ? Number(id) : null,
        name,
        address,
        city,
        state,
        zipCode: zipCode || undefined,
        capacity: capacity ? Number(capacity) : undefined,
      };
      const saved = isEdit ? await venuesApi.updateVenue(Number(id), dto) : await venuesApi.createVenue(dto);
      navigate(`/venues`, { replace: true, state: { savedId: saved.id } });
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) return <p>Loading...</p>;

  return (
    <div className="card form-card">
      <h1>{isEdit ? "Edit venue" : "New venue"}</h1>
      <ErrorBanner message={error} />
      <form onSubmit={handleSubmit}>
        <label>
          Name
          <input value={name} onChange={(e) => setName(e.target.value)} required />
        </label>
        <label>
          Address
          <input value={address} onChange={(e) => setAddress(e.target.value)} required />
        </label>
        <label>
          City
          <input value={city} onChange={(e) => setCity(e.target.value)} required />
        </label>
        <label>
          State
          <input value={state} onChange={(e) => setState(e.target.value)} required />
        </label>
        <label>
          Zip code
          <input value={zipCode} onChange={(e) => setZipCode(e.target.value)} />
        </label>
        <label>
          Capacity
          <input type="number" min={1} value={capacity} onChange={(e) => setCapacity(e.target.value)} />
        </label>
        <button type="submit" disabled={submitting}>
          {submitting ? "Saving..." : "Save"}
        </button>
      </form>
    </div>
  );
}
