# LimitGuard Admin UI

This Angular application is the console for LimitGuard Admin. Tenant administrators use it to
manage tenants and their funding source products. The Gradle build compiles it into the Admin
Spring Boot JAR, so there is no separate server to run.

From the repository root, start PostgreSQL and Keycloak, then the Admin backend:

```bash
docker compose --profile identity up -d --wait postgres keycloak
cd services/limitguard-admin
./gradlew bootRun
```

Open `http://localhost:8080` and sign in as `platform-admin` from the local Keycloak realm in
`infra/keycloak`. `./gradlew test` in `services/limitguard-admin` runs these UI tests together
with the backend tests; `npm test` here runs only the UI tests.
