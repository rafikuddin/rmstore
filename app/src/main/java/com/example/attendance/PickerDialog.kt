package com.example.attendance

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity

/** A pick-one list with a search box, so choosing from 64 districts or 50+ thanas is quick. */
object PickerDialog {
    fun show(activity: AppCompatActivity, title: String, items: List<String>, onPick: (String) -> Unit) {
        val ctx: Context = activity
        val density = ctx.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val search = SeekField(ctx, "Type to search", openOnShow = true)
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, ArrayList(items))
        val list = ListView(ctx).apply { this.adapter = adapter }

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(search)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (360 * density).toInt()))
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .create()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                adapter.clear()
                adapter.addAll(if (q.isEmpty()) items else items.filter { it.contains(q, ignoreCase = true) })
            }
        })
        list.setOnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let { onPick(it) }
            dialog.dismiss()
        }
        dialog.show()
    }
}
