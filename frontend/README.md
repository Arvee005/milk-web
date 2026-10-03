# Milk Web Owner Billing

The React application currently provides an owner billing workspace for monthly period reconciliation, draft bill generation, bill review, and issuing.

## Development

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

Vite proxies `/api` requests to the backend at `http://localhost:8080`. The frontend uses the existing JWT login and owner/customer APIs; no API client dependency is required.

Owner login tokens are held in `sessionStorage` for the current browser tab and removed when the owner signs out. The billing screen uses the `Asia/Kolkata` timezone for month defaults and displayed business dates. Amounts are shown without a currency symbol because currency confirmation remains pending.

## Verification

```sh
npm run build
npm run lint
```
