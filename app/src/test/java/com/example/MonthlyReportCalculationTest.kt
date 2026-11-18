package com.example

import com.example.data.model.Account
import com.example.data.model.Customer
import com.example.data.model.Expense
import com.example.data.model.OtherIncome
import com.example.data.model.Payment
import com.example.data.model.Product
import com.example.data.model.Purchase
import com.example.data.model.PurchaseItem
import com.example.data.model.Sale
import com.example.data.model.SaleItem
import com.example.data.model.Store
import com.example.data.model.Supplier
import com.example.data.model.SupplierPayment
import com.example.domain.CalculationEngine
import com.example.util.AppLanguage
import com.example.util.ReportDataBuilders
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date

class MonthlyReportCalculationTest {

    private fun tsOf(year: Int, monthZeroBased: Int, day: Int, hour: Int = 12): Timestamp {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, monthZeroBased)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return Timestamp(Date(cal.timeInMillis))
    }

    @Test
    fun monthlyReport_computesAllEightSectionsAndReconciliationChecks() {
        val year = 2025
        val month = Calendar.OCTOBER // 9 (past month, deterministic against nowMillis)

        val products = listOf(
            Product(id = "p1", name = "Rice 50kg", unit = "bag", buyPrice = 2000.0, sellPrice = 2500.0, stock = 10.0, lowStockLimit = 5.0),
            Product(id = "p2", name = "Sugar", unit = "kg", buyPrice = 100.0, sellPrice = 130.0, stock = 3.0, lowStockLimit = 5.0),
            Product(id = "p3", name = "Dead Stock Oil", unit = "ltr", buyPrice = 150.0, sellPrice = 180.0, stock = 20.0, lowStockLimit = 2.0)
        )

        val customers = listOf(
            Customer(id = "c1", name = "Rahim Store", mobile = "01711111111"),
            Customer(id = "c2", name = "Karim Traders", mobile = "01822222222")
        )

        val suppliers = listOf(
            Supplier(id = "s1", name = "Jamuna Mills", mobile = "01933333333", balance = 4000.0)
        )

        val accounts = listOf(
            Account(id = "cash_in_hand", name = "Cash in Hand", type = "Cash", openingBalance = 5000.0)
        )

        // Prior Baki in August 2025 (so > 60 days overdue by Oct 31, 2025)
        val priorBakiSale = Sale(
            id = "sale_old",
            invoiceNo = "INV-0001",
            customerId = "c1",
            customerName = "Rahim Store",
            paymentType = "baki",
            subtotal = 1000.0,
            discount = 0.0,
            grandTotal = 1000.0,
            items = listOf(SaleItem(productId = "p2", name = "Sugar", qty = 10.0, buyPrice = 80.0, sellPrice = 100.0, lineTotal = 1000.0, profit = 200.0)),
            createdAt = tsOf(2025, Calendar.AUGUST, 10)
        )

        // October sales: 1 cash walk-in + 1 baki sale with discount
        val octCashSale = Sale(
            id = "sale_oct_cash",
            invoiceNo = "INV-0002",
            customerId = "",
            customerName = "Cash Sale",
            paymentType = "cash",
            accountId = "cash_in_hand",
            accountName = "Cash in Hand",
            subtotal = 5000.0,
            discount = 200.0,
            grandTotal = 4800.0,
            items = listOf(SaleItem(productId = "p1", name = "Rice 50kg", qty = 2.0, buyPrice = 2000.0, sellPrice = 2500.0, discount = 200.0, lineTotal = 4800.0, profit = 800.0)),
            createdAt = tsOf(year, month, 12)
        )
        val octBakiSale = Sale(
            id = "sale_oct_baki",
            invoiceNo = "INV-0003",
            customerId = "c2",
            customerName = "Karim Traders",
            paymentType = "baki",
            subtotal = 2600.0,
            discount = 0.0,
            grandTotal = 2600.0,
            items = listOf(SaleItem(productId = "p2", name = "Sugar", qty = 20.0, buyPrice = 100.0, sellPrice = 130.0, lineTotal = 2600.0, profit = 600.0)),
            createdAt = tsOf(year, month, 20)
        )

        // October customer payment from c1 reducing old baki from 1000 to 600
        val octPaymentIn = Payment(
            id = "pay_oct_1",
            customerId = "c1",
            customerName = "Rahim Store",
            amount = 400.0,
            accountId = "cash_in_hand",
            accountName = "Cash in Hand",
            paymentDate = tsOf(year, month, 18).toDate().time,
            createdAt = tsOf(year, month, 18)
        )

        // October supplier purchase: 6000 total, 2000 paid, 4000 due -> matches supplier.balance = 4000
        val octPurchase = Purchase(
            id = "pur_oct_1",
            supplierId = "s1",
            supplierName = "Jamuna Mills",
            totalAmount = 6000.0,
            paidAmount = 2000.0,
            dueAmount = 4000.0,
            accountId = "cash_in_hand",
            accountName = "Cash in Hand",
            items = listOf(PurchaseItem(productId = "p1", name = "Rice 50kg", qty = 3.0, buyPrice = 2000.0, lineTotal = 6000.0)),
            createdAt = tsOf(year, month, 5)
        )

        // October expenses (sorted Rent 1200, Tea 300) + Other income 100
        val octExpenses = listOf(
            Expense(id = "exp_1", amount = 300.0, category = "Tea", accountId = "cash_in_hand", date = tsOf(year, month, 8).toDate().time),
            Expense(id = "exp_2", amount = 1200.0, category = "Rent", accountId = "cash_in_hand", date = tsOf(year, month, 2).toDate().time),
            Expense(id = "exp_del", amount = 999.0, category = "Deleted", accountId = "cash_in_hand", date = tsOf(year, month, 3).toDate().time, isDeleted = true)
        )
        val octIncomes = listOf(
            OtherIncome(id = "inc_1", amount = 100.0, category = "Scrap", accountId = "cash_in_hand", date = tsOf(year, month, 22).toDate().time)
        )

        val report = CalculationEngine.calculateMonthlyReportData(
            year = year,
            month = month,
            allSales = listOf(priorBakiSale, octCashSale, octBakiSale),
            products = products,
            customers = customers,
            suppliers = suppliers,
            allPayments = listOf(octPaymentIn),
            lang = AppLanguage.EN,
            allPurchases = listOf(octPurchase),
            allPurchaseReturns = emptyList(),
            allSupplierPayments = emptyList(),
            allExpenses = octExpenses,
            allOtherIncomes = octIncomes,
            accounts = accounts,
            allAdjustments = emptyList()
        )

        // 1 & 2. Sales
        assertEquals(7600.0, report.grossSales, 0.01)
        assertEquals(200.0, report.totalDiscounts, 0.01)
        assertEquals(7400.0, report.netSales, 0.01)
        assertEquals(2L, report.totalOrders)
        assertEquals(3700.0, report.averageOrderValue, 0.01)
        assertEquals(4800.0, report.cashReceivedSales, 0.01)
        assertEquals(2600.0, report.bakiCreditSales, 0.01)
        assertEquals("p1", report.topProductsByAmount.first().productId)
        assertEquals("Karim Traders", report.topCustomersByAmount.first().customerName)

        // 8. P&L: COGS = 2*2000 + 20*100 = 6000; Gross = 7400 - 6000 = 1400; Net = 1400 + 100 - 1500 = 0
        assertEquals(6000.0, report.costOfGoodsSold, 0.01)
        assertEquals(1400.0, report.grossProfit, 0.01)
        assertEquals(1500.0, report.totalExpenses, 0.01)
        assertEquals(100.0, report.otherIncome, 0.01)
        assertEquals(0.0, report.netProfit, 0.01)

        // 6. Expenses sorted biggest first
        assertEquals(2, report.expensesByCategory.size)
        assertEquals("Rent", report.expensesByCategory[0].category)
        assertEquals(1200.0, report.expensesByCategory[0].amount, 0.01)

        // 3. Baki + Ageing: c1 owes 600 (>60d from Aug 10), c2 owes 2600 (0-30d from Oct 20)
        assertEquals(1000.0, report.bakiOpeningReceivable, 0.01)
        assertEquals(2600.0, report.bakiNewCreditGiven, 0.01)
        assertEquals(400.0, report.bakiCollectedThisMonth, 0.01)
        assertEquals(3200.0, report.bakiClosingReceivable, 0.01)
        assertEquals(2600.0, report.bakiAgeing.bucket0To30, 0.01)
        assertEquals(600.0, report.bakiAgeing.bucket60Plus, 0.01)
        assertTrue(report.bakiCheck.matches)

        // 4. Suppliers
        assertEquals(0.0, report.supplierOpeningPayable, 0.01)
        assertEquals(6000.0, report.supplierPurchasesThisMonth, 0.01)
        assertEquals(2000.0, report.supplierPaidThisMonth, 0.01)
        assertEquals(4000.0, report.supplierClosingPayable, 0.01)
        assertTrue(report.supplierCheck.matches)

        // 5. Stock: low stock has Sugar (3 <= 5), Dead Stock Oil has 20 * 150 = 3000 stuck
        assertTrue(report.lowStockItems.any { it.productId == "p2" })
        assertTrue(report.slowStock30DayItems.any { it.productId == "p3" && it.moneyStuck == 3000.0 })
        assertEquals(3000.0, report.totalSlowStockMoneyStuck, 0.01)

        // 7. Cash & Bank: Open 5000 + In (4800 + 400 + 100 = 5300) - Out (2000 + 1500 = 3500) = 6800
        assertEquals(5000.0, report.cashOpeningTotal, 0.01)
        assertEquals(5300.0, report.cashInTotal, 0.01)
        assertEquals(3500.0, report.cashOutTotal, 0.01)
        assertEquals(6800.0, report.cashClosingBalance, 0.01)
        assertTrue(report.cashCheck.matches)

        // Export builder exports all 8 sections + check when includeSupplierDebt = true
        val fullExport = ReportDataBuilders.buildMonthlyReportTable(
            reportData = report,
            year = year,
            month = month,
            store = Store(id = "st1", name = "Demo Dokan"),
            canSeeProfit = true,
            lang = AppLanguage.BN,
            includeSupplierDebt = true
        )
        assertTrue(fullExport.rows.any { it.first().contains("৮. লাভ ও ক্ষতি") })
        assertTrue(fullExport.rows.any { it.first().contains("হিসাব যাচাই") })

        // Father mode export exports ONLY Summary
        val fatherExport = ReportDataBuilders.buildMonthlyReportTable(
            reportData = report,
            year = year,
            month = month,
            store = Store(id = "st1", name = "Demo Dokan"),
            canSeeProfit = true,
            lang = AppLanguage.EN,
            includeSupplierDebt = false
        )
        assertTrue(fatherExport.rows.all { it.first() == "1. Summary" })
    }

    @Test
    fun monthlyReport_detectsSupplierBalanceMismatchWarningInsteadOfHiding() {
        val year = 2025
        val month = Calendar.NOVEMBER

        val mismatchingSupplier = Supplier(id = "s1", name = "Mismatch Supplier", balance = 9500.0)
        val purchase = Purchase(
            id = "p1",
            supplierId = "s1",
            supplierName = "Mismatch Supplier",
            totalAmount = 5000.0,
            paidAmount = 1000.0,
            dueAmount = 4000.0,
            createdAt = tsOf(year, month, 10)
        )

        val report = CalculationEngine.calculateMonthlyReportData(
            year = year,
            month = month,
            allSales = emptyList(),
            products = emptyList(),
            customers = emptyList(),
            suppliers = listOf(mismatchingSupplier),
            allPayments = emptyList(),
            allPurchases = listOf(purchase),
            allSupplierPayments = listOf(
                SupplierPayment(id = "sp1", supplierId = "s1", amount = 500.0, paymentDate = tsOf(year, month, 12).toDate().time)
            )
        )

        assertFalse(report.supplierCheck.matches)
        assertEquals(9500.0, report.supplierCheck.actualClosing, 0.01)
        assertEquals(3500.0, report.supplierCheck.expectedClosing, 0.01)
        assertEquals(6000.0, report.supplierCheck.difference, 0.01)
    }
}
