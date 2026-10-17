package com.autodoomscroller.adapter

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/** Logs an accessibility node tree. Used to reverse-engineer target app layouts. */
object NodeTreeDumper {
    private const val TAG = "NodeTree"
    private const val MAX_DEPTH = 40

    fun dump(root: AccessibilityNodeInfo) {
        Log.d(TAG, "---- ${root.packageName} ----")
        dumpNode(root, depth = 0)
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > MAX_DEPTH) return
        Log.d(TAG, describe(node, depth))
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dumpNode(it, depth + 1) }
        }
    }

    private fun describe(node: AccessibilityNodeInfo, depth: Int) = buildString {
        append("  ".repeat(depth))
        append(node.className?.toString()?.substringAfterLast('.'))
        node.viewIdResourceName?.let { append(" id=").append(it.substringAfter(":id/")) }
        node.text?.let { append(" text=\"").append(it.take(60)).append('"') }
        node.contentDescription?.let { append(" desc=\"").append(it.take(60)).append('"') }
        if (node.isClickable) append(" [clickable]")
        if (node.isSelected) append(" [selected]")
    }
}
