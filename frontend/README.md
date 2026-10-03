# Milk Web Owner Billing

The React application currently provides an owner billing workspace for monthly period reconciliation, draft bill generation, bill review, and issuing.

## Development

Use Node.js v22.6 or newer for the built-in TypeScript-stripping test runner.

Start the backend with its local development profile in one terminal:

```sh
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The `dev` profile uses an in-memory H2 database and a development-only JWT secret. Its data is temporary and is discarded when the backend stops. Do not use this profile for business data.

In a second terminal, start the frontend:

```sh
cd frontend
npm run dev
```

Vite proxies `/api` requests to the backend at `http://localhost:8080`. The owner portal posts a 10-digit phone number and password to `POST /api/auth/login`, then validates the returned JWT with `GET /api/auth/me` before rendering billing. Backend role restrictions remain authoritative; CUSTOMER credentials are not accepted into the owner workspace.

The JWT is held in `sessionStorage` for the current browser tab and removed when the owner signs out or the backend returns 401. A 403 is shown as an authorization message and does not silently discard the session. `sessionStorage` is accessible to same-origin JavaScript; production deployment still requires HTTPS and standard XSS defenses. The billing screen uses the `Asia/Kolkata` timezone for month defaults and displayed business dates. Amounts are shown without a currency symbol because currency confirmation remains pending.

## Verification

```sh
npm run build
npm run lint
npm run test
```

Authentication client tests use Node's built-in test runner and do not add a runtime or test dependency.
