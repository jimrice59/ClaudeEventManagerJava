#!/usr/bin/env bash
# Generates a self-signed TLS cert for LOCAL DEV ONLY, covering both:
#   - server.ssl.*     (Tomcat/HTTPS) -> needs a PKCS12 keystore
#   - grpc-api.tls.*   (gRPC server)  -> needs a PEM cert chain + PEM private key
# Output lands in this directory (certs/), which is gitignored except for this script and README.
set -euo pipefail
cd "$(dirname "$0")"

DAYS=825
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-changeit}"

openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout localhost.key -out localhost.crt -days "$DAYS" \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

openssl pkcs12 -export \
  -in localhost.crt -inkey localhost.key \
  -out localhost.p12 -name event-manager \
  -passout pass:"$KEYSTORE_PASSWORD"

echo
echo "Generated certs/localhost.{key,crt,p12} (keystore password: $KEYSTORE_PASSWORD)"
echo
echo "HTTPS (Tomcat):"
echo "  SERVER_SSL_ENABLED=true"
echo "  SERVER_SSL_KEY_STORE=file:$(pwd)/localhost.p12"
echo "  SERVER_SSL_KEY_STORE_PASSWORD=$KEYSTORE_PASSWORD"
echo "  SERVER_SSL_KEY_ALIAS=event-manager"
echo
echo "gRPC:"
echo "  GRPC_API_TLS_ENABLED=true"
echo "  GRPC_API_TLS_CERT_CHAIN_FILE=$(pwd)/localhost.crt"
echo "  GRPC_API_TLS_PRIVATE_KEY_FILE=$(pwd)/localhost.key"
