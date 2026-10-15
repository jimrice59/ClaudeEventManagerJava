---
paths:
  - "k8s/**"
---

# Kubernetes

`k8s/deployment.yml` contains four resources applied with `kubectl apply -f k8s/deployment.yml`:

| Resource | Name | Notes |
|---|---|---|
| `Secret` | `event-manager-secrets` | `DB_PASSWORD`, `REDIS_PASSWORD`, `JWT_SECRET` — replace before applying |
| `ConfigMap` | `event-manager-config` | Non-sensitive config; host names are in-cluster service names |
| `Deployment` | `event-manager` | 3 replicas; rolling update with `maxUnavailable: 0`; container exposes both `http` (8080) and `grpc` (9091) ports |
| `Service` | `event-manager-service` | `LoadBalancer` with two named ports: `http` (80 → pod 8080, REST API + web UI) and `grpc` (9091 → pod 9091, this app's own gRPC API server — see grpc-api-server.md). Named ports are required once a `Service` exposes more than one. |

Resource sizing: 250m CPU / 512Mi memory requests; 1 CPU / 1Gi limits. Rolling update strategy keeps all 3 replicas live during a deploy (`maxUnavailable: 0`, `maxSurge: 1`).

Probes use the dedicated Spring Boot Kubernetes endpoints, both over HTTP on port 8080 (there is no
separate gRPC health check — a pod failing its HTTP readiness probe is removed from load balancing
for both the `http` and `grpc` Service ports together, since both come from the same container):
- **Readiness**: `/actuator/health/readiness` — checks datastores; pod removed from load balancing if unhealthy
- **Liveness**: `/actuator/health/liveness` — checks JVM only; does not restart pods on DB outage

The gRPC server binds `grpc-api.port` (default `9091`, see grpc-api-server.md); it is not
independently configured here — the `ConfigMap`/`Secret` only override the datastore/JWT settings
that differ per environment, and `grpc-api.enabled`/`grpc-api.port` are left at their
`application.yml` defaults. Set `GRPC_API_ENABLED`/`GRPC_API_PORT` in the `ConfigMap` if either
needs to be overridden per environment.

Update the `image:` field to your registry path before applying.
