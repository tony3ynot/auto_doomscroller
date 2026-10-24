package com.autodoomscroller.adapter

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Logs or saves an accessibility node tree. Used to reverse-engineer target app layouts. */
object NodeTreeDumper {
    private const val TAG = "NodeTree"
    private const val MAX_DEPTH = 40

    fun dump(root: AccessibilityNodeInfo) {
        render(root).forEach { Log.d(TAG, it) }
    }

    /**
     * Writes the tree to [dir] so rare layouts seen during normal use (no logcat running) can be
     * pulled later. The file contains on-screen text from the user's own feed; debug builds only.
     */
    fun save(root: AccessibilityNodeInfo, dir: File, label: String): File {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "$stamp-$label.txt").apply { writeText(render(root).joinToString("\n")) }
    }

    private fun render(root: AccessibilityNodeInfo): List<String> = buildList {
        add("---- ${root.packageName} ----")
        val ownPrefix = "${root.packageName}:id/"
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH) return
            add(describe(node, depth, ownPrefix))
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
    }

    /** IDs from the app's own resource package are shortened; others are shown in full. */
    private fun describe(node: AccessibilityNodeInfo, depth: Int, ownPrefix: String) = buildString {
        append("  ".repeat(depth))
        append(node.className?.toString()?.substringAfterLast('.'))
        node.viewIdResourceName?.let { append(" id=").append(it.removePrefix(ownPrefix)) }
        node.text?.let { append(" text=\"").append(it.take(60)).append('"') }
        node.contentDescription?.let { append(" desc=\"").append(it.take(60)).append('"') }
        if (node.isClickable) append(" [clickable]")
        if (node.isScrollable) append(" [scrollable]")
        if (node.isSelected) append(" [selected]")
        if (node.isCheckable) append(if (node.isCheckedCompat) " [checked]" else " [unchecked]")
        if (!node.isVisibleToUser) append(" [offscreen]")
        node.rangeInfo?.let { append(" range=${it.current}/${it.min}..${it.max}") }
        node.collectionInfo?.let { append(" collection=${it.rowCount}x${it.columnCount}") }
        node.collectionItemInfo?.let { append(" item=${it.rowIndex},${it.columnIndex}") }
        val bounds = Rect().also(node::getBoundsInScreen)
        append(" @${bounds.left},${bounds.top}-${bounds.right},${bounds.bottom}")
    }
}
