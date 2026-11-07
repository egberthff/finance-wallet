package ph.financewallet.domain.ledger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ph.financewallet.domain.common.DomainError
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.errorOrNull
import ph.financewallet.domain.common.getOrThrow
import java.time.LocalDate

class EntryTemplatesTest {
    private val f = LedgerFixture()
    private val t = f.templates

    private fun code(result: DomainResult<*>): String? = result.errorOrNull()?.code

    private fun field(result: DomainResult<*>): String? = (result.errorOrNull() as? DomainError.Validation)?.field

    @Test
    fun `EX-5 transfer GCash to BPI creates two balanced entries and no nominal entries`() {
        val d = t.transfer("gcash", "bpi", f.p(200_000)).getOrThrow()
        assertEquals(TxnType.TRANSFER, d.type)
        assertEquals(listOf(Entry("gcash", f.p(-200_000)), Entry("bpi", f.p(200_000))), d.entries)
    }

    @Test
    fun `expense and income with splits`() {
        val e = t.expense("gcash", listOf(Split("food", f.p(30_000)), Split("transport", f.p(20_000)))).getOrThrow()
        assertEquals(listOf(Entry("gcash", f.p(-50_000)), Entry("food", f.p(30_000)), Entry("transport", f.p(20_000))), e.entries)
        val i = t.income("bpi", listOf(Split("salary", f.p(3_000_000)))).getOrThrow()
        assertEquals(listOf(Entry("bpi", f.p(3_000_000)), Entry("salary", f.p(-3_000_000))), i.entries)
        assertEquals(TxnType.INCOME, i.type)
    }

    @Test
    fun `EX-6 card purchase is an expense from the liability, card payment is a transfer`() {
        val purchase = t.expense("card", listOf(Split("shopping", f.p(120_000)))).getOrThrow()
        assertEquals(Entry("card", f.p(-120_000)), purchase.entries.first())
        val payment = t.transfer("bpi", "card", f.p(120_000)).getOrThrow()
        assertEquals(listOf(Entry("bpi", f.p(-120_000)), Entry("card", f.p(120_000))), payment.entries)
    }

    @Test
    fun `EX-7 linked refund defaults to the original category and links to it`() {
        val original = f.postOk(t.expense("gcash", listOf(Split("food", f.p(50_000)))))
        val r = t.refund("gcash", f.p(20_000), original = RefundOriginal(original, f.p(0))).getOrThrow()
        assertEquals(listOf(Entry("gcash", f.p(20_000)), Entry("food", f.p(-20_000))), r.entries)
        assertEquals(original.id, r.refundOfTxnId)
    }

    @Test
    fun `refund validation`() {
        val split = f.postOk(t.expense("gcash", listOf(Split("food", f.p(30_000)), Split("transport", f.p(20_000)))))
        val none = RefundOriginal(split, f.p(0))
        assertEquals("REFUND_CATEGORY_REQUIRED", code(t.refund("gcash", f.p(100), original = none)))
        assertEquals("REFUND_CATEGORY_NOT_IN_ORIGINAL", code(t.refund("gcash", f.p(100), "shopping", none)))
        assertEquals(TxnType.REFUND, t.refund("gcash", f.p(100), "transport", none).getOrThrow().type)
        assertEquals("REFUND_EXCEEDS_ORIGINAL", code(t.refund("gcash", f.p(10_001), "food", RefundOriginal(split, f.p(40_000)))))
        assertEquals(TxnType.REFUND, t.refund("gcash", f.p(10_000), "food", RefundOriginal(split, f.p(40_000))).getOrThrow().type)
        val income = f.postOk(t.income("bpi", listOf(Split("salary", f.p(100)))))
        assertEquals("REFUND_ORIGINAL_NOT_EXPENSE", code(t.refund("bpi", f.p(100), "food", RefundOriginal(income, f.p(0)))))
        assertEquals(
            "ORIGINAL_NOT_EFFECTIVE",
            code(t.refund("gcash", f.p(100), "food", RefundOriginal(split.copy(deleted = true), f.p(0)))),
        )
        assertEquals("CURRENCY_MISMATCH", code(t.refund("gcash", f.p(100), "food", RefundOriginal(split, f.u(0)))))
        // Unlinked refunds need an expense category.
        assertEquals("REFUND_CATEGORY_REQUIRED", code(t.refund("gcash", f.p(100))))
        assertEquals("CATEGORY_WRONG_CLASS", code(t.refund("gcash", f.p(100), "salary")))
        assertNull(t.refund("gcash", f.p(100), "food").getOrThrow().refundOfTxnId)
    }

    @Test
    fun `EX-8 loan payment splits principal from interest`() {
        val d = t.debtPayment("bpi", "loan", f.p(100_000), f.p(10_000), f.p(0)).getOrThrow()
        assertEquals(
            listOf(Entry("bpi", f.p(-110_000)), Entry("loan", f.p(100_000)), Entry("sys:expense:interest_fees", f.p(10_000))),
            d.entries,
        )
        val feesOnly = t.debtPayment("bpi", "loan", f.p(0), f.p(0), f.p(500), feeCategoryId = "food").getOrThrow()
        assertEquals(listOf(Entry("bpi", f.p(-500)), Entry("food", f.p(500))), feesOnly.entries)
        val principalOnly = t.debtPayment("bpi", "card", f.p(700), f.p(0), f.p(0)).getOrThrow()
        assertEquals(2, principalOnly.entries.size)
        assertEquals("AMOUNT_NOT_POSITIVE", code(t.debtPayment("bpi", "loan", f.p(0), f.p(0), f.p(0))))
        assertEquals("AMOUNT_NEGATIVE", code(t.debtPayment("bpi", "loan", f.p(-1), f.p(0), f.p(5))))
        assertEquals("ACCOUNT_NOT_ASSET", code(t.debtPayment("card", "loan", f.p(1), f.p(0), f.p(0))))
        assertEquals("ACCOUNT_NOT_LIABILITY", code(t.debtPayment("bpi", "gcash", f.p(1), f.p(0), f.p(0))))
    }

    @Test
    fun `debt disbursement increases cash and the liability`() {
        val d = t.debtDisbursement("bpi", "loan", f.p(1_000_000)).getOrThrow()
        assertEquals(listOf(Entry("bpi", f.p(1_000_000)), Entry("loan", f.p(-1_000_000))), d.entries)
        assertEquals("AMOUNT_NOT_POSITIVE", code(t.debtDisbursement("bpi", "loan", f.p(0))))
        assertEquals("CURRENCY_MISMATCH", code(t.debtDisbursement("usd-bank", "loan", f.u(5))))
    }

    @Test
    fun `savings contribution and withdrawal require the savings side`() {
        assertEquals(TxnType.SAVINGS_CONTRIBUTION, t.savingsContribution("bpi", "bpi-savings", f.p(500_000)).getOrThrow().type)
        assertEquals("NOT_SAVINGS_ACCOUNT", code(t.savingsContribution("bpi", "gcash", f.p(1))))
        assertEquals(TxnType.SAVINGS_WITHDRAWAL, t.savingsWithdrawal("bpi-savings", "bpi", f.p(1)).getOrThrow().type)
        assertEquals("NOT_SAVINGS_ACCOUNT", code(t.savingsWithdrawal("gcash", "bpi", f.p(1))))
    }

    @Test
    fun `transfer validation`() {
        assertEquals("SAME_ACCOUNT", code(t.transfer("gcash", "gcash", f.p(1))))
        assertEquals("CURRENCY_MISMATCH", code(t.transfer("gcash", "usd-bank", f.p(1))))
        assertEquals("AMOUNT_NOT_POSITIVE", code(t.transfer("gcash", "bpi", f.p(0))))
        assertEquals("AMOUNT_NOT_POSITIVE", code(t.transfer("gcash", "bpi", f.p(-5))))
        assertEquals("CURRENCY_MISMATCH", code(t.transfer("gcash", "bpi", f.u(5))))
        assertEquals("ACCOUNT_ARCHIVED", code(t.transfer("old-wallet", "bpi", f.p(5))))
        assertEquals("ACCOUNT_NOT_REAL", code(t.transfer("food", "bpi", f.p(5))))
        assertEquals("NOT_FOUND", code(t.transfer("ghost", "bpi", f.p(5))))
        assertEquals("fromId", field(t.transfer("old-wallet", "bpi", f.p(5))))
    }

    @Test
    fun `cross-currency transfer balances per currency through equity fx`() {
        val d = t.crossCurrencyTransfer("bpi", f.p(560_000), "usd-bank", f.u(10_000)).getOrThrow()
        assertEquals(
            listOf(
                Entry("bpi", f.p(-560_000)),
                Entry("sys:equity:fx", f.p(560_000)),
                Entry("sys:equity:fx", f.u(-10_000)),
                Entry("usd-bank", f.u(10_000)),
            ),
            d.entries,
        )
        assertEquals("SAME_CURRENCY", code(t.crossCurrencyTransfer("bpi", f.p(1), "gcash", f.p(1))))
        assertEquals("SAME_ACCOUNT", code(t.crossCurrencyTransfer("bpi", f.p(1), "bpi", f.p(1))))
        assertEquals("CURRENCY_MISMATCH", code(t.crossCurrencyTransfer("bpi", f.u(1), "usd-bank", f.u(1))))
        assertEquals("AMOUNT_NOT_POSITIVE", code(t.crossCurrencyTransfer("bpi", f.p(1), "usd-bank", f.u(0))))
    }

    @Test
    fun `split validation`() {
        assertEquals("SPLITS_EMPTY", code(t.expense("gcash", emptyList())))
        assertEquals("DUPLICATE_CATEGORY", code(t.expense("gcash", listOf(Split("food", f.p(1)), Split("food", f.p(2))))))
        assertEquals("CATEGORY_WRONG_CLASS", code(t.expense("gcash", listOf(Split("salary", f.p(1))))))
        assertEquals("CATEGORY_WRONG_CLASS", code(t.income("gcash", listOf(Split("food", f.p(1))))))
        assertEquals("CATEGORY_ARCHIVED", code(t.expense("gcash", listOf(Split("old-category", f.p(1))))))
        assertEquals("AMOUNT_NOT_POSITIVE", code(t.expense("gcash", listOf(Split("food", f.p(0))))))
        assertEquals("CURRENCY_MISMATCH", code(t.expense("gcash", listOf(Split("food", f.u(1))))))
        assertEquals("NOT_FOUND", code(t.expense("gcash", listOf(Split("ghost", f.p(1))))))
    }

    @Test
    fun `reversal negates every entry exactly once`() {
        val original = f.postOk(t.expense("gcash", listOf(Split("food", f.p(30_000)), Split("transport", f.p(20_000)))))
        val r = t.reversal(original, alreadyReversed = false).getOrThrow()
        assertEquals(original.entries.map { Entry(it.accountId, -it.amount) }, r.entries)
        assertEquals(original.id, r.reversesTxnId)
        assertEquals("ALREADY_REVERSED", code(t.reversal(original, alreadyReversed = true)))
        val reversalTx = f.post(r)
        assertEquals("REVERSAL_OF_REVERSAL", code(t.reversal(reversalTx, alreadyReversed = false)))
        assertEquals("ORIGINAL_NOT_EFFECTIVE", code(t.reversal(original.copy(status = TxnStatus.PENDING), false)))
    }

    @Test
    fun `opening balance and adjustment post against their equity accounts`() {
        val opening = t.openingBalance("card", f.p(-250_000)).getOrThrow()
        assertEquals(listOf(Entry("card", f.p(-250_000)), Entry("sys:equity:opening", f.p(250_000))), opening.entries)
        assertEquals(TxnType.OPENING_BALANCE, opening.type)
        val adj = t.balanceAdjustment("cash", f.p(3_500)).getOrThrow()
        assertEquals(listOf(Entry("cash", f.p(3_500)), Entry("sys:equity:adjustment", f.p(-3_500))), adj.entries)
        assertEquals("AMOUNT_ZERO", code(t.balanceAdjustment("cash", f.p(0))))
        assertEquals("CURRENCY_MISMATCH", code(t.openingBalance("cash", f.u(1))))
    }

    @Test
    fun `invariant checker rejects malformed entry sets`() {
        val d = f.directory
        assertEquals("TOO_FEW_ENTRIES", LedgerInvariants.check(listOf(Entry("gcash", f.p(5))), d).errorOrNull()?.code)
        assertEquals("UNBALANCED", LedgerInvariants.check(listOf(Entry("gcash", f.p(5)), Entry("food", f.p(-4))), d).errorOrNull()?.code)
        assertEquals("ZERO_ENTRY", LedgerInvariants.check(listOf(Entry("gcash", f.p(0)), Entry("food", f.p(0))), d).errorOrNull()?.code)
        assertEquals("UNKNOWN_ACCOUNT", LedgerInvariants.check(listOf(Entry("x", f.p(5)), Entry("food", f.p(-5))), d).errorOrNull()?.code)
        assertEquals(
            "REAL_ACCOUNT_CURRENCY",
            LedgerInvariants.check(listOf(Entry("gcash", f.u(5)), Entry("food", f.u(-5))), d).errorOrNull()?.code,
        )
        assertEquals(DomainResult.Ok(Unit), LedgerInvariants.check(listOf(Entry("gcash", f.p(5)), Entry("food", f.p(-5))), d))
    }

    @Test
    fun `fixture dates are irrelevant to templates`() {
        // Templates never look at dates; attribution happens in calculators (guards against accidental coupling).
        val d = t.transfer("gcash", "bpi", f.p(1)).getOrThrow()
        assertEquals(d.entries, f.post(d, LocalDate.of(2000, 1, 1)).entries)
    }

    @Test
    fun `archived ids are allowed only when listed in allowArchived (edits of existing transactions)`() {
        val edit = EntryTemplates(f.directory, allowArchived = setOf("old-category", "old-wallet"))
        // Listed: an edit may keep the archived category and account its transaction already uses.
        assertNull(code(edit.expense("old-wallet", listOf(Split("old-category", f.p(1))))))
        // Not listed: the default templates still refuse archived ones for new use.
        assertEquals("CATEGORY_ARCHIVED", code(t.expense("gcash", listOf(Split("old-category", f.p(1))))))
        assertEquals("ACCOUNT_ARCHIVED", code(t.expense("old-wallet", listOf(Split("food", f.p(1))))))
        // Listing one archived id does not open up another.
        val onlyCategory = EntryTemplates(f.directory, allowArchived = setOf("old-category"))
        assertEquals("ACCOUNT_ARCHIVED", code(onlyCategory.expense("old-wallet", listOf(Split("old-category", f.p(1))))))
    }
}
