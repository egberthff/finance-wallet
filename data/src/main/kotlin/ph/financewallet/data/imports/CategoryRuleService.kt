package ph.financewallet.data.imports

import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.entity.CategoryRuleEntity
import ph.financewallet.data.db.validatedWrite
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.text.TextNormalizer

enum class MatchField { DESCRIPTION, MERCHANT, ANY_TEXT }

enum class MatchOp { EQUALS, CONTAINS, STARTS_WITH }

/** SUGGEST shows the category in the import preview; AUTO pre-selects it (both are visible and changeable). */
enum class RuleMode { SUGGEST, AUTO }

data class NewRule(
    val pattern: String,
    val categoryId: String,
    val field: MatchField = MatchField.ANY_TEXT,
    val op: MatchOp = MatchOp.CONTAINS,
    val mode: RuleMode = RuleMode.SUGGEST,
    val accountId: String? = null,
    /** Inclusive bounds on the absolute amount in minor units. */
    val minMinor: Long? = null,
    val maxMinor: Long? = null,
)

/**
 * Categorization rules (UC-15, CAT-03, docs/10-domain-model.md §10). Rules are matched on normalized text in priority
 * order. They only suggest categories at import time and never change existing transactions.
 */
class CategoryRuleService(
    private val db: FinanceDatabase,
    private val ctx: WriteContext,
) {
    private val dao get() = db.importDao()

    suspend fun rules(): List<CategoryRuleEntity> = dao.rules()

    suspend fun create(rule: NewRule): DomainResult<String> =
        db.validatedWrite {
            val pattern = TextNormalizer.normalize(rule.pattern)
            ensure(pattern.length in 1..MAX_PATTERN, "pattern", "PATTERN_LENGTH")
            val category = db.ledgerDao().ledgerAccount(rule.categoryId) ?: notFound("category", rule.categoryId)
            ensure(db.ledgerDao().category(rule.categoryId) != null, "categoryId", "NOT_A_CATEGORY")
            ensure(category.archivedAt == null, "categoryId", "CATEGORY_ARCHIVED")
            rule.accountId?.let { ensure(db.ledgerDao().financialAccount(it) != null, "accountId", "ACCOUNT_NOT_REAL") }
            ensure(rule.minMinor == null || rule.minMinor >= 0, "minMinor", "AMOUNT_NEGATIVE")
            ensure(rule.minMinor == null || rule.maxMinor == null || rule.minMinor <= rule.maxMinor, "maxMinor", "RANGE_INVALID")
            val id = ctx.ids.newId()
            dao.insertRule(
                CategoryRuleEntity(
                    id = id,
                    priority = dao.maxPriority() + 1,
                    matchField = rule.field.name,
                    matchOp = rule.op.name,
                    patternNorm = pattern,
                    minMinor = rule.minMinor,
                    maxMinor = rule.maxMinor,
                    accountId = rule.accountId,
                    categoryId = rule.categoryId,
                    mode = rule.mode.name,
                    enabled = true,
                    version = 1,
                    updatedAt = ctx.nowMillis(),
                    updatedByDevice = ctx.deviceId,
                    hlc = ctx.nextHlc(),
                ),
            )
            id
        }

    suspend fun setEnabled(
        ruleId: String,
        enabled: Boolean,
    ): DomainResult<Unit> =
        db.validatedWrite {
            val r = dao.rule(ruleId) ?: notFound("rule", ruleId)
            dao.updateRule(r.copy(enabled = enabled, version = r.version + 1, updatedAt = ctx.nowMillis(), hlc = ctx.nextHlc()))
        }

    suspend fun delete(ruleId: String): DomainResult<Unit> =
        db.validatedWrite {
            dao.rule(ruleId) ?: notFound("rule", ruleId)
            dao.deleteRule(ruleId)
        }

    companion object {
        private const val MAX_PATTERN = 120

        /**
         * The first enabled rule (lowest priority number) matching the row whose category is of [categoryClass] (so an
         * income rule never categorizes an outflow). [descriptionNorm] and [merchantNorm] are already normalized.
         */
        fun match(
            rules: List<CategoryRuleEntity>,
            categoryClassOf: (String) -> String?,
            categoryClass: String,
            accountId: String,
            descriptionNorm: String,
            merchantNorm: String?,
            absAmountMinor: Long,
        ): CategoryRuleEntity? =
            rules.filter { it.enabled }.sortedBy { it.priority }.firstOrNull { r ->
                val texts =
                    when (MatchField.valueOf(r.matchField)) {
                        MatchField.DESCRIPTION -> listOf(descriptionNorm)
                        MatchField.MERCHANT -> listOfNotNull(merchantNorm)
                        MatchField.ANY_TEXT -> listOfNotNull(descriptionNorm, merchantNorm)
                    }
                val textMatches =
                    texts.any { t ->
                        when (MatchOp.valueOf(r.matchOp)) {
                            MatchOp.EQUALS -> t == r.patternNorm
                            MatchOp.CONTAINS -> t.contains(r.patternNorm)
                            MatchOp.STARTS_WITH -> t.startsWith(r.patternNorm)
                        }
                    }
                textMatches &&
                    (r.accountId == null || r.accountId == accountId) &&
                    (r.minMinor == null || absAmountMinor >= r.minMinor) &&
                    (r.maxMinor == null || absAmountMinor <= r.maxMinor) &&
                    categoryClassOf(r.categoryId) == categoryClass
            }
    }
}
