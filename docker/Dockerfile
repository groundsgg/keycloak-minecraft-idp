ARG KEYCLOAK_VERSION=26.5.4

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}

# Copy the provider JAR (if it exists at build time)
# For development, we mount it via docker-compose volumes instead
COPY --chown=keycloak:keycloak providers/ /opt/keycloak/providers/

# Build optimized Keycloak
RUN /opt/keycloak/bin/kc.sh build

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]

