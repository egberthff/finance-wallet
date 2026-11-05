# ADR-0005: Optional backend for backup, sync and AI only: Ktor + PostgreSQL

- **Status:** **Accepted** (2026-11-05). Approved by owner with Phase 1
- **Requirements:** BAK-02, SYNC-01, AI-01, SEC-02

## Context
With no direct integrations, the server is needed only for optional cloud auth, E2E-encrypted backup, the multi-device sync relay, and the AI proxy. None of these are in the MVP.

## Decision
In Phase 7, build a Kotlin Ktor service with PostgreSQL (users, devices, sync sequence metadata, audit) and S3-compatible storage for encrypted blobs. It shares kotlinx.serialization contracts with the app. The server never holds plaintext financial data. Hosting and region are decided in Phase 7.

## Alternatives considered
Supabase (acceptable managed alternative). Node/Fastify (rev. 1; no longer shares a language with the app). Firebase (LWW document sync conflicts with SYNC-02).

## Consequences
- **Pro:** One language and one build.
- **Con:** Operations burden only once cloud features ship.
