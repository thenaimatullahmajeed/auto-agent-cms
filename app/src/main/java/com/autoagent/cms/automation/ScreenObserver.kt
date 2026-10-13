package com.autoagent.cms.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.autoagent.cms.security.ScreenField
import com.autoagent.cms.security.ScreenSnapshot
import com.autoagent.cms.security.SensitiveScreenGuard
import java.security.MessageDigest
import java.util.Locale

class ScreenObserver(
    private val service: AccessibilityService,
    private val guard: SensitiveScreenGuard,
    private val maxNodes: Int = 500,
    private val maxDepth: Int = 30
) {
    data class Observation(
        val screen: ObservedScreen?,
        val blocked: Boolean,
        val reason: String
    )

    fun observe(): Observation {
        val root = try {
            service.rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return Observation(null, true, "Active window unavailable.")

        return try {
            val packageName = root.packageName?.toString()
            val elements = ArrayList<UiElement>()
            val fields = ArrayList<ScreenField>()
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(root to 0)
            var visited = 0

            while (queue.isNotEmpty() && visited < maxNodes) {
                val (node, depth) = queue.removeFirst()
                visited++
                val bounds = Rect()
                try {
                    node.getBoundsInScreen(bounds)
                } catch (_: Exception) {
                    bounds.setEmpty()
                }
                val text = safeString { node.text?.toString() }
                val description = safeString { node.contentDescription?.toString() }
                val viewId = safeString { node.viewIdResourceName }
                val className = safeString { node.className?.toString() }
                val isPassword = safeBoolean { node.isPassword }
                fields.add(
                    ScreenField(
                        text = text,
                        contentDescription = description,
                        className = className,
                        viewId = viewId,
                        isPassword = isPassword
                    )
                )
                if (bounds.width() > 0 && bounds.height() > 0) {
                    elements.add(
                        UiElement(
                            index = elements.size,
                            text = text,
                            description = description,
                            viewId = viewId,
                            className = className,
                            clickable = safeBoolean { node.isClickable },
                            editable = safeBoolean { node.isEditable },
                            scrollable = safeBoolean { node.isScrollable },
                            boundsLeft = bounds.left,
                            boundsTop = bounds.top,
                            boundsRight = bounds.right,
                            boundsBottom = bounds.bottom,
                            password = isPassword
                        )
                    )
                }
                if (depth < maxDepth) {
                    val childCount = safeInt { node.childCount }.coerceAtMost(100)
                    for (i in 0 until childCount) {
                        val child = try {
                            node.getChild(i)
                        } catch (_: Exception) {
                            null
                        }
                        if (child != null) queue.add(child to depth + 1)
                    }
                }
            }

            val snapshot = ScreenSnapshot(packageName = packageName, fields = fields)
            val guardResult = guard.inspect(snapshot)
            if (guardResult.blocked) {
                Observation(screen = null, blocked = true, reason = guardResult.reason)
            } else {
                Observation(
                    screen = ObservedScreen(
                        packageName = packageName,
                        elements = elements,
                        screenHash = stableHash(packageName, elements)
                    ),
                    blocked = false,
                    reason = "Screen observed."
                )
            }
        } catch (_: Exception) {
            Observation(null, true, "Accessibility traversal failed.")
        }
    }

    private fun stableHash(packageName: String?, elements: List<UiElement>): String {
        val canonical = buildString {
            append(packageName.orEmpty())
            append('\n')
            elements.forEach { element ->
                append(normalize(element.text))
                append('|')
                append(normalize(element.description))
                append('|')
                append(element.viewId.orEmpty())
                append('|')
                append(element.className.orEmpty())
                append('|')
                append(element.clickable)
                append('|')
                append(element.editable)
                append('|')
                append(element.scrollable)
                append('\n')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun normalize(value: String?): String = value.orEmpty()
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
        .take(200)

    private inline fun <T> safeString(block: () -> T?): T? =
        try { block() } catch (_: Exception) { null }

    private inline fun safeBoolean(block: () -> Boolean): Boolean =
        try { block() } catch (_: Exception) { false }

    private inline fun safeInt(block: () -> Int): Int =
        try { block() } catch (_: Exception) { 0 }
}
