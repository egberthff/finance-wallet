# Milestone Plan: Phase 2, Architecture

| Status | **Complete. Approved by owner** (2026-11-05) |
|---|---|

## 1. Objectives
Turn the approved direction (ADR-0001..0009) into a design detailed enough that Phase 3–4 implementation needs no
architectural invention. That means exact data structures, exact financial rules, exact file formats, and exact security controls.

## 2. Scope
In scope: domain model and financial rules; database schema (ERD + DDL + indexes + migration policy); use-case catalogue;
import pipeline spec; backup file format; security architecture and threat model; device-sync protocol (draft, Phase 7);
backend API (draft, Phase 7); coding standards.
Out of scope: any production code, toolchain installation, UI visual design (Phase 3).

## 3. Technical approach
- Each financial rule is written as a precise statement with worked examples. Those examples become Phase 4 test cases verbatim.
- The schema is designed for integrity first, with constraints at the DB level where SQLite can express them, and in the domain layer otherwise.
- Every spec references PRD requirement IDs for traceability.

## 4. Dependencies
Phase 1 approval (received). No tooling dependency.

## 5. Risks
| Risk | Mitigation |
|---|---|
| Design can't be validated without running code | Worked examples double as tests; any assumption that needs benchmarks (query performance, SQLCipher cost, library size) is listed as a Phase 3 spike, not asserted |
| Over-design of Phase 7 features | Sync and API are marked *Draft*. Only the fields they need from day one (ids, versions, HLC) go into the schema now |

## 6. Acceptance criteria (exit gate)
1. Every P0 requirement maps to at least one entity, rule, or use case (traceability table in the use-case catalogue).
2. Every transaction type has a defined entry template, validation rules, and report classification.
3. Every AC-CORE criterion has a worked example in the domain spec.
4. The DDL is internally consistent: every FK target exists and every index has a stated query purpose.
5. Owner approves the architecture.

## 7. Deliverables
| Document | File |
|---|---|
| Domain model & financial rules | [10-domain-model.md](../10-domain-model.md) |
| Database schema, ERD, migrations | [11-database-schema.md](../11-database-schema.md) |
| Use-case catalogue + traceability | [12-use-cases.md](../12-use-cases.md) |
| Import pipeline spec | [13-import-pipeline.md](../13-import-pipeline.md) |
| Backup file format | [14-backup-format.md](../14-backup-format.md) |
| Device sync protocol (draft) | [15-sync-protocol.md](../15-sync-protocol.md) |
| Security architecture & threat model | [16-security-architecture.md](../16-security-architecture.md) |
| Backend API (draft) | [17-api-spec.md](../17-api-spec.md) |
| Coding standards | [18-coding-standards.md](../18-coding-standards.md) |
| New ADRs | ADR-0010 (no network permission in MVP), ADR-0011 (backup format and KDF) |

## 8. Exit-criteria self-check
| # | Criterion | Result |
|---|---|---|
| 1 | Every P0 requirement maps to an entity, rule, or use case | Met: 12-use-cases.md §2 |
| 2 | Every transaction type has a template, validation, and classification | Met: 10-domain-model.md §3.3 (11 types) |
| 3 | Every AC-CORE has a worked example | AC-CORE-1..8 → EX-5, EX-9, EX-15, EX-1, EX-12, EX-13, EX-7, EX-10. AC-CORE-9 (crash atomicity) is an infrastructure test, specified in 18-coding-standards.md §7 and schema §6 rather than as an EX |
| 4 | DDL internally consistent | Met: parsed in SQLite 3.45.3; all FK targets exist; one defect found and fixed (misplaced table CHECK in `import_batch`) |
| 5 | Owner approval | Met (2026-11-05) |
