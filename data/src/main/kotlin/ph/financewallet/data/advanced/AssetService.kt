package ph.financewallet.data.advanced

import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.entity.AssetEntity
import ph.financewallet.data.db.entity.AssetValuationEntity
import ph.financewallet.data.db.validatedWrite
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.ValidationScope
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.LocalDate

enum class AssetKind { INVESTMENT, PROPERTY, VEHICLE, RECEIVABLE, OTHER }

data class AssetSummary(
    val id: String,
    val name: String,
    val kind: AssetKind,
    val includeInNetWorth: Boolean,
    val archived: Boolean,
    /** Latest user-entered value, never fetched from a provider (AST-01). */
    val latestValue: Money?,
    val latestAsOf: LocalDate?,
    val version: Long,
)

/**
 * User-entered assets and their valuation history (UC-25, AST-01). Values are what the user typed; the app does not
 * claim to know market values. Valuations are soft-deleted so history is never silently lost.
 */
class AssetService(
    private val db: FinanceDatabase,
    private val ctx: WriteContext,
) {
    private val dao get() = db.advancedDao()

    suspend fun create(
        name: String,
        kind: AssetKind,
        currency: CurrencyCode,
        includeInNetWorth: Boolean = true,
        notes: String? = null,
    ): DomainResult<String> =
        db.validatedWrite {
            checkFields(name, notes)
            val id = ctx.ids.newId()
            val now = ctx.nowMillis()
            dao.insertAsset(
                AssetEntity(
                    id,
                    name.trim(),
                    kind.name,
                    currency.code,
                    includeInNetWorth,
                    notes?.ifBlank {
                        null
                    },
                    null,
                    now,
                    1,
                    now,
                    ctx.deviceId,
                    ctx.nextHlc(),
                ),
            )
            id
        }

    suspend fun edit(
        assetId: String,
        expectedVersion: Long,
        name: String,
        includeInNetWorth: Boolean,
        notes: String?,
    ): DomainResult<Unit> =
        db.validatedWrite {
            val a = current(assetId, expectedVersion)
            checkFields(name, notes)
            dao.updateAsset(touch(a).copy(name = name.trim(), includeInNetWorth = includeInNetWorth, notes = notes?.ifBlank { null }))
        }

    suspend fun archive(
        assetId: String,
        expectedVersion: Long,
    ): DomainResult<Unit> =
        db.validatedWrite {
            val a = current(assetId, expectedVersion)
            ensure(a.archivedAt == null, "assetId", "ALREADY_ARCHIVED")
            dao.updateAsset(touch(a).copy(archivedAt = ctx.nowMillis(), includeInNetWorth = false))
        }

    /** Records the asset's value on [asOf] (not in the future). */
    suspend fun addValuation(
        assetId: String,
        asOf: LocalDate,
        value: Money,
        note: String? = null,
    ): DomainResult<String> =
        db.validatedWrite {
            val a = dao.asset(assetId) ?: notFound("asset", assetId)
            ensure(a.archivedAt == null, "assetId", "ASSET_ARCHIVED")
            ensure(value.currency.code == a.currency, "value", "CURRENCY_MISMATCH")
            ensure(!value.isNegative, "value", "AMOUNT_NEGATIVE")
            ensure(!asOf.isAfter(ctx.today()), "asOf", "DATE_IN_FUTURE")
            ensure((note?.length ?: 0) <= MAX_NOTE, "note", "TOO_LONG")
            val id = ctx.ids.newId()
            val now = ctx.nowMillis()
            dao.insertValuation(
                AssetValuationEntity(
                    id,
                    assetId,
                    asOf.toString(),
                    value.minor,
                    note?.ifBlank {
                        null
                    },
                    null,
                    1,
                    now,
                    ctx.deviceId,
                    ctx.nextHlc(),
                ),
            )
            id
        }

    suspend fun deleteValuation(valuationId: String): DomainResult<Unit> =
        db.validatedWrite {
            val v = dao.valuation(valuationId) ?: notFound("valuation", valuationId)
            ensure(v.deletedAt == null, "valuationId", "ALREADY_DELETED")
            dao.updateValuation(
                v.copy(deletedAt = ctx.nowMillis(), version = v.version + 1, updatedAt = ctx.nowMillis(), hlc = ctx.nextHlc()),
            )
        }

    suspend fun assets(): List<AssetSummary> =
        dao.assets().map { a ->
            val latest = dao.valuations(a.id).firstOrNull()
            AssetSummary(
                id = a.id,
                name = a.name,
                kind = AssetKind.valueOf(a.kind),
                includeInNetWorth = a.includeInNetWorth,
                archived = a.archivedAt != null,
                latestValue = latest?.let { Money(it.valueMinor, CurrencyCode.of(a.currency)) },
                latestAsOf = latest?.let { LocalDate.parse(it.asOfDate) },
                version = a.version,
            )
        }

    suspend fun valuations(assetId: String) = dao.valuations(assetId)

    private suspend fun ValidationScope.current(
        id: String,
        expectedVersion: Long,
    ): AssetEntity {
        val a = dao.asset(id) ?: notFound("asset", id)
        ensure(a.version == expectedVersion, "assetId", "STALE_VERSION")
        return a
    }

    private fun ValidationScope.checkFields(
        name: String,
        notes: String?,
    ) {
        ensure(name.trim().length in 1..MAX_NAME, "name", "NAME_LENGTH")
        ensure((notes?.length ?: 0) <= MAX_NOTES, "notes", "TOO_LONG")
    }

    private fun touch(a: AssetEntity) =
        a.copy(version = a.version + 1, updatedAt = ctx.nowMillis(), updatedByDevice = ctx.deviceId, hlc = ctx.nextHlc())

    private companion object {
        const val MAX_NAME = 80
        const val MAX_NOTES = 2000
        const val MAX_NOTE = 200
    }
}
