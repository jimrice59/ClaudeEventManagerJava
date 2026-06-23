# Load Balancer Options for Spring Boot

## General-purpose reverse proxy / load balancer

- **Nginx** — most widely used. Handles HTTP/HTTPS, round-robin/least-connections/IP-hash strategies, SSL termination, and static file serving. Configuration is declarative and well-documented. Good default choice for a VPS or bare-metal deployment.

- **HAProxy** — purpose-built for load balancing, lower overhead than Nginx for pure TCP/HTTP proxying. Better health-check granularity and real-time stats via a built-in dashboard. Preferred in high-throughput or latency-sensitive environments.

## Cloud-native / container-aware

- **Traefik** — auto-discovers backends from Docker labels or Kubernetes annotations. No manual upstream config when containers start/stop. Built-in Let's Encrypt, dashboard, and middleware (rate limiting, auth). Best fit for this project's docker-compose or GKE setup.

- **Envoy** — L7 proxy used as the data plane in service meshes (Istio, Consul Connect). Powerful but complex to configure standalone. Worth it if you're moving toward a service mesh; overkill otherwise.

## Kubernetes Ingress controllers

Relevant since this project already has `k8s/deployment.yml`:

- **Nginx Ingress Controller** — most common; wraps Nginx behind a Kubernetes Ingress resource. One annotation on the Ingress routes external traffic to `event-manager-service`.
- **Traefik Ingress Controller** — same idea, auto-discovers from Ingress/IngressRoute resources, built-in dashboard.
- **Emissary (formerly Ambassador)** — Envoy-based, API-gateway focused, good for JWT validation at the edge (relevant here since the app uses JWT).

## API gateway with load balancing

- **Kong** — Nginx-based, adds rate limiting, auth plugins, request transformation, analytics. Useful if you want to offload JWT validation or rate-limit `/api/auth/**` at the gateway layer rather than in the app.

## Recommendations for this project

| Context | Recommendation | Reason |
|---|---|---|
| Local / docker-compose | Traefik | Add to `docker-compose.yml`, label the app container, auto-discovered |
| GKE | Nginx Ingress Controller | Standard, well-supported on GKE; one Ingress manifest routes to `event-manager-service` |
| High-traffic production | HAProxy or GCP load balancer | Raw throughput; GKE's `LoadBalancer` service type provisions a GCP load balancer automatically |
