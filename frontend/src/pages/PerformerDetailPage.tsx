import { useEffect, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import * as performersApi from "../api/performers";
import { extractErrorMessage } from "../api/client";
import { ErrorBanner } from "../components/ErrorBanner";
import { useAuth } from "../context/AuthContext";
import type { PerformerDto } from "../types";

export function PerformerDetailPage() {
  const { id } = useParams<{ id: string }>();
  const performerId = Number(id);
  const navigate = useNavigate();
  const { isAdmin } = useAuth();

  const [performer, setPerformer] = useState<PerformerDto | null>(null);
  const [newVideoUrl, setNewVideoUrl] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionPending, setActionPending] = useState(false);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      setPerformer(await performersApi.getPerformer(performerId));
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [performerId]);

  async function handleAddVideo(event: FormEvent) {
    event.preventDefault();
    if (!newVideoUrl) return;
    setActionPending(true);
    setError(null);
    try {
      setPerformer(await performersApi.addVideo(performerId, newVideoUrl));
      setNewVideoUrl("");
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setActionPending(false);
    }
  }

  async function handleRemoveVideo(url: string) {
    setActionPending(true);
    setError(null);
    try {
      setPerformer(await performersApi.deleteVideo(performerId, url));
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setActionPending(false);
    }
  }

  async function handleDelete() {
    if (!confirm("Delete this performer? This cannot be undone.")) return;
    setActionPending(true);
    setError(null);
    try {
      await performersApi.deletePerformer(performerId);
      navigate("/performers", { replace: true });
    } catch (err) {
      setError(extractErrorMessage(err));
      setActionPending(false);
    }
  }

  if (loading) return <p>Loading...</p>;
  if (!performer) return <ErrorBanner message={error ?? "Performer not found."} />;

  return (
    <div className="card detail-card">
      <div className="page-header">
        <h1>{performer.name}</h1>
        {isAdmin && (
          <div className="button-row">
            <Link to={`/performers/${performer.id}/edit`} className="button">
              Edit
            </Link>
            <button onClick={handleDelete} disabled={actionPending} className="button danger">
              Delete
            </button>
          </div>
        )}
      </div>

      <ErrorBanner message={error} />

      {performer.genre && <p className="muted">{performer.genre}</p>}
      {performer.bio && <p>{performer.bio}</p>}

      <h2>Videos</h2>
      {(!performer.videoUrls || performer.videoUrls.length === 0) && <p className="muted">No videos yet.</p>}
      <ul className="video-list">
        {performer.videoUrls?.map((url) => (
          <li key={url}>
            <a href={url} target="_blank" rel="noreferrer">
              {url}
            </a>
            {isAdmin && (
              <button onClick={() => handleRemoveVideo(url)} disabled={actionPending} className="button danger small">
                Remove
              </button>
            )}
          </li>
        ))}
      </ul>

      {isAdmin && (
        <form className="ticket-form" onSubmit={handleAddVideo}>
          <label>
            Video URL
            <input
              type="url"
              value={newVideoUrl}
              onChange={(e) => setNewVideoUrl(e.target.value)}
              placeholder="https://example.com/video.mp4"
            />
          </label>
          <button type="submit" disabled={actionPending}>
            Add video
          </button>
        </form>
      )}
    </div>
  );
}
