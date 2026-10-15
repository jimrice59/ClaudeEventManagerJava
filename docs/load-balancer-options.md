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

## gRPC-specific load balancing

This application also exposes a gRPC server (port `9091`; see grpc-api-server.md and
docs/grpc-api-support.md) alongside the REST API, and gRPC breaks the assumptions all of the
general-purpose options above were designed around.

**Why plain load balancing doesn't work for gRPC:** gRPC runs over one long-lived HTTP/2
connection per client, and every RPC that client makes multiplexes over that single connection.
Anything that balances at the TCP/connection level — kube-proxy's `ClusterIP` (used by a plain
`Service`), a cloud `LoadBalancer` Service in passthrough mode, Docker's bridge networking — picks
one backend pod *once per connection*, not once per request. A client that opens one channel and
never reconnects sticks to that one pod for the life of the connection, so N replicas silently
behave like 1 from that client's perspective. REST/HTTP1.1 traffic doesn't expose this (one
connection per request, or short, frequently-recycled keep-alives), which is why it's easy to miss:
`k8s/deployment.yml`'s `event-manager-service` is a plain `LoadBalancer` Service today — it gives
the gRPC port real connectivity, but not real balancing across the 3 replicas, for exactly this
reason.

**Internal cluster traffic** (another in-cluster service calling this app's gRPC API):

- **Client-side load balancing against a headless Service** — a second `Service` with
  `clusterIP: None` selecting the same pods returns one DNS A record per pod instead of one VIP.
  Point the gRPC client at `dns:///event-manager-grpc-headless.<namespace>.svc.cluster.local:9091`
  and set the channel's LB policy to `round_robin`
  (`ManagedChannelBuilder.defaultLoadBalancingPolicy("round_robin")` in grpc-java) so it balances
  per-RPC across every resolved address. No new infrastructure, but every consumer has to opt in.
- **A service mesh sidecar** (Linkerd, or Istio/Envoy) — transparently load-balances gRPC per
  request for any traffic in the mesh, no client changes needed. Same verdict as Envoy above:
  worth it if you're already moving toward a mesh, overkill to adopt just to fix this one problem.

For this project, the headless-Service-plus-`round_robin` approach is the better fit — smallest
change, and this app isn't part of a larger mesh today.

**External traffic** (a client outside the cluster calling the gRPC API): the fix is an L7,
HTTP/2-aware proxy in front, not a raw L4 `LoadBalancer` Service — the connection-pinning problem
above applies identically at the edge.

- **Traefik Ingress Controller** — same proxy family already used locally via the docker-compose
  `traefik` profile (dev/prod continuity); supports gRPC backends via `scheme: h2c` on the
  `IngressRoute`.
- **Nginx Ingress Controller** — already this doc's pick for GKE above; supports gRPC via the
  `nginx.ingress.kubernetes.io/backend-protocol: "GRPC"` annotation.
- **Envoy-based** (Contour, Istio Gateway, Emissary) — native, first-class gRPC support with the
  least protocol-specific configuration, at the cost of new infrastructure for a project this size.

Either Ingress controller means replacing `event-manager-service`'s `type: LoadBalancer` with a
`ClusterIP` Service fronted by an Ingress/IngressRoute resource — not done in `k8s/deployment.yml`
yet; this section is the design discussion to work from when that change is made.

## Recommendations for this project

| Context | Recommendation | Reason |
|---|---|---|
| Local / docker-compose | Traefik | Add to `docker-compose.yml`, label the app container, auto-discovered |
| GKE | Nginx Ingress Controller | Standard, well-supported on GKE; one Ingress manifest routes to `event-manager-service` |
| High-traffic production | HAProxy or GCP load balancer | Raw throughput; GKE's `LoadBalancer` service type provisions a GCP load balancer automatically |
| gRPC, internal (cluster) | Headless Service + client-side `round_robin` | Smallest change; a service mesh is overkill for one service |
| gRPC, external | Traefik or Nginx Ingress Controller with gRPC/HTTP2 backend support | A raw `LoadBalancer` Service only balances per-connection, not per-RPC, for gRPC traffic |
