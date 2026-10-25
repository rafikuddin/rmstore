package com.example.attendance

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Products (admins only): add a new product (SKU, name, brand, category, price, unit, stock) and edit or switch off the ones
 * that exist. Switched-off products can no longer be ordered. The server checks that the person is an admin.
 */
class ProductsActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var content: LinearLayout
    private lateinit var etSearch: SeekField
    private val products = ArrayList<JSONObject>()
    private var query = ""
    private val picker = PicturePicker(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { finish(); return }
        if (!AdminFlag.get(this)) { Toast.makeText(this, "Only an admin can manage products.", Toast.LENGTH_LONG).show(); finish(); return }
        setContentView(R.layout.activity_notices)
        setupHeader("Products") { load() }
        setupBottomNav(null)
        content = findViewById(R.id.noticeList)
        load()
    }

    private fun label(text: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Pal.c(color))
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun money(v: Double): String = "৳ " + String.format(Locale.US, "%,.2f", v)

    private fun num(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun pill(text: String, fg: String, bg: String) = TextView(this).apply {
        this.text = text; setTextColor(Pal.c(fg)); setTypeface(null, Typeface.BOLD); textSize = 12f
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Pal.c(bg)) }
    }

    private fun load() {
        content.removeAllViews()
        content.addView(label("Loading…", 14f, "#8A5F52").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
        api(JSONObject().put("action", "adminProducts")) { res, err ->
            content.removeAllViews()
            if (res == null) { content.addView(label(err ?: "Could not load.", 14f, "#DC2626").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) }); return@api }
            setProducts(res)
            draw()
        }
    }

    private fun setProducts(res: JSONObject) {
        products.clear()
        val arr = res.optJSONArray("products")
        if (arr != null) for (i in 0 until arr.length()) products.add(arr.getJSONObject(i))
    }

    private fun draw() {
        content.removeAllViews()
        content.addView(MaterialButton(this).apply {
            text = "＋  Add new product"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            setOnClickListener { editDialog(null) }
        })
        etSearch = SeekField(this, "Search SKU, name, brand or category").also {
            it.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) { query = e?.toString()?.trim().orEmpty(); fill() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        content.addView(etSearch)
        content.addView(LinearLayout(this).apply { tag = "rows"; orientation = LinearLayout.VERTICAL })
        fill()
    }

    private fun fill() {
        val box = content.findViewWithTag<LinearLayout>("rows") ?: return
        box.removeAllViews()
        val shown = products.filter {
            query.isEmpty() || it.optString("sku").contains(query, true) || it.optString("name").contains(query, true) ||
                it.optString("brand").contains(query, true) || it.optString("category").contains(query, true)
        }
        box.addView(label("${shown.size} of ${products.size} products", 12f, "#8A5F52", true).apply { setPadding(dp(6), dp(10), dp(6), dp(6)) })
        if (products.isEmpty()) box.addView(label("No products yet. Tap “Add new product”.", 15f, "#8A5F52").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
        shown.forEach { box.addView(card(it)) }
    }

    private fun card(p: JSONObject): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        names.addView(label(p.optString("name"), 16f, "#3E1D16", true))
        names.addView(label("SKU: ${p.optString("sku")}  ·  ${p.optString("brand")}  ·  ${p.optString("category")}", 12f, "#8A5F52"))
        top.addView(productThumb(session, p.optString("image"), 56).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12) })
        top.addView(names)
        top.addView(if (p.optBoolean("active", true)) pill("Active", "#166534", "#DCFCE7") else pill("Off", "#B91C1C", "#FEE2E2"))
        col.addView(top)
        col.addView(label("${money(p.optDouble("price"))} / ${p.optString("unit")}   ·   Stock: ${num(p.optDouble("stock"))}", 14f, "#6B352A", true).apply { setPadding(0, dp(6), 0, 0) })
        return MaterialCardView(this).apply {
            radius = dp(24).toFloat(); cardElevation = dp(3).toFloat(); setCardBackgroundColor(Pal.card)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            isClickable = true; isFocusable = true
            setOnClickListener { editDialog(p) }
            addView(col)
        }
    }

    private fun roundBg(fill: String, stroke: String?, radius: Int) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat(); setColor(Pal.c(fill)); if (stroke != null) setStroke(dp(1), Pal.c(stroke))
    }

    private fun input(hint: String, type: Int, start: String = "") = EditText(this).apply {
        this.hint = hint; inputType = type; setText(start); textSize = 15f
        setTextColor(Pal.c("#3E1D16")); setHintTextColor(Pal.c("#B8A096"))
        background = roundBg("#FBF7F2", "#E4D3C8", 14)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    /** A caption above a rounded field, with an optional small action (like "Pick existing") on the right. */
    private fun field(caption: String, et: EditText, action: View? = null): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(this@ProductsActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(label(caption, 12f, "#8A5F52", true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (action != null) addView(action)
        })
        addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    private fun section(title: String, vararg rows: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(14))
        background = roundBg("#FFFFFF", "#EFE3DA", 20)
        addView(label(title, 14f, "#6B352A", true))
        rows.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    private fun pickButton(text: String, values: List<String>, target: EditText) = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(Pal.c("#6B352A")); typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(10), dp(3), dp(10), dp(3)); isClickable = true
        background = roundBg("#F0E3A6", null, 12)
        setOnClickListener { if (values.isEmpty()) toast("Nothing to choose from yet.") else PickerDialog.show(this@ProductsActivity, "Choose", values) { target.setText(it) } }
    }

    /** Makes a small JPEG (longest side 480 px, about 30-100 KB) so the product list loads quickly on a phone. */
    private fun shrink(src: ByteArray): ByteArray? {
        val bmp = BitmapFactory.decodeByteArray(src, 0, src.size) ?: return null
        val scale = minOf(1f, 480f / maxOf(bmp.width, bmp.height))
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * scale).toInt()), maxOf(1, (bmp.height * scale).toInt()), true) else bmp
        var quality = 85
        var bytes: ByteArray
        do {
            val bo = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, quality, bo)
            bytes = bo.toByteArray()
            quality -= 10
        } while (bytes.size > 120_000 && quality > 30)
        return if (bytes.size < 600) null else bytes
    }

    /** Add (p == null) or edit one product. */
    private fun editDialog(p: JSONObject?) {
        val isNew = p == null
        val etSku = input("SKU (code)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, p?.optString("sku") ?: "")
        if (!isNew) { etSku.isEnabled = false }
        val etName = input("Product name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, p?.optString("name") ?: "")
        val etBrand = input("Brand", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, p?.optString("brand") ?: "")
        val etCat = input("Category", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, p?.optString("category") ?: "")
        val etPrice = input("Price", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, if (p == null) "" else num(p.optDouble("price")))
        val etUnit = input("Unit (pcs, box, kg…)", InputType.TYPE_CLASS_TEXT, p?.optString("unit") ?: "pcs")
        val etStock = input("Stock quantity", InputType.TYPE_CLASS_NUMBER, if (p == null) "0" else num(p.optDouble("stock")))
        val swActive = androidx.appcompat.widget.SwitchCompat(this).apply { isChecked = p?.optBoolean("active", true) ?: true }
        val error = label("", 14f, "#DC2626")
        // the picture of this SKU: a new one is shrunk to a small JPEG here, then sent with Save
        var pendingImage: ByteArray? = null
        var removeImage = false
        val thumb = productThumb(session, p?.optString("image") ?: "", 84)
        val thumbImage = thumb.getChildAt(1) as ImageView
        val btnPick = label("📷  Choose picture", 13f, "#6B352A", true).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8)); isClickable = true; background = roundBg("#F0E3A6", null, 20)
            setOnClickListener {
                picker.choose { raw ->
                    val small = shrink(raw)
                    if (small == null) toast("That picture could not be used.")
                    else {
                        pendingImage = small; removeImage = false
                        thumbImage.setImageBitmap(BitmapFactory.decodeByteArray(small, 0, small.size))
                    }
                }
            }
        }
        val btnClear = label("✕  Remove", 13f, "#B91C1C", true).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8)); isClickable = true; background = roundBg("#FEE2E2", null, 20)
            setOnClickListener { pendingImage = null; removeImage = true; thumbImage.setImageDrawable(null) }
        }
        val brands = products.map { it.optString("brand") }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        val cats = products.map { it.optString("category") }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }

        // ---- header band ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.c("#6B352A"), Pal.c("#A0604C"))).apply {
                cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f)
            }
            addView(label("📦", 28f, "#FFFFFF", false).apply { setPadding(0, 0, dp(12), 0) })
            addView(LinearLayout(this@ProductsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(if (isNew) "New product" else "Edit product", 20f, "#FFFFFF", true))
                addView(label(if (isNew) "Add it to the catalogue" else "SKU ${p?.optString("sku")}", 12f, "#F0E3D8", false))
            })
        }
        // ---- picture ----
        val picBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(thumb.apply { (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL })
            addView(LinearLayout(this@ProductsActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0)
                addView(btnPick); addView(btnClear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            })
        }
        val priceUnit = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(field("PRICE (৳)", etPrice), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            addView(field("UNIT", etUnit), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        }
        val activeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundBg("#FFFFFF", "#EFE3DA", 20)
            addView(LinearLayout(this@ProductsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Active", 15f, "#3E1D16", true)); addView(label("Users can order it", 12f, "#8A5F52", false))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(swActive)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        val btnCancel = label("Cancel", 15f, "#6B352A", true).apply { setPadding(dp(18), dp(12), dp(18), dp(12)); isClickable = true }
        val btnSave = MaterialButton(this).apply {
            text = "Save product"; isAllCaps = false; textSize = 15f; cornerRadius = dp(24)
            setBackgroundColor(Pal.c("#6B352A")); setTextColor(Pal.c("#FFFFFF"))
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END
            addView(btnCancel); addView(btnSave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginStart = dp(6) })
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(picBox)
            addView(section("BASIC INFO", field("SKU (CODE)", etSku), field("PRODUCT NAME", etName)))
            addView(section("CLASSIFICATION", field("BRAND", etBrand, pickButton("▾ Pick existing", brands, etBrand)), field("CATEGORY", etCat, pickButton("▾ Pick existing", cats, etCat))))
            addView(section("PRICE & STOCK", priceUnit, field("STOCK QUANTITY", etStock)))
            addView(activeRow)
            addView(error.apply { setPadding(dp(4), dp(8), dp(4), 0) })
            addView(buttons)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = roundBg("#F7F0EA", null, 28)
            addView(header)
            addView(ScrollView(this@ProductsActivity).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val dialog = AlertDialog.Builder(this).setView(root).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
                val sku = etSku.text.toString().trim()
                when {
                    sku.isEmpty() -> error.text = "Enter the SKU"
                    etName.text.toString().isBlank() -> error.text = "Enter the product name"
                    etBrand.text.toString().isBlank() -> error.text = "Enter the brand"
                    etCat.text.toString().isBlank() -> error.text = "Enter the category"
                    etPrice.text.toString().toDoubleOrNull() == null -> error.text = "Enter the price"
                    etStock.text.toString().toLongOrNull() == null -> error.text = "Stock must be a whole number"
                    else -> {
                        error.text = "Saving…"
                        val req = JSONObject().put("action", "adminSaveProduct").put("isNew", isNew).put("sku", sku)
                            .put("name", etName.text.toString().trim()).put("brand", etBrand.text.toString().trim())
                            .put("category", etCat.text.toString().trim()).put("price", etPrice.text.toString().toDouble())
                            .put("unit", etUnit.text.toString().trim()).put("stock", etStock.text.toString().toLong())
                            .put("active", swActive.isChecked)
                        pendingImage?.let { req.put("image", Base64.encodeToString(it, Base64.NO_WRAP)) }
                        if (pendingImage == null && removeImage) req.put("removeImage", true)
                        api(req) { res, err ->
                            if (res != null) { dialog.dismiss(); setProducts(res); draw(); toast("Saved") } else error.text = err ?: "Could not save."
                        }
                    }
                }
        }
        dialog.show()
    }
}
