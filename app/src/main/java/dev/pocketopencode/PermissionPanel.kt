package dev.pocketopencode

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.*
import org.json.JSONObject

/** Native fallback for requests not rendered by the upstream mobile web UI. */
class PermissionPanel(context: Context) : LinearLayout(context) {
    private val heading=TextView(context).apply { setTextColor(Color.WHITE); textSize=15f }
    private val details=TextView(context).apply { setTextColor(Color.LTGRAY); textSize=13f; setTextIsSelectable(true) }
    private val deny=Button(context).apply { tag="permission-reject" }
    private val allow=Button(context).apply { tag="permission-allow" }
    var requestId: String?=null; private set
    private var busy=false
    init {
        orientation=VERTICAL; tag="permission-panel"; visibility=View.GONE
        setPadding(12.dp(),8.dp(),12.dp(),8.dp()); setBackgroundColor(Color.rgb(43,43,43))
        addView(heading)
        addView(ScrollView(context).apply { addView(details) },LayoutParams(-1,100.dp()))
        addView(LinearLayout(context).apply {
            addView(deny,LayoutParams(0,-2,1f));addView(allow,LayoutParams(0,-2,1f))
        })
    }
    fun show(request: JSONObject, reply: (Boolean)->Unit) {
        if(busy)return
        requestId=request.getString("id")
        heading.text="${tr(UiText.AllowAction)} · ${request.optString("permission")}"
        details.text=buildString {
            val patterns=request.optJSONArray("patterns")
            if(patterns!=null)for(i in 0 until patterns.length())appendLine(patterns.getString(i))
            val command=request.optJSONObject("metadata")?.optString("command").orEmpty()
            if(command.isNotBlank())appendLine(command)
        }.trim()
        deny.text=tr(UiText.Reject);allow.text=tr(UiText.AllowOnce)
        deny.isEnabled=true;allow.isEnabled=true;visibility=View.VISIBLE
        fun submit(approved:Boolean) { if(busy)return;busy=true;deny.isEnabled=false;allow.isEnabled=false;reply(approved) }
        deny.setOnClickListener { submit(false) };allow.setOnClickListener { submit(true) }
    }
    fun clear() { busy=false;requestId=null;visibility=View.GONE }
    private fun Int.dp()=(this*resources.displayMetrics.density).toInt()
}
