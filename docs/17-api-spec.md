# Backend API (DRAFT, for Phase 7)

| Status | **Draft.** Built only if the owner approves cloud features (Q-01). The MVP has no backend and no network permission (ADR-0010) |
|---|---|
| Decision | ADR-0005 (Ktor + PostgreSQL + S3-compatible storage) |

The server never receives plaintext financial data. All payloads under `/backups` and `/sync` are client-encrypted.

## Conventions
JSON over HTTPS (TLS 1.2+), base path `/v1`, bearer access tokens (short-lived, 15 min) plus rotating refresh tokens bound to a device.
Errors use `application/problem+json` (`type`, `title`, `status`, `code`).
Rate limits per account and IP: auth 10/min, writes 120/min. Exceeding them returns 429 with `Retry-After`.
Every write accepts `Idempotency-Key`.

## Endpoints

| Method & path | Purpose | Notes |
|---|---|---|
| `POST /v1/auth/start` | Begin sign-in (email one-time code; method to finalize in Phase 7) | No passwords stored server-side if OTC/passkey is chosen |
| `POST /v1/auth/verify` | Exchange code → tokens + device registration | Registers the device public key (X25519) |
| `POST /v1/auth/refresh` | Rotate refresh token | Reuse detection revokes the token family |
| `GET /v1/devices` / `DELETE /v1/devices/{id}` | List / revoke devices | Revocation triggers K_data rotation (client-driven) |
| `POST /v1/devices/{id}/keys` | Store a K_data wrapped for a newly authorized device | Ciphertext only |
| `PUT /v1/backups/{backupId}` | Upload encrypted `.fwbak` (resumable, chunked) | Size limit per plan; SHA-256 echoed |
| `GET /v1/backups` / `GET /v1/backups/{id}` / `DELETE /v1/backups/{id}` | List / download / delete | Metadata: size, createdAt, appVersion only |
| `POST /v1/sync/changesets` | Push encrypted change sets | Idempotent by changeSetId; returns assigned seq |
| `GET /v1/sync/changesets?since={seq}&limit=` | Pull | Paginated |
| `POST /v1/ai/query` | Optional AI proxy (Phase 7, opt-in) | Receives only the minimized aggregate payload (PRD AI-02); provider key server-side |
| `DELETE /v1/account` | Delete account and all server data (PRIV-01) | Immediate deletion of backups and change sets; audit retained without content |

## Data stored server-side
`user(id, email_hash?, created_at)`, `device(id, user_id, public_key, name, created_at, revoked_at)`,
`refresh_token(...)`, `backup(id, user_id, size, sha256, created_at, object_key)`, `changeset(user_id, seq, device_id, id, size, object_key|bytes)`,
`audit(user_id, kind, at)`. **No financial fields.**
