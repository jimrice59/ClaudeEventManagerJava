import { Route, Routes } from "react-router-dom";
import { NavBar } from "./components/NavBar";
import { AdminRoute, RequireAuth } from "./components/ProtectedRoute";
import { LoginPage } from "./pages/LoginPage";
import { RegisterPage } from "./pages/RegisterPage";
import { EventsPage } from "./pages/EventsPage";
import { EventDetailPage } from "./pages/EventDetailPage";
import { EventFormPage } from "./pages/EventFormPage";
import { PerformersPage } from "./pages/PerformersPage";
import { PerformerDetailPage } from "./pages/PerformerDetailPage";
import { PerformerFormPage } from "./pages/PerformerFormPage";
import { VenuesPage } from "./pages/VenuesPage";
import { VenueFormPage } from "./pages/VenueFormPage";
import { MyTicketsPage } from "./pages/MyTicketsPage";

function App() {
  return (
    <>
      <NavBar />
      <main className="app-main">
        <Routes>
          <Route path="/" element={<EventsPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />

          <Route path="/events" element={<EventsPage />} />
          <Route path="/events/:id" element={<EventDetailPage />} />
          <Route
            path="/events/new"
            element={
              <AdminRoute>
                <EventFormPage />
              </AdminRoute>
            }
          />
          <Route
            path="/events/:id/edit"
            element={
              <AdminRoute>
                <EventFormPage />
              </AdminRoute>
            }
          />

          <Route path="/performers" element={<PerformersPage />} />
          <Route path="/performers/:id" element={<PerformerDetailPage />} />
          <Route
            path="/performers/new"
            element={
              <AdminRoute>
                <PerformerFormPage />
              </AdminRoute>
            }
          />
          <Route
            path="/performers/:id/edit"
            element={
              <AdminRoute>
                <PerformerFormPage />
              </AdminRoute>
            }
          />

          <Route
            path="/my-tickets"
            element={
              <RequireAuth>
                <MyTicketsPage />
              </RequireAuth>
            }
          />

          <Route path="/venues" element={<VenuesPage />} />
          <Route
            path="/venues/new"
            element={
              <AdminRoute>
                <VenueFormPage />
              </AdminRoute>
            }
          />
          <Route
            path="/venues/:id/edit"
            element={
              <AdminRoute>
                <VenueFormPage />
              </AdminRoute>
            }
          />
        </Routes>
      </main>
    </>
  );
}

export default App;
