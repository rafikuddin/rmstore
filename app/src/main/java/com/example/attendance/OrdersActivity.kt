package com.example.attendance

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Orders. Three pages: a new order (customer + products, quantity, price and discount), my orders, and Review
 * (a line manager sees the orders of the people who report to them and approves them on to the admin; an admin sees every
 * order, approves or rejects it, and marks approved ones delivered). The server decides who may do what.
 */
class OrdersActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var content: LinearLayout
    private var isAdmin = false
    private var isManager = false
    private var mode = "new"                        // new | mine | review
    private var reviewAll = false                   // review page: false = only what needs my decision

    // the order being typed (kept when you switch pages)
    private class Line(val p: JSONObject, var qty: Int, var price: Double, var discount: Double = 0.0) {
        fun total(): Double = Math.round(qty * price * (1.0 - discount / 100.0) * 100.0) / 100.0
    }
    private val products = ArrayList<JSONObject>()
    private var productsLoaded = false
    private val lines = ArrayList<Line>()
    private var customer = ""
    private var note = ""
    private var brandFilter = ""
    private var categoryFilter = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { finish(); return }
        setContentView(R.layout.activity_notices)
        setupHeader("Orders") { show() }
        setupBottomNav(null)
        content = findViewById(R.id.noticeList)
        isAdmin = AdminFlag.get(this)
        isManager = session.isManager || isAdmin
        show()
    }

    // ---------- small view helpers ----------

    private fun label(text: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Pal.c(color))
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun money(v: Double): String = "৳ " + String.format(Locale.US, "%,.2f", v)

    private fun qtyText(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    private fun pill(text: String, fg: String, bg: String) = TextView(this).apply {
        this.text = text; setTextColor(Pal.c(fg)); setTypeface(null, Typeface.BOLD); textSize = 12f
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Pal.c(bg)) }
    }

    private fun statusPill(status: String): TextView = when (status) {
        "Approved" -> pill(status, "#166534", "#DCFCE7")
        "Delivered" -> pill(status, "#1D4ED8", "#DBEAFE")
        "Rejected", "Cancelled" -> pill(status, "#B91C1C", "#FEE2E2")
        else -> pill(status, "#B45309", "#FEF3C7")
    }

    private fun cardOf(inner: View): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(24).toFloat(); cardElevation = dp(3).toFloat(); setCardBackgroundColor(Pal.card)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        addView(inner)
    }

    private fun column(pad: Int = 16) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad - 2), dp(pad), dp(pad - 2))
    }

    private fun chip(text: String, selected: Boolean, weight: Boolean = true, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(Pal.c(if (selected) "#FFFFFF" else "#6B352A"))
        setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c(if (selected) "#6B352A" else "#F0E3A6")) }
        isClickable = true; isFocusable = true
        layoutParams = if (weight) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
                       else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun button(text: String, outlined: Boolean = false, onClick: () -> Unit) =
        (if (outlined) MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle) else MaterialButton(this)).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun input(hint: String, type: Int, start: String = "") = EditText(this).apply {
        this.hint = hint; inputType = type; setText(start)
        setTextColor(Pal.c("#3E1D16")); setHintTextColor(Pal.c("#A98578"))
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun watch(e: EditText, f: (String) -> Unit) {
        e.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { f(s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    // ---------- the page ----------

    private fun show() {
        content.removeAllViews()
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        tabs.addView(chip("🛒 New order", mode == "new") { mode = "new"; show() })
        tabs.addView(chip("📦 My orders", mode == "mine") { mode = "mine"; show() })
        if (isManager) tabs.addView(chip(if (isAdmin) "✅ All orders" else "✅ Review", mode == "review") { mode = "review"; show() })
        content.addView(tabs)
        when (mode) {
            "new" -> showNew()
            "mine" -> loadOrders("mine")
            else -> loadOrders("review")
        }
    }

    // ---------- new order ----------

    private fun showNew() {
        if (!productsLoaded) {
            content.addView(label("Loading products…", 14f, "#8A5F52").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
            api(JSONObject().put("action", "products")) { res, err ->
                if (res == null) { toast(err ?: "Could not load the products."); return@api }
                products.clear()
                val arr = res.optJSONArray("products")
                if (arr != null) for (i in 0 until arr.length()) products.add(arr.getJSONObject(i))
                productsLoaded = true
                if (mode == "new") show()
            }
            return
        }
        if (products.isEmpty()) {
            content.addView(label("There are no products yet. An admin adds them in the menu: Products.", 15f, "#8A5F52").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
            return
        }
        val form = column()
        form.addView(label("ORDER FOR", 12f, "#6B352A", true))
        val etCustomer = input("Customer / shop name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, customer)
        watch(etCustomer) { customer = it }
        form.addView(etCustomer)
        val etNote = input("Note (optional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, note)
        watch(etNote) { note = it }
        form.addView(etNote)

        val brands = products.map { it.optString("brand") }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        val cats = products.map { it.optString("category") }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        form.addView(label("PRODUCTS", 12f, "#6B352A", true).apply { setPadding(0, dp(14), 0, dp(4)) })
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bBrand = chip("Brand: " + (if (brandFilter.isEmpty()) "All" else brandFilter) + " ▾", false) { }
        val bCat = chip("Category: " + (if (categoryFilter.isEmpty()) "All" else categoryFilter) + " ▾", false) { }
        bBrand.setOnClickListener {
            val items = listOf("All brands") + brands
            PickerDialog.show(this, "Brand", items) { picked -> brandFilter = if (picked == "All brands") "" else picked; bBrand.text = "Brand: " + (if (brandFilter.isEmpty()) "All" else brandFilter) + " ▾" }
        }
        bCat.setOnClickListener {
            val items = listOf("All categories") + cats
            PickerDialog.show(this, "Category", items) { picked -> categoryFilter = if (picked == "All categories") "" else picked; bCat.text = "Category: " + (if (categoryFilter.isEmpty()) "All" else categoryFilter) + " ▾" }
        }
        filters.addView(bBrand); filters.addView(bCat)
        form.addView(filters)
        form.addView(button("＋  Add product") { pickProduct() })
        content.addView(cardOf(form))

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(box)
        val totalView = label("", 18f, "#6B352A", true).apply { setPadding(dp(8), dp(6), dp(8), dp(6)) }
        content.addView(totalView)
        content.addView(button("✅  Place order") { placeOrder() })
        drawLines(box, totalView)
    }

    private fun pickProduct() {
        val list = products.filter {
            (brandFilter.isEmpty() || it.optString("brand") == brandFilter) && (categoryFilter.isEmpty() || it.optString("category") == categoryFilter)
        }
        if (list.isEmpty()) { toast("No product matches this brand and category."); return }
        val labels = list.map {
            "${it.optString("name")}  ·  ${it.optString("brand")}  ·  ${it.optString("sku")}  ·  ${money(it.optDouble("price"))}/${it.optString("unit")}  ·  stock ${qtyText(it.optDouble("stock"))}"
        }
        PickerDialog.show(this, "Choose a product", labels) { picked ->
            val p = list[labels.indexOf(picked)]
            val existing = lines.firstOrNull { it.p.optString("sku").equals(p.optString("sku"), true) }
            if (existing != null) existing.qty += 1 else lines.add(Line(p, 1, p.optDouble("price")))
            show()
        }
    }

    private fun drawLines(box: LinearLayout, totalView: TextView) {
        box.removeAllViews()
        val lineTotals = ArrayList<TextView>()
        fun refresh() {
            var sum = 0.0
            lines.forEachIndexed { i, l -> lineTotals[i].text = "Line total: " + money(l.total()); sum += l.total() }
            totalView.text = if (lines.isEmpty()) "No products added yet" else "Order total: " + money(Math.round(sum * 100.0) / 100.0)
        }
        lines.forEachIndexed { i, l ->
            val col = column()
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            names.addView(label(l.p.optString("name"), 16f, "#3E1D16", true))
            names.addView(label("${l.p.optString("sku")} · ${l.p.optString("brand")} · ${l.p.optString("category")} · list ${money(l.p.optDouble("price"))}/${l.p.optString("unit")} · stock ${qtyText(l.p.optDouble("stock"))}", 12f, "#8A5F52"))
            top.addView(names)
            top.addView(label("✕", 20f, "#B91C1C", true).apply {
                setPadding(dp(12), dp(4), dp(4), dp(4)); isClickable = true
                setOnClickListener { lines.removeAt(i); show() }
            })
            col.addView(top)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            fun cell(title: String, e: EditText) {
                val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) } }
                c.addView(label(title, 11f, "#8A5F52", true)); c.addView(e); row.addView(c)
            }
            val eQty = input("Qty", InputType.TYPE_CLASS_NUMBER, l.qty.toString())
            val ePrice = input("Price", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, qtyText(l.price))
            val eDisc = input("%", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, if (l.discount == 0.0) "" else qtyText(l.discount))
            cell("Quantity", eQty); cell("Unit price", ePrice); cell("Discount %", eDisc)
            col.addView(row)
            val lt = label("", 14f, "#6B352A", true).apply { setPadding(0, dp(6), 0, 0) }
            lineTotals.add(lt); col.addView(lt)
            watch(eQty) { l.qty = it.toIntOrNull() ?: 0; refresh() }
            watch(ePrice) { l.price = it.toDoubleOrNull() ?: 0.0; refresh() }
            watch(eDisc) { l.discount = it.toDoubleOrNull() ?: 0.0; refresh() }
            box.addView(cardOf(col))
        }
        refresh()
    }

    private fun placeOrder() {
        if (customer.isBlank()) { toast("Enter the customer or shop name."); return }
        if (lines.isEmpty()) { toast("Add at least one product."); return }
        val arr = JSONArray()
        for (l in lines) {
            if (l.qty < 1) { toast("Quantity of ${l.p.optString("name")} must be 1 or more."); return }
            if (l.discount < 0 || l.discount > 100) { toast("Discount of ${l.p.optString("name")} must be 0 to 100."); return }
            arr.put(JSONObject().put("sku", l.p.optString("sku")).put("qty", l.qty).put("price", l.price).put("discount", l.discount))
        }
        api(JSONObject().put("action", "orderCreate").put("customer", customer.trim()).put("note", note.trim()).put("lines", arr)) { res, err ->
            if (res == null) { toast(err ?: "Could not place the order."); return@api }
            val st = res.optString("status")
            toast("Order ${res.optString("id")} placed. " + if (st == "Pending Manager") "Waiting for your line manager." else "Waiting for the admin.")
            lines.clear(); customer = ""; note = ""
            productsLoaded = false                // stock may have changed
            mode = "mine"; show()
        }
    }

    // ---------- lists ----------

    private fun loadOrders(scope: String) {
        content.addView(label("Loading…", 14f, "#8A5F52").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
        api(JSONObject().put("action", "orders").put("scope", scope)) { res, err ->
            if (scope != (if (mode == "review") "review" else "mine")) return@api      // the person already switched page
            // remove the "Loading…" line (the last child)
            if (content.childCount > 1) content.removeViewAt(content.childCount - 1)
            if (res == null) { content.addView(label(err ?: "Could not load.", 14f, "#DC2626").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) }); return@api }
            val all = ArrayList<JSONObject>()
            val arr = res.optJSONArray("orders")
            if (arr != null) for (i in 0 until arr.length()) all.add(arr.getJSONObject(i))
            if (scope == "review") {
                val todo = res.optInt("todo")
                val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } }
                bar.addView(chip("Needs my decision ($todo)", !reviewAll) { reviewAll = false; show() })
                bar.addView(chip("All", reviewAll) { reviewAll = true; show() })
                content.addView(bar)
                val shown = if (reviewAll) all else all.filter { needsMe(it) }
                if (shown.isEmpty()) content.addView(label(if (reviewAll) "No orders yet." else "Nothing is waiting for your decision.", 15f, "#8A5F52").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
                shown.forEach { content.addView(orderCard(it, true)) }
            } else {
                if (all.isEmpty()) content.addView(label("You have not placed any order yet.", 15f, "#8A5F52").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
                all.forEach { content.addView(orderCard(it, false)) }
            }
        }
    }

    private fun needsMe(o: JSONObject): Boolean {
        val st = o.optString("status")
        if (isAdmin) return st == "Pending Admin" || st == "Pending Manager"
        return st == "Pending Manager" && o.optString("approver").equals(session.username, true)
    }

    private fun orderCard(o: JSONObject, review: Boolean): View {
        val id = o.optString("id"); val status = o.optString("status")
        val col = column()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        names.addView(label(o.optString("customer"), 16f, "#3E1D16", true))
        names.addView(label("$id · ${o.optString("createdOn")}", 12f, "#8A5F52"))
        if (review) names.addView(label("By ${o.optString("name")} (${o.optString("username")})", 12f, "#8A5F52"))
        top.addView(names); top.addView(statusPill(status))
        col.addView(top)
        val ls = o.optJSONArray("lines")
        if (ls != null) for (i in 0 until ls.length()) {
            val l = ls.getJSONObject(i)
            val disc = l.optDouble("discount")
            col.addView(label("• ${l.optString("name")} × ${qtyText(l.optDouble("qty"))} @ ${qtyText(l.optDouble("price"))}" + (if (disc > 0) " −${qtyText(disc)}%" else "") + " = ${money(l.optDouble("total"))}", 13f, "#3E1D16")
                .apply { setPadding(0, dp(4), 0, 0) })
        }
        col.addView(label("Total: " + money(o.optDouble("total")), 16f, "#6B352A", true).apply { setPadding(0, dp(8), 0, 0) })
        if (o.optString("note").isNotBlank()) col.addView(label("Note: ${o.optString("note")}", 12f, "#8A5F52"))
        if (o.optString("managerBy").isNotBlank()) col.addView(label("Manager ${o.optString("managerBy")} · ${o.optString("managerOn")}" + (if (o.optString("managerRemark").isNotBlank()) " — ${o.optString("managerRemark")}" else ""), 12f, "#8A5F52"))
        if (o.optString("adminBy").isNotBlank()) col.addView(label("Admin ${o.optString("adminBy")} · ${o.optString("adminOn")}" + (if (o.optString("adminRemark").isNotBlank()) " — ${o.optString("adminRemark")}" else ""), 12f, "#8A5F52"))
        if (o.optString("deliveredOn").isNotBlank()) col.addView(label("Delivered · ${o.optString("deliveredOn")}", 12f, "#1D4ED8", true))

        if (!review && (status == "Pending Manager" || status == "Pending Admin")) {
            col.addView(button("Cancel this order", outlined = true) {
                AlertDialog.Builder(this).setTitle("Cancel order $id?").setMessage("It will not go any further.")
                    .setPositiveButton("Cancel order") { _, _ -> decide("orderCancel", id, "", "") }
                    .setNegativeButton("Keep it", null).show()
            })
        }
        if (review && needsMe(o)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
            row.addView(MaterialButton(this).apply {
                text = "✅ Approve"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                setOnClickListener {
                    val msg = if (isAdmin) "The order is approved and the stock is reduced." else "It goes to the admin for the final approval."
                    AlertDialog.Builder(this@OrdersActivity).setTitle("Approve $id?").setMessage(msg)
                        .setPositiveButton("Approve") { _, _ -> decide("orderDecide", id, "approve", "") }
                        .setNegativeButton("Cancel", null).show()
                }
            })
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "✖ Reject"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
                setOnClickListener { rejectDialog(id) }
            })
            col.addView(row)
        }
        if (review && isAdmin && status == "Approved") {
            col.addView(button("🚚  Mark delivered") {
                AlertDialog.Builder(this).setTitle("Mark $id delivered?")
                    .setPositiveButton("Delivered") { _, _ -> decide("orderDecide", id, "deliver", "") }
                    .setNegativeButton("Cancel", null).show()
            })
        }
        return cardOf(col)
    }

    private fun rejectDialog(id: String) {
        val et = input("Reason (required)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        val d = AlertDialog.Builder(this).setTitle("Reject $id?").setView(box)
            .setPositiveButton("Reject", null).setNegativeButton("Cancel", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val r = et.text.toString().trim()
                if (r.length < 3) { et.error = "Write a short reason"; return@setOnClickListener }
                d.dismiss(); decide("orderDecide", id, "reject", r)
            }
        }
        d.show()
    }

    private fun decide(action: String, id: String, decision: String, remark: String) {
        val body = JSONObject().put("action", action).put("orderId", id)
        if (decision.isNotEmpty()) body.put("decision", decision)
        if (remark.isNotEmpty()) body.put("remark", remark)
        api(body) { res, err ->
            if (res == null) { toast(err ?: "Could not save."); return@api }
            toast("Done: " + res.optString("status"))
            productsLoaded = false
            show()
        }
    }
}
