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

    private fun input(hint: String, type: Int, start: String = "") = EditText(this).apply {
        this.hint = hint; inputType = type; setText(start)
        setTextColor(Pal.c("#3E1D16")); setHintTextColor(Pal.c("#A98578"))
    }

    private fun pickButton(text: String, values: List<String>, target: EditText) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(Pal.c("#6B352A"))
        setPadding(dp(4), dp(2), dp(4), dp(6)); isClickable = true
        setOnClickListener { if (values.isEmpty()) toast("Nothing to choose from yet.") else PickerDialog.show(this@ProductsActivity, text.trim(), values) { target.setText(it) } }
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
        val cbActive = CheckBox(this).apply { text = "Active (can be ordered)"; isChecked = p?.optBoolean("active", true) ?: true }
        val error = label("", 14f, "#DC2626")
        // the picture of this SKU: a new one is shrunk to a small JPEG here, then sent with Save
        var pendingImage: ByteArray? = null
        var removeImage = false
        val thumb = productThumb(session, p?.optString("image") ?: "", 84)
        val thumbImage = thumb.getChildAt(1) as ImageView
        val btnPick = label("📷  Choose picture", 14f, "#6B352A", true).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8)); isClickable = true
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
        val btnClear = label("✕  Remove picture", 14f, "#B91C1C", true).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8)); isClickable = true
            setOnClickListener { pendingImage = null; removeImage = true; thumbImage.setImageDrawable(null) }
        }
        val picRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(8))
            addView(thumb.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
            addView(LinearLayout(this@ProductsActivity).apply { orientation = LinearLayout.VERTICAL; addView(btnPick); addView(btnClear) })
        }
        val brands = products.map { it.optString("brand") }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        val cats = products.map { it.optString("category") }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        val pad = dp(16)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0)
            addView(picRow); addView(etSku); addView(etName); addView(etBrand); addView(pickButton("▾ Pick an existing brand", brands, etBrand))
            addView(etCat); addView(pickButton("▾ Pick an existing category", cats, etCat))
            addView(etPrice); addView(etUnit); addView(etStock); addView(cbActive); addView(error)
        }
        val dialog = AlertDialog.Builder(this).setTitle(if (isNew) "Add new product" else "Edit product")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
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
                        val body = JSONObject().put("action", "adminSaveProduct").put("isNew", isNew).put("sku", sku)
                            .put("name", etName.text.toString().trim()).put("brand", etBrand.text.toString().trim())
                            .put("category", etCat.text.toString().trim()).put("price", etPrice.text.toString().toDouble())
                            .put("unit", etUnit.text.toString().trim()).put("stock", etStock.text.toString().toLong())
                            .put("active", cbActive.isChecked)
                        pendingImage?.let { body.put("image", Base64.encodeToString(it, Base64.NO_WRAP)) }
                        if (pendingImage == null && removeImage) body.put("removeImage", true)
                        api(body) { res, err ->
                            if (res != null) { dialog.dismiss(); setProducts(res); draw(); toast("Saved") } else error.text = err ?: "Could not save."
                        }
                    }
                }
            }
        }
        dialog.show()
    }
}
