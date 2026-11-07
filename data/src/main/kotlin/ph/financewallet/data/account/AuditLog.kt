package ph.financewallet.data.account

import ph.financewallet.data.db.entity.AuditEventEntity
import ph.financewallet.data.ledger.RevisionJson
import ph.financewallet.data.ledger.WriteContext

/**
 * Writes one `audit_event` (CLAUDE.md §16 audit logging) with [detail] as a flat JSON object, keys sorted so the
 * output is deterministic. Call it inside the same write transaction as the change it records.
 */
internal suspend fun AccountDao.audit(
    ctx: WriteContext,
    kind: String,
    entityType: String,
    entityId: String,
    detail: Map<String, String>,
) {
    val json =
        detail.toSortedMap().entries.joinToString(",", "{", "}") { (k, v) ->
            "\"${RevisionJson.escape(k)}\":\"${RevisionJson.escape(v)}\""
        }
    insertAudit(AuditEventEntity(ctx.ids.newId(), kind, entityType, entityId, json, ctx.deviceId, ctx.nowMillis()))
}
