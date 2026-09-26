package dev.pocketopencode

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/** The agent's eyes (window content, screenshots) and hands (gestures, text, global keys). Enabled by the user in Accessibility settings. */
class PhoneControlService : AccessibilityService() {
    companion object {
        @Volatile var instance: PhoneControlService? = null
            private set
        fun enabled(context: Context): Boolean {
            val me = ComponentName(context, PhoneControlService::class.java)
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
    /** Uptime of the latest UI event; actions wait until the screen goes quiet. */
    @Volatile var lastEvent = 0L
        private set
    override fun onServiceConnected() { lastEvent = SystemClock.uptimeMillis(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { lastEvent = SystemClock.uptimeMillis() }
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { if (instance === this) instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
}

class PhoneElement(val number: Int, val node: AccessibilityNodeInfo, val bounds: Rect, val tap: Point,
                   val line: String, val actionable: Boolean, val scrollable: Boolean)

class PhoneScreen(val elements: List<PhoneElement>, val text: String, val width: Int, val height: Int)

/** Turns the accessibility trees of all visible windows into a compact numbered list, top window first. */
object PhoneScreenReader {
    private const val MAX_ELEMENTS = 300
    private class Candidate(val node: AccessibilityNodeInfo, val bounds: Rect, val tap: Point, val line: String, val actionable: Boolean)

    fun read(service: PhoneControlService): PhoneScreen {
        val size = screenSize(service)
        val screen = Rect(0, 0, size.x, size.y)
        val covered = Region()
        val groups = mutableListOf<Pair<String, List<Candidate>>>()
        var app = ""
        var keyboard = false
        var visited = 0
        for (window in service.windows.sortedByDescending { it.layer }) {
            val windowBounds = Rect().also(window::getBoundsInScreen)
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) { keyboard = true; covered.op(windowBounds, Region.Op.UNION); continue }
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            val root = window.root ?: continue
            val found = mutableListOf<Candidate>()
            fun walk(node: AccessibilityNodeInfo, depth: Int, consumed: Boolean) {
                if (depth > 64 || ++visited > 5000 || !node.isVisibleToUser) return
                val box = Rect().also(node::getBoundsInScreen)
                if (!box.intersect(screen)) return
                val actionable = node.isClickable || node.isLongClickable || node.isEditable || node.isCheckable || node.isScrollable
                val own = label(node)
                if (actionable || (!consumed && own.isNotEmpty())) {
                    // Elements hidden behind a dialog, the keyboard or another window cannot be tapped.
                    val visible = Region(box).apply { op(covered, Region.Op.DIFFERENCE) }
                    if (!visible.isEmpty) {
                        val area = visible.bounds
                        val text = own.ifEmpty { if (actionable && !node.isScrollable) childText(node) else "" }
                        found += Candidate(node, box, Point(area.centerX(), area.centerY()), describe(node, text), actionable)
                    }
                }
                // A clickable row owns its texts; list items inside a scrollable container stay separate.
                val consumes = consumed || (actionable && !node.isScrollable)
                for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1, consumes) }
            }
            walk(root, 0, false)
            val pkg = root.packageName?.toString().orEmpty()
            if (app.isEmpty() && window.type == AccessibilityWindowInfo.TYPE_APPLICATION) app = pkg
            if (found.isNotEmpty()) {
                val title = window.title?.toString()?.takeIf { it.isNotBlank() }
                groups += "== ${listOfNotNull(title, pkg.takeIf { it.isNotEmpty() && it != title }).joinToString(" · ")} ==" to
                    found.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            }
            covered.op(windowBounds, Region.Op.UNION)
        }
        val elements = mutableListOf<PhoneElement>()
        val text = StringBuilder()
        text.append("Foreground app: ").append(app.ifEmpty { "unknown" })
        if (keyboard) text.append(" · keyboard open")
        text.append('\n')
        var hidden = 0
        for ((header, items) in groups) {
            text.append(header).append('\n')
            for (item in items) {
                if (elements.size >= MAX_ELEMENTS) { hidden++; continue }
                val number = elements.size + 1
                elements += PhoneElement(number, item.node, item.bounds, item.tap, item.line, item.actionable, item.node.isScrollable)
                text.append('[').append(number).append("] ").append(item.line).append('\n')
            }
        }
        if (elements.isEmpty()) text.append("(no readable elements; try phone_screenshot)\n")
        if (hidden > 0) text.append("(").append(hidden).append(" more elements not listed; scroll or use phone_screenshot)\n")
        return PhoneScreen(elements, text.toString().trimEnd(), size.x, size.y)
    }

    private fun label(node: AccessibilityNodeInfo): String {
        val showingHint = Build.VERSION.SDK_INT >= 26 && node.isShowingHintText
        val parts = listOfNotNull(
            node.text?.takeUnless { showingHint || node.isPassword },
            node.contentDescription,
            if (Build.VERSION.SDK_INT >= 30) node.stateDescription else null,
        ).map { it.toString().replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }.distinct()
        return parts.joinToString(" · ").take(120)
    }

    private fun childText(node: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        fun collect(parent: AccessibilityNodeInfo, depth: Int) {
            for (i in 0 until parent.childCount) {
                if (parts.sumOf { it.length } > 120) return
                val child = parent.getChild(i) ?: continue
                if (!child.isVisibleToUser) continue
                if (child.isClickable || child.isEditable || child.isCheckable || child.isScrollable) continue
                label(child).takeIf { it.isNotEmpty() }?.let(parts::add)
                if (depth < 4) collect(child, depth + 1)
            }
        }
        collect(node, 0)
        return parts.distinct().joinToString(" · ").take(120)
    }

    private fun describe(node: AccessibilityNodeInfo, text: String): String {
        val cls = node.className?.toString().orEmpty()
        val kind = when {
            node.isEditable -> "input"
            cls.endsWith("Switch") || cls.endsWith("SwitchCompat") || cls.endsWith("SwitchMaterial") -> "switch"
            cls.endsWith("CheckBox") -> "checkbox"
            cls.endsWith("RadioButton") -> "radio"
            cls.endsWith("SeekBar") || cls.endsWith("Slider") -> "slider"
            cls.contains("Button") -> "button"
            node.isScrollable -> "list"
            cls.contains("Image") -> "image"
            node.isCheckable -> "toggle"
            node.isClickable || node.isLongClickable -> "item"
            else -> "text"
        }
        val flags = buildList {
            if (node.isCheckable) add(if (node.isChecked) "on" else "off")
            if (node.isSelected) add("selected")
            if (!node.isEnabled) add("disabled")
            if (node.isEditable && node.isFocused) add("focused")
            if (node.isPassword) add("password")
            if (node.isScrollable) add("scrollable")
            if (node.isLongClickable && !node.isClickable) add("long-press")
        }
        return buildString {
            append(kind)
            if (text.isNotEmpty() || node.isEditable) append(" \"").append(text).append('"')
            if (node.isEditable) {
                val hint = (if (Build.VERSION.SDK_INT >= 26) node.hintText else null)
                    ?: node.text?.takeIf { Build.VERSION.SDK_INT >= 26 && node.isShowingHintText }
                if (!hint.isNullOrBlank()) append(" hint \"").append(hint.toString().take(60)).append('"')
            }
            if (text.isEmpty() && !node.isEditable) node.viewIdResourceName?.substringAfter(":id/")?.let { append(" id=").append(it) }
            if (flags.isNotEmpty()) append(" (").append(flags.joinToString(", ")).append(')')
        }
    }

    @Suppress("DEPRECATION")
    fun screenSize(context: Context): Point = Point().also {
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(it)
    }
}

class PhoneToolFailure(message: String) : Exception(message)

/** MCP tools over the accessibility service. Calls are serialized; every action returns the settled screen. */
object PhoneTools {
    const val INSTRUCTIONS = "Optional tools for operating this Android phone's apps. Use them only when the user asks you to do something " +
        "on the phone or in another app; for chat, questions and coding work do not call them, and never call them just to look around. " +
        "When a task needs the phone, call phone_screen to read the screen as a numbered list of elements, " +
        "act by element number with phone_tap / phone_type / phone_scroll, and read the updated screen that every action returns. " +
        "Prefer element numbers over coordinates and phone_open_app over hunting for icons. Use phone_screenshot only when the list " +
        "is not enough (images, unlabeled icons, games, maps). Scroll to find items that are off screen. Do not enter passwords or " +
        "payment details, and do not confirm purchases, payments, messages to other people or deletions unless the user explicitly asked for it."
    private const val DISABLED = "Phone control is disabled. Ask the user to enable it: Opencode → Environment → Phone control " +
        "(Android Settings → Accessibility → Opencode phone control)."
    private val mutex = Mutex()
    private var screen: PhoneScreen? = null
    private val recent = ArrayDeque<Int>()
    private class App(val label: String, val pkg: String)

    fun definitions(): JSONArray {
        fun prop(type: String, description: String, vararg values: String) = JSONObject().put("type", type).put("description", description)
            .apply { if (values.isNotEmpty()) put("enum", JSONArray(values.toList())) }
        fun tool(name: String, description: String, readOnly: Boolean, vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()) =
            JSONObject().put("name", name).put("description", description)
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
                    .put("required", JSONArray(required)))
                .put("annotations", JSONObject().put("readOnlyHint", readOnly))
        val index = "index" to prop("integer", "Element number from the latest screen (preferred)")
        val x = "x" to prop("number", "X in pixels of the latest phone_screenshot image (only when there is no element number)")
        val y = "y" to prop("number", "Y in pixels of the latest phone_screenshot image")
        return JSONArray()
            .put(tool("screen", "Read the current phone screen as a numbered list of visible elements (buttons, inputs, texts, lists) grouped by window, top window first. Numbers stay valid until the next action.", true,
                "wait_ms" to prop("integer", "Wait this long before reading, e.g. while something loads (max 10000)")))
            .put(tool("screenshot", "Screenshot of the phone with element numbers drawn as red boxes, plus the element list. Use when the list is not enough: images, unlabeled icons, games, maps, visual checks.", true,
                "marks" to prop("boolean", "Draw element numbers (default true)")))
            .put(tool("tap", "Tap an element by number, or at x,y of the latest screenshot. Returns the updated screen.", false, index, x, y))
            .put(tool("long_press", "Long-press an element by number, or at x,y of the latest screenshot. Returns the updated screen.", false, index, x, y,
                "ms" to prop("integer", "Press duration in ms (default 800)")))
            .put(tool("type", "Set the text of an input (by number, or the focused input) without the keyboard and verify it. Replaces the content unless clear=false. submit=true presses Enter/Search afterwards.", false,
                index, "text" to prop("string", "Text to enter"), "clear" to prop("boolean", "Replace existing text (default true)"),
                "submit" to prop("boolean", "Press Enter/Search after typing (default false)"), required = listOf("text")))
            .put(tool("scroll", "Scroll to reveal more content. direction=down shows content further down. Scrolls inside the element number if given, otherwise the main scrollable list.", false,
                "direction" to prop("string", "Where the new content should come from", "down", "up", "left", "right"), index,
                "amount" to prop("number", "Fraction of the list size to scroll, 0.2-0.9 (default 0.6)"), required = listOf("direction")))
            .put(tool("swipe", "Raw swipe gesture between two points of the latest screenshot (pixels of that image). Prefer phone_scroll for lists.", false,
                "x1" to prop("number", "Start X"), "y1" to prop("number", "Start Y"), "x2" to prop("number", "End X"), "y2" to prop("number", "End Y"),
                "ms" to prop("integer", "Duration in ms (default 300; shorter is a fling)"), required = listOf("x1", "y1", "x2", "y2")))
            .put(tool("key", "Press a system key. enter submits the focused input.", false,
                "key" to prop("string", "Key to press", "back", "home", "recents", "notifications", "quick_settings", "enter"), required = listOf("key")))
            .put(tool("open_app", "Launch an installed app by its launcher name (any language) or package name. Returns the app's first screen.", false,
                "name" to prop("string", "App name or package, e.g. Settings, Chrome, com.android.settings"), required = listOf("name")))
            .put(tool("list_apps", "List installed launchable apps with their package names.", true))
    }

    suspend fun call(name: String, args: JSONObject): JSONObject = mutex.withLock {
        try {
            val service = PhoneControlService.instance ?: throw PhoneToolFailure(DISABLED)
            when (name) {
                "screen" -> { settle(service, args.optLong("wait_ms", 0).coerceIn(0, 10_000)); text(read(service).text) }
                "screenshot" -> screenshot(service, args.optBoolean("marks", true))
                "tap" -> { val (point, what) = target(service, args); act(service) { gesture(service, Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }, 60); "Tapped $what." } }
                "long_press" -> {
                    val (point, what) = target(service, args)
                    act(service) { gesture(service, Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }, args.optLong("ms", 800).coerceIn(400, 3000)); "Long-pressed $what." }
                }
                "type" -> type(service, args)
                "scroll" -> scroll(service, args)
                "swipe" -> {
                    val s = scale(current(service))
                    val path = Path().apply {
                        moveTo(args.getDouble("x1").toFloat() / s, args.getDouble("y1").toFloat() / s)
                        lineTo(args.getDouble("x2").toFloat() / s, args.getDouble("y2").toFloat() / s)
                    }
                    act(service) { gesture(service, path, args.optLong("ms", 300).coerceIn(50, 3000)); "Swiped." }
                }
                "key" -> key(service, args.optString("key"))
                "open_app" -> openApp(service, args.optString("name").trim())
                "list_apps" -> text(apps(service).joinToString("\n") { "${it.label} — ${it.pkg}" })
                else -> throw PhoneToolFailure("Unknown tool: $name")
            }
        } catch (e: PhoneToolFailure) { failure(e.message.orEmpty()) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure("${e.javaClass.simpleName}: ${e.message}") }
    }

    fun failure(message: String) = text(message).put("isError", true)
    private fun text(value: String) = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", value))).put("isError", false)
    private fun read(service: PhoneControlService) = PhoneScreenReader.read(service).also { screen = it }
    private fun current(service: PhoneControlService) = screen ?: read(service)
    /** Screenshots are halved on large displays; coordinates from the model are in image pixels. */
    private fun scale(screen: PhoneScreen) = if (max(screen.width, screen.height) > 1600) 0.5f else 1f

    private fun element(service: PhoneControlService, number: Int): PhoneElement =
        current(service).elements.getOrNull(number - 1)
            ?: throw PhoneToolFailure("No element [$number] on the latest screen. Call phone_screen to refresh the numbers.")

    private fun target(service: PhoneControlService, args: JSONObject): Pair<Point, String> {
        if (args.has("index")) return element(service, args.getInt("index")).let { it.tap to "[${it.number}] ${it.line}" }
        if (args.has("x") && args.has("y")) {
            val s = scale(current(service))
            return Point((args.getDouble("x") / s).roundToInt(), (args.getDouble("y") / s).roundToInt()) to "at (${args.get("x")}, ${args.get("y")})"
        }
        throw PhoneToolFailure("Pass an element number (index) or x and y.")
    }

    private suspend fun settle(service: PhoneControlService, maxMs: Long) {
        val start = SystemClock.uptimeMillis()
        if (maxMs <= 0) return
        delay(minOf(250, maxMs))
        while (SystemClock.uptimeMillis() - start < maxMs && SystemClock.uptimeMillis() - service.lastEvent < 400) delay(100)
    }

    private suspend fun act(service: PhoneControlService, settleMs: Long = 3000, block: suspend () -> String): JSONObject {
        val before = screen?.text
        val done = block()
        settle(service, settleMs)
        val after = read(service)
        val notes = mutableListOf(done)
        if (after.text == before) notes += "Note: the screen did not change after this action."
        recent.addLast(after.text.hashCode()); while (recent.size > 10) recent.removeFirst()
        if (recent.count { it == after.text.hashCode() } >= 3)
            notes += "Warning: this exact screen appeared 3 or more times in your recent actions. You may be going in circles; try a different approach."
        return text(notes.joinToString("\n") + "\n\n" + after.text)
    }

    private suspend fun gesture(service: PhoneControlService, path: Path, duration: Long) {
        val done = suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
            val sent = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) { if (continuation.isActive) continuation.resume(true) }
                override fun onCancelled(description: GestureDescription?) { if (continuation.isActive) continuation.resume(false) }
            }, null)
            if (!sent && continuation.isActive) continuation.resume(false)
        }
        if (!done) throw PhoneToolFailure("Android rejected or cancelled the gesture.")
    }

    private fun fieldText(node: AccessibilityNodeInfo) =
        if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) "" else node.text?.toString().orEmpty()

    private suspend fun type(service: PhoneControlService, args: JSONObject): JSONObject {
        if (!args.has("text")) throw PhoneToolFailure("Pass the text to type.")
        val value = args.getString("text")
        val clear = args.optBoolean("clear", true)
        val element = if (args.has("index")) element(service, args.getInt("index")) else null
        val node = element?.node ?: service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: throw PhoneToolFailure("No input is focused. Pass the element number of an input.")
        node.refresh()
        if (!node.isEditable) throw PhoneToolFailure("${element?.let { "[${it.number}]" } ?: "The focused element"} is not a text input.")
        return act(service, 2000) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val existing = fieldText(node)
            val wanted = if (clear) value else existing + value
            val set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, wanted) })
            if (!set) {
                // Some custom editors ignore SET_TEXT but accept a paste.
                service.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Opencode", value))
                if (clear) node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, existing.length)
                })
                if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE))
                    throw PhoneToolFailure("This field does not accept text from accessibility. Tap it and inspect with phone_screenshot.")
            }
            delay(200)
            node.refresh()
            val actual = fieldText(node)
            val where = element?.let { "[${it.number}]" } ?: "the focused input"
            val check = when {
                node.isPassword -> "Typed ${value.length} characters into password field $where (value hidden)."
                actual == wanted -> "Typed into $where; it now contains \"${actual.take(200)}\" (verified)."
                else -> "Warning: expected $where to contain \"${wanted.take(200)}\" but it contains \"${actual.take(200)}\"."
            }
            if (!args.optBoolean("submit", false)) check
            else if (Build.VERSION.SDK_INT >= 30 && node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) "$check Pressed Enter."
            else "$check Could not press Enter; tap the send or search button instead."
        }
    }

    private suspend fun scroll(service: PhoneControlService, args: JSONObject): JSONObject {
        val direction = args.optString("direction")
        if (direction !in listOf("down", "up", "left", "right")) throw PhoneToolFailure("direction must be down, up, left or right.")
        val screen = current(service)
        val chosen = if (args.has("index")) element(service, args.getInt("index"))
            else screen.elements.filter { it.scrollable }.maxByOrNull { it.bounds.width() * it.bounds.height() }
        val area = chosen?.bounds ?: Rect(0, screen.height / 5, screen.width, screen.height * 4 / 5)
        val amount = args.optDouble("amount", 0.6).coerceIn(0.2, 0.9).toFloat()
        // Keep clear of the system gesture areas at the screen edges.
        val top = max(area.top.toFloat(), screen.height * 0.08f)
        val bottom = minOf(area.bottom.toFloat(), screen.height * 0.92f)
        val left = max(area.left.toFloat(), screen.width * 0.05f)
        val right = minOf(area.right.toFloat(), screen.width * 0.95f)
        val cx = (left + right) / 2; val cy = (top + bottom) / 2
        val dy = (bottom - top) * amount / 2; val dx = (right - left) * amount / 2
        val path = Path().apply {
            when (direction) {
                "down" -> { moveTo(cx, cy + dy); lineTo(cx, cy - dy) }
                "up" -> { moveTo(cx, cy - dy); lineTo(cx, cy + dy) }
                "right" -> { moveTo(cx + dx, cy); lineTo(cx - dx, cy) }
                else -> { moveTo(cx - dx, cy); lineTo(cx + dx, cy) }
            }
        }
        val what = chosen?.let { " in [${it.number}]" } ?: ""
        return act(service) { gesture(service, path, 450); "Scrolled $direction$what." }
    }

    private suspend fun key(service: PhoneControlService, key: String): JSONObject {
        if (key == "enter") {
            val node = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: throw PhoneToolFailure("No input is focused.")
            if (Build.VERSION.SDK_INT < 30) throw PhoneToolFailure("Enter needs Android 11 or newer; tap the send or search button instead.")
            return act(service) {
                if (!node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) throw PhoneToolFailure("The focused input did not accept Enter.")
                "Pressed Enter."
            }
        }
        val action = when (key) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> throw PhoneToolFailure("Unknown key: $key")
        }
        return act(service) {
            if (!service.performGlobalAction(action)) throw PhoneToolFailure("Android rejected the $key key.")
            "Pressed $key."
        }
    }

    private fun apps(context: Context): List<App> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    private suspend fun openApp(service: PhoneControlService, query: String): JSONObject {
        if (query.isEmpty()) throw PhoneToolFailure("Pass the app name.")
        val apps = apps(service)
        val q = query.lowercase()
        val app = apps.firstOrNull { it.pkg.equals(query, true) } ?: apps.firstOrNull { it.label.equals(query, true) }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(q) } ?: apps.firstOrNull { it.label.lowercase().contains(q) }
            ?: apps.firstOrNull { it.pkg.lowercase().contains(q) }
            ?: throw PhoneToolFailure("No app matches \"$query\". Call phone_list_apps to see installed apps.")
        val intent = service.packageManager.getLaunchIntentForPackage(app.pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ?: throw PhoneToolFailure("${app.label} cannot be launched.")
        return act(service, 6000) { service.startActivity(intent); "Opened ${app.label} (${app.pkg})." }
    }

    private suspend fun screenshot(service: PhoneControlService, marks: Boolean): JSONObject {
        if (Build.VERSION.SDK_INT < 30) throw PhoneToolFailure("Screenshots need Android 11 or newer. Use phone_screen.")
        val current = read(service)
        val shot = capture(service)
        val s = scale(current)
        val scaled = if (s == 1f) shot else Bitmap.createScaledBitmap(shot, (shot.width * s).roundToInt(), (shot.height * s).roundToInt(), true)
        val image = if (scaled.isMutable) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true)
        if (marks) drawMarks(image, current, s)
        val jpeg = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 75, it) }.toByteArray()
        return JSONObject().put("isError", false).put("content", JSONArray()
            .put(JSONObject().put("type", "image").put("mimeType", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)))
            .put(JSONObject().put("type", "text").put("text",
                "Screenshot ${image.width}x${image.height}. Red boxes carry element numbers from the list below; prefer phone_tap with index. " +
                "For x,y parameters use pixels of this image.\n\n${current.text}")))
    }

    private suspend fun capture(service: PhoneControlService): Bitmap {
        if (Build.VERSION.SDK_INT < 30) throw PhoneToolFailure("Screenshots need Android 11 or newer.")
        repeat(3) {
            val result: Any = suspendCancellableCoroutine { continuation ->
                service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                        val hardware = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                        val copy = hardware?.copy(Bitmap.Config.ARGB_8888, true)
                        hardware?.recycle(); shot.hardwareBuffer.close()
                        if (continuation.isActive) continuation.resume(copy ?: -1)
                    }
                    override fun onFailure(code: Int) { if (continuation.isActive) continuation.resume(code) }
                })
            }
            when (result) {
                is Bitmap -> return result
                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> delay(400)
                // ERROR_TAKE_SCREENSHOT_SECURE_WINDOW (API 34): the app forbids screen capture.
                5 -> throw PhoneToolFailure("This app blocks screenshots (secure window). Use phone_screen.")
                else -> throw PhoneToolFailure("Screenshot failed (code $result).")
            }
        }
        throw PhoneToolFailure("Screenshot failed: Android rate limit. Try again.")
    }

    private fun drawMarks(bitmap: Bitmap, screen: PhoneScreen, scale: Float) {
        val canvas = Canvas(bitmap)
        val accent = Color.rgb(255, 45, 85)
        val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = accent }
        val fill = Paint().apply { color = accent }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
        for (element in screen.elements) {
            if (!element.actionable) continue
            val r = RectF(element.bounds.left * scale, element.bounds.top * scale, element.bounds.right * scale, element.bounds.bottom * scale)
            canvas.drawRect(r, box)
            val number = element.number.toString()
            canvas.drawRect(r.left, r.top, r.left + label.measureText(number) + 6, r.top + 18, fill)
            canvas.drawText(number, r.left + 3, r.top + 14, label)
        }
    }
}
