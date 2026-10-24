package com.example.attendance

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.TextUtils
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.Locale

/**
 * Prints an order, or its delivery challan, with Android's own print screen (print to a printer, or "Save as PDF" and share it).
 * The page is built here from the order the server sent; the challan number comes from the server (CH-000001 ...), so it is unique.
 */
object OrderPrint {
    private var holder: WebView? = null          // the page must stay alive until Android has finished with it

    fun print(act: AppCompatActivity, o: JSONObject, challan: Boolean) {
        if (challan && o.optString("challanNo").isBlank()) {
            Toast.makeText(act, "This order has no challan yet. It is made when the admin approves the order.", Toast.LENGTH_LONG).show()
            return
        }
        val jobName = (if (challan) o.optString("challanNo") else o.optString("id")) + " - RM Store"
        val html = if (challan) challanHtml(o) else orderHtml(o)
        val web = WebView(act)
        holder = web
        var started = false
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (started) return
                started = true
                try {
                    val pm = act.getSystemService(Context.PRINT_SERVICE) as PrintManager
                    pm.print(jobName, view.createPrintDocumentAdapter(jobName),
                        PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
                } catch (e: Exception) {
                    Toast.makeText(act, "Printing is not available on this phone.", Toast.LENGTH_LONG).show()
                }
            }
        }
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun esc(s: String): String = TextUtils.htmlEncode(s)

    private fun n(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    private fun money(v: Double): String = "৳ " + String.format(Locale.US, "%,.2f", v)

    private const val STYLE = """
        body{font-family:sans-serif;font-size:12px;color:#222;margin:0}
        h1{font-size:22px;margin:0;color:#6B352A}
        h2{font-size:16px;margin:4px 0 0 0;letter-spacing:1px}
        .muted{color:#666}
        .head{border-bottom:2px solid #6B352A;padding-bottom:8px;margin-bottom:10px}
        table{width:100%;border-collapse:collapse;margin-top:12px}
        th,td{border:1px solid #999;padding:6px;text-align:left;vertical-align:top}
        th{background:#f1e6d6}
        .r{text-align:right}
        .meta td{border:none;padding:2px 0}
        .sig{margin-top:70px;width:100%}
        .sig td{border:none;border-top:1px solid #333;text-align:center;padding-top:4px;width:30%}
        .sig td.gap{border:none;width:5%}
        """

    private fun head(title: String): String =
        "<div class='head'><h1>RM Store</h1><h2>$title</h2></div>"

    private fun orderHtml(o: JSONObject): String {
        val sb = StringBuilder()
        sb.append("<html><head><meta charset='utf-8'><style>").append(STYLE).append("</style></head><body>")
        sb.append(head("ORDER"))
        sb.append("<table class='meta'>")
        sb.append("<tr><td><b>Order no:</b> ${esc(o.optString("id"))}</td><td class='r'><b>Date:</b> ${esc(o.optString("createdOn"))}</td></tr>")
        sb.append("<tr><td><b>Customer:</b> ${esc(o.optString("customer"))}</td><td class='r'><b>Status:</b> ${esc(o.optString("status"))}</td></tr>")
        sb.append("<tr><td><b>Ordered by:</b> ${esc(o.optString("name"))} (${esc(o.optString("username"))})</td><td class='r'>")
        if (o.optString("challanNo").isNotBlank()) sb.append("<b>Challan:</b> ${esc(o.optString("challanNo"))}")
        sb.append("</td></tr></table>")
        sb.append("<table><tr><th>#</th><th>SKU</th><th>Product</th><th class='r'>Qty</th><th class='r'>Unit price</th><th class='r'>Disc %</th><th class='r'>Amount</th></tr>")
        val ls = o.optJSONArray("lines")
        if (ls != null) for (i in 0 until ls.length()) {
            val l = ls.getJSONObject(i)
            val d = l.optDouble("discount")
            sb.append("<tr><td>${i + 1}</td><td>${esc(l.optString("sku"))}</td><td>${esc(l.optString("name"))}</td>")
                .append("<td class='r'>${n(l.optDouble("qty"))}</td><td class='r'>${n(l.optDouble("price"))}</td>")
                .append("<td class='r'>${if (d > 0) n(d) else "-"}</td><td class='r'>${money(l.optDouble("total"))}</td></tr>")
        }
        sb.append("<tr><td colspan='6' class='r'><b>Total</b></td><td class='r'><b>${money(o.optDouble("total"))}</b></td></tr></table>")
        if (o.optString("note").isNotBlank()) sb.append("<p><b>Note:</b> ${esc(o.optString("note"))}</p>")
        sb.append("<p class='muted'>")
        if (o.optString("managerBy").isNotBlank()) sb.append("Manager: ${esc(o.optString("managerBy"))} (${esc(o.optString("managerOn"))}). ")
        if (o.optString("adminBy").isNotBlank()) sb.append("Admin: ${esc(o.optString("adminBy"))} (${esc(o.optString("adminOn"))}).")
        sb.append("</p>")
        sb.append("<table class='sig'><tr><td>Ordered by</td><td class='gap'></td><td>Checked by</td><td class='gap'></td><td>Authorised by</td></tr></table>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun challanHtml(o: JSONObject): String {
        val sb = StringBuilder()
        sb.append("<html><head><meta charset='utf-8'><style>").append(STYLE).append("</style></head><body>")
        sb.append(head("DELIVERY CHALLAN"))
        sb.append("<table class='meta'>")
        sb.append("<tr><td><b>Challan no:</b> ${esc(o.optString("challanNo"))}</td><td class='r'><b>Date:</b> ${esc(o.optString("challanOn"))}</td></tr>")
        sb.append("<tr><td><b>Order no:</b> ${esc(o.optString("id"))}</td><td class='r'><b>Order date:</b> ${esc(o.optString("createdOn"))}</td></tr>")
        sb.append("<tr><td colspan='2'><b>Delivered to:</b> ${esc(o.optString("customer"))}</td></tr>")
        sb.append("<tr><td colspan='2'><b>Sales person:</b> ${esc(o.optString("name"))} (${esc(o.optString("username"))})</td></tr></table>")
        sb.append("<table><tr><th>#</th><th>SKU</th><th>Product</th><th class='r'>Qty</th></tr>")
        var totalQty = 0.0
        val ls = o.optJSONArray("lines")
        if (ls != null) for (i in 0 until ls.length()) {
            val l = ls.getJSONObject(i)
            totalQty += l.optDouble("qty")
            sb.append("<tr><td>${i + 1}</td><td>${esc(l.optString("sku"))}</td><td>${esc(l.optString("name"))}</td><td class='r'>${n(l.optDouble("qty"))}</td></tr>")
        }
        sb.append("<tr><td colspan='3' class='r'><b>Total quantity</b></td><td class='r'><b>${n(totalQty)}</b></td></tr></table>")
        if (o.optString("note").isNotBlank()) sb.append("<p><b>Note:</b> ${esc(o.optString("note"))}</p>")
        sb.append("<p class='muted'>Goods received in good condition.</p>")
        sb.append("<table class='sig'><tr><td>Prepared by</td><td class='gap'></td><td>Delivered by</td><td class='gap'></td><td>Received by (customer)</td></tr></table>")
        sb.append("</body></html>")
        return sb.toString()
    }
}
