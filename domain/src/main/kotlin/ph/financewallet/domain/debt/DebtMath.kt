package ph.financewallet.domain.debt

import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.validating
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.recurring.Recurrence
import ph.financewallet.domain.recurring.RecurrenceCalculator
import java.time.LocalDate

/** One installment of a debt schedule. */
public data class ScheduleItem(
    public val seq: Int,
    public val dueDate: LocalDate,
    public val amountDue: Money,
)

/**
 * Debt figures (docs/10-domain-model.md §7, DEBT-01). Interest is never computed here: PH lenders use different
 * methods (add-on, diminishing), so the user enters principal, interest and fees per payment (DEBT-02).
 */
public object DebtMath {
    /** Outstanding = −balance of the liability account (I-5). Never stored separately. */
    public fun outstanding(liabilityBalance: Money): Money = -liabilityBalance

    /**
     * [count] installments of [total] on the first [count] due dates of [recurrence]. All items are equal except the
     * last, which takes the remainder, so the schedule sums exactly to [total] (AC-ADV-2).
     */
    public fun equalSchedule(
        total: Money,
        count: Int,
        recurrence: Recurrence,
    ): DomainResult<List<ScheduleItem>> =
        validating {
            ensure(total.isPositive, "total", "AMOUNT_NOT_POSITIVE")
            ensure(count in 1..MAX_INSTALLMENTS, "count", "INSTALLMENTS_OUT_OF_RANGE")
            ensure(total.minor >= count, "count", "INSTALLMENTS_EXCEED_AMOUNT")
            val dates = dueDates(recurrence, count)
            val base = total.minor / count
            val last = total.minor - base * (count - 1)
            dates.mapIndexed { i, d -> ScheduleItem(i + 1, d, Money(if (i == count - 1) last else base, total.currency)) }
        }

    /** Unpaid installments (DEBT-01 "remaining installments"). */
    public fun remaining(
        items: List<ScheduleItem>,
        paidSeqs: Set<Int>,
    ): Int = items.count { it.seq !in paidSeqs }

    private fun dueDates(
        r: Recurrence,
        count: Int,
    ): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var from = r.anchorDate
        while (out.size < count) {
            val chunk = RecurrenceCalculator.dueDates(r, from, from.plusDays(CHUNK_DAYS))
            out += chunk.take(count - out.size)
            from = from.plusDays(CHUNK_DAYS + 1)
        }
        return out
    }

    /** 50 years of monthly payments; longer terms are not realistic for personal debts. */
    public const val MAX_INSTALLMENTS: Int = 600
    private const val CHUNK_DAYS = 400L
}
