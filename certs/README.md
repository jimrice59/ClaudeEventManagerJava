# Dev TLS certs

Generates a self-signed cert for **local development only** — covers both the REST API/web UI
(HTTPS, via Tomcat) and this application's own gRPC server, which need the same key material in
different formats.

```bash
./generate-dev-certs.sh
```

Produces (gitignored — regenerate instead of committing):
- `localhost.p12` — PKCS12 keystore for `server.ssl.*` (Tomcat/HTTPS)
- `localhost.crt` / `localhost.key` — PEM cert chain / private key for `grpc-api.tls.*` (grpc-java
  wants PEM, not a PKCS12/JKS keystore, hence the two different outputs from one cert)

The script prints the exact env vars to export. Example, running both locally:

```bash
export SERVER_SSL_ENABLED=true
export SERVER_SSL_KEY_STORE=file:$(pwd)/certs/localhost.p12
export SERVER_SSL_KEY_STORE_PASSWORD=changeit
export SERVER_SSL_KEY_ALIAS=event-manager

export GRPC_API_TLS_ENABLED=true
export GRPC_API_TLS_CERT_CHAIN_FILE=$(pwd)/certs/localhost.crt
export GRPC_API_TLS_PRIVATE_KEY_FILE=$(pwd)/certs/localhost.key

JAVA_HOME=... $MVN spring-boot:run
```

Browsers/`curl`/`grpcurl` will flag the cert as untrusted (self-signed, no real CA) — expected for
local testing; use `curl -k` or `grpcurl -insecure`. Never reuse this cert outside local dev; a
real deployment should terminate TLS at an Ingress/load balancer with a CA-issued cert (see
`docs/load-balancer-options.md`) or supply its own CA-issued keystore/PEM pair here.
