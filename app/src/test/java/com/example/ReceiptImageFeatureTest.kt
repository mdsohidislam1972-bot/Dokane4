package com.example

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.CompletedSaleReceipt
import com.example.data.model.SaleItem
import com.example.domain.CalculationEngine
import com.example.ui.components.DigitalReceiptContent
import com.example.util.AppLanguage
import com.example.util.LocalAppLanguage
import com.example.util.ReceiptImageRenderer
import com.example.util.ReceiptImageStyle
import com.example.util.ShopLocale
import com.example.util.StockPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptImageFeatureTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleItems(): List<SaleItem> = listOf(
        SaleItem(
            productId = "p1",
            name = "Miniket Rice Premium Export Quality 50kg Bag Special Grade",
            qty = 2.0,
            unit = "bag",
            stockQty = 2.0,
            buyPrice = 2900.0,
            sellPrice = 3250.5,
            discount = 0.0,
            lineTotal = CalculationEngine.roundMoney(6501.0),
            profit = 701.0
        ),
        SaleItem(
            productId = "p2",
            name = "দেশি সরিষার তেল খাঁটি ঘানি ভাঙা ১ লিটার বোতল",
            qty = 3.0,
            unit = "litre",
            stockQty = 3.0,
            buyPrice = 260.0,
            sellPrice = 310.0,
            discount = 0.0,
            lineTotal = CalculationEngine.roundMoney(930.0),
            profit = 150.0
        )
    )

    private fun buildReceipt(
        invoiceNo: String = "INV-0107",
        storeName: String = "রহমান স্টোর Rahman Mart",
        storePhone: String = "+8801712345678",
        storeAddress: String = "42 Station Road, Dhaka",
        items: List<SaleItem> = sampleItems(),
        subtotal: Double = 7431.0,
        discount: Double = 100.0,
        grandTotal: Double = 7331.0,
        paymentType: String = "cash",
        customerName: String = "Cash Sale",
        customerPhone: String = "",
        paidAmount: Double = 7331.0,
        dueAmount: Double = 0.0
    ): CompletedSaleReceipt = CompletedSaleReceipt(
        invoiceNo = invoiceNo,
        storeName = storeName,
        storePhone = storePhone,
        storeAddress = storeAddress,
        items = items,
        totalItems = items.sumOf { it.stockQty },
        subtotal = subtotal,
        discount = discount,
        grandTotal = grandTotal,
        paymentType = paymentType,
        customerName = customerName,
        customerPhone = customerPhone,
        notes = "",
        timestamp = 1_735_000_000_000L,
        storeLogoUri = "",
        paidAmount = paidAmount,
        dueAmount = dueAmount
    )

    @Test
    fun `exported PNG has 2x width 1440px, invoice filename, and valid PNG signature`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val receipt = buildReceipt(invoiceNo = "INV-0107")

        val file = ReceiptImageRenderer.exportReceiptPng(
            context = context,
            receipt = receipt,
            style = ReceiptImageStyle.LIGHT,
            lang = AppLanguage.EN
        )

        assertTrue(file.exists())
        assertEquals("INV-0107.png", file.name)
        val header = file.inputStream().use { stream -> ByteArray(8).also { stream.read(it) } }
        assertEquals(0x89.toByte(), header[0])
        assertEquals(0x50.toByte(), header[1]) // P
        assertEquals(0x4E.toByte(), header[2]) // N
        assertEquals(0x47.toByte(), header[3]) // G

        val bitmap = ReceiptImageRenderer.renderReceiptBitmap(
            context = context,
            receipt = receipt,
            style = ReceiptImageStyle.LIGHT,
            lang = AppLanguage.EN
        )
        assertEquals(ReceiptImageRenderer.EXPORT_WIDTH_PX, bitmap.width) // 1440 px (2x of 720)
        assertTrue(bitmap.height > 720)
        bitmap.recycle()
    }

    @Test
    fun `receipt image height grows dynamically with extra items and wrapping item names`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shortReceipt = buildReceipt(items = listOf(sampleItems().first().copy(name = "Sugar")))
        val longNameAndManyItems = buildReceipt(
            items = listOf(
                sampleItems().first().copy(
                    name = "Ultra Long Product Description That Definitely Wraps Across Multiple Lines On A 720px Receipt Without Ever Being Truncated With Ellipsis"
                ),
                sampleItems()[1],
                sampleItems().first().copy(productId = "p3", name = "Fresh Loose Tea Leaves 500g Packet Premium"),
                sampleItems().first().copy(productId = "p4", name = "Organic Wildflower Honey Glass Jar 250g")
            )
        )

        val shortBmp = ReceiptImageRenderer.renderReceiptBitmap(context, shortReceipt, ReceiptImageStyle.LIGHT, AppLanguage.EN)
        val longBmp = ReceiptImageRenderer.renderReceiptBitmap(context, longNameAndManyItems, ReceiptImageStyle.LIGHT, AppLanguage.EN)

        assertEquals(1440, shortBmp.width)
        assertEquals(1440, longBmp.width)
        assertTrue("Expected multi-item wrapped receipt (${longBmp.height}) to be taller than short receipt (${shortBmp.height})", longBmp.height > shortBmp.height + 200)
        shortBmp.recycle()
        longBmp.recycle()
    }

    @Test
    fun `light vs dark style uses requested background and card pixels and persists in StockPreferences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = StockPreferences(context)
        prefs.setReceiptImageStyle(ReceiptImageStyle.DARK)
        assertEquals(ReceiptImageStyle.DARK, prefs.getReceiptImageStyle())
        prefs.setReceiptImageStyle(ReceiptImageStyle.LIGHT)
        assertEquals(ReceiptImageStyle.LIGHT, prefs.getReceiptImageStyle())

        val receipt = buildReceipt()
        val lightBmp: Bitmap = ReceiptImageRenderer.renderReceiptBitmap(context, receipt, ReceiptImageStyle.LIGHT, AppLanguage.EN)
        val darkBmp: Bitmap = ReceiptImageRenderer.renderReceiptBitmap(context, receipt, ReceiptImageStyle.DARK, AppLanguage.EN)

        // Top-left corner (margin outside card)
        assertEquals(Color.parseColor("#FFFFFF"), lightBmp.getPixel(4, 4))
        assertEquals(Color.parseColor("#0F0F0F"), darkBmp.getPixel(4, 4))

        // Inside card background region (center-x near bottom margin edge inside card)
        assertEquals(Color.parseColor("#FFFFFF"), lightBmp.getPixel(120, 120))
        assertEquals(Color.parseColor("#202020"), darkBmp.getPixel(120, 120))
        assertNotEquals(lightBmp.getPixel(4, 4), darkBmp.getPixel(4, 4))

        lightBmp.recycle()
        darkBmp.recycle()
    }

    @Test
    fun `discount and baki sections adjust height and cash without logo or address renders cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val noDiscountCash = buildReceipt(
            storeName = "Simple Shop",
            storePhone = "",
            storeAddress = "",
            discount = 0.0,
            subtotal = 7431.0,
            grandTotal = 7431.0,
            paymentType = "cash"
        )
        val withDiscountCash = noDiscountCash.copy(discount = 200.0, grandTotal = 7231.0)
        val bakiReceipt = noDiscountCash.copy(
            paymentType = "baki",
            customerName = "আব্দুল করিম",
            customerPhone = "01811223344",
            paidAmount = 2000.0,
            dueAmount = 5431.0
        )

        val noDiscBmp = ReceiptImageRenderer.renderReceiptBitmap(context, noDiscountCash, ReceiptImageStyle.LIGHT, AppLanguage.EN)
        val discBmp = ReceiptImageRenderer.renderReceiptBitmap(context, withDiscountCash, ReceiptImageStyle.LIGHT, AppLanguage.EN)
        val bakiBmp = ReceiptImageRenderer.renderReceiptBitmap(context, bakiReceipt, ReceiptImageStyle.LIGHT, AppLanguage.BN)

        assertTrue(discBmp.height > noDiscBmp.height)
        assertTrue(bakiBmp.height > noDiscBmp.height)

        noDiscBmp.recycle()
        discBmp.recycle()
        bakiBmp.recycle()
    }

    @Test
    fun `single digit style matches active EN or BN language and stored rate preserves exact decimals`() {
        val enRate = ReceiptImageRenderer.formatStoredRate(125.5, AppLanguage.EN)
        val bnRate = ReceiptImageRenderer.formatStoredRate(125.5, AppLanguage.BN)

        assertTrue(enRate.contains("125.5"))
        assertFalse(enRate.any { it in '০'..'৯' })
        assertTrue(bnRate.contains("১২৫.৫"))
        assertFalse(bnRate.any { it in '0'..'9' })

        val bnMoney = ShopLocale.formatMoneyFixed2(7331.0, AppLanguage.BN)
        assertTrue(bnMoney.contains("৭৩৩১.০০"))
        assertFalse(bnMoney.any { it in '0'..'9' })

        val enMoney = ShopLocale.formatMoneyFixed2(7331.0, AppLanguage.EN)
        assertTrue(enMoney.contains("7331.00"))
        assertFalse(enMoney.any { it in '০'..'৯' })
    }

    @Test
    fun `shareReceiptPng launches image_png chooser intent and never sends plain text`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val receipt = buildReceipt(invoiceNo = "INV-0205")

        val result = ReceiptImageRenderer.shareReceiptPng(
            context = context,
            receipt = receipt,
            style = ReceiptImageStyle.LIGHT,
            lang = AppLanguage.EN
        )

        assertTrue(result.isSuccess)
        assertEquals("INV-0205.png", result.getOrNull()?.name)

        val launchedIntent = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(launchedIntent)
        assertEquals(Intent.ACTION_CHOOSER, launchedIntent.action)
        @Suppress("DEPRECATION")
        val targetIntent = launchedIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(targetIntent)
        assertEquals(Intent.ACTION_SEND, targetIntent?.action)
        assertEquals("image/png", targetIntent?.type)
        assertFalse(targetIntent?.hasExtra(Intent.EXTRA_TEXT) == true)
    }

    @Test
    fun `Sale Completed Compose UI displays wrapped full item names and action testTags`() {
        val longName = "Very Long Product Name That Must Wrap Completely On Screen Without Any Ellipsis Truncation"
        val receipt = buildReceipt(
            invoiceNo = "INV-0309",
            items = listOf(sampleItems().first().copy(name = longName)),
            paymentType = "baki",
            customerName = "Karim Traders",
            customerPhone = "01700000000",
            discount = 50.0,
            paidAmount = 1000.0,
            dueAmount = 5451.0
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        var newSaleClicked = false

        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
                DigitalReceiptContent(
                    receipt = receipt,
                    onDismiss = {},
                    onStartNewSale = { newSaleClicked = true },
                    receiptImageStyle = ReceiptImageStyle.LIGHT
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("sale_completed_title").assertIsDisplayed()
        composeRule.onNodeWithText(longName).assertIsDisplayed()
        composeRule.onNodeWithTag("share_receipt_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("save_receipt_image_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("copy_receipt_button").performScrollTo().assertIsDisplayed().performClick()

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(clipText.contains("INV-0309"))
        assertTrue(clipText.contains(longName))

        composeRule.onNodeWithTag("next_sale_button").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(newSaleClicked)
    }

    @Test
    fun `Sale Completed Compose UI renders Bangla digits and Dark style preview cleanly`() {
        val receipt = buildReceipt(
            invoiceNo = "INV-0412",
            paymentType = "cash",
            discount = 0.0,
            subtotal = 7431.0,
            grandTotal = 7431.0
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.BN) {
                DigitalReceiptContent(
                    receipt = receipt,
                    onDismiss = {},
                    onStartNewSale = {},
                    receiptImageStyle = ReceiptImageStyle.DARK
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("sale_completed_title").assertIsDisplayed()
        composeRule.onNodeWithTag("receipt_grand_total_value").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("share_receipt_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("save_receipt_image_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("next_sale_button").performScrollTo().assertIsDisplayed()
    }
}
