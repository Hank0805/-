package jp.minobs.app

import android.app.AlertDialog
import android.widget.EditText

class StudioDialogs(private val activity: ObsStudioActivity) {
  fun text(title: String, initial: String = "", done: (String) -> Unit) {
    val input = EditText(activity).apply { setText(initial) }
    AlertDialog.Builder(activity).setTitle(title).setView(input).setPositiveButton("OK") { _, _ -> done(input.text.toString()) }.setNegativeButton("Cancel", null).show()
  }
}
