import { useEffect, useState, type FormEvent } from "react";
import { useNavigate, useParams } from "react-router-dom";
import * as performersApi from "../api/performers";
import { extractErrorMessage } from "../api/client";
import { ErrorBanner } from "../components/ErrorBanner";

export function PerformerFormPage() {
  const { id } = useParams<{ id: string }>();
  const isEdit = id !== undefined;
  const navigate = useNavigate();

  const [name, setName] = useState("");
  const [genre, setGenre] = useState("");
  const [bio, setBio] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(isEdit);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!isEdit) return;
    performersApi
      .getPerformer(Number(id))
      .then((p) => {
        setName(p.name);
        setGenre(p.genre ?? "");
        setBio(p.bio ?? "");
      })
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setLoading(false));
  }, [id, isEdit]);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const saved = isEdit
        ? await performersApi.updatePerformer(Number(id), { id: Number(id), name, genre, bio })
        : await performersApi.createPerformer({ id: null, name, genre, bio });
      navigate(`/performers/${saved.id}`, { replace: true });
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) return <p>Loading...</p>;

  return (
    <div className="card form-card">
      <h1>{isEdit ? "Edit performer" : "New performer"}</h1>
      <ErrorBanner message={error} />
      <form onSubmit={handleSubmit}>
        <label>
          Name
          <input value={name} onChange={(e) => setName(e.target.value)} required />
        </label>
        <label>
          Genre
          <input value={genre} onChange={(e) => setGenre(e.target.value)} />
        </label>
        <label>
          Bio
          <textarea value={bio} onChange={(e) => setBio(e.target.value)} rows={4} />
        </label>
        <button type="submit" disabled={submitting}>
          {submitting ? "Saving..." : "Save"}
        </button>
      </form>
      {isEdit && <p className="muted">Videos are managed from the performer detail page.</p>}
    </div>
  );
}
