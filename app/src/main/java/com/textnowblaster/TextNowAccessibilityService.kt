package com.textnowblaster

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

class TextNowAccessibilityService : AccessibilityService() {

    companion object {
        var instance: TextNowAccessibilityService? = null
        private const val TAG = "TNBlaster"

        // TextNow package — covers both free and premium variants
        private val TEXTNOW_PACKAGES = setOf(
            "com.enflick.android.tngo",
            "com.enflick.android.TextNow"
        )

        // How long to wait for TextNow UI to settle after an action (ms)
        private const val UI_WAIT_MS = 2000L
        private const val SEND_WAIT_MS = 1500L
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var sendingJob: Job? = null
    private var isCurrentlySending = false

    // Callbacks set by MainActivity
    private var progressCallback: ((Int, Int) -> Unit)? = null
    private var completeCallback: ((Int, Int) -> Unit)? = null

    // State tracking for accessibility event handling
    private var waitingForWindow = false
    private var windowResumeCallback: (() -> Unit)? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        instance = this
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        serviceInfo = info
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        instance = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (waitingForWindow && event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            if (packageName in TEXTNOW_PACKAGES) {
                waitingForWindow = false
                val cb = windowResumeCallback
                windowResumeCallback = null
                cb?.invoke()
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun isSending() = isCurrentlySending

    fun stopSending() {
        sendingJob?.cancel()
        isCurrentlySending = false
        Log.d(TAG, "Sending stopped by user")
    }

    fun startSending(
        numbers: ArrayList<String>,
        message: String,
        delayMs: Long,
        onProgress: (Int, Int) -> Unit,
        onComplete: (Int, Int) -> Unit
    ) {
        if (isCurrentlySending) return

        progressCallback = onProgress
        completeCallback = onComplete
        isCurrentlySending = true

        sendingJob = serviceScope.launch {
            var sent = 0
            var failed = 0
            val total = numbers.size

            for ((index, number) in numbers.withIndex()) {
                if (!isActive) break

                Log.d(TAG, "Processing $number (${index + 1}/$total)")
                onProgress(index + 1, total)

                val success = sendMessageTo(number, message)
                if (success) sent++ else failed++

                Log.d(TAG, "Result for $number: ${if (success) "OK" else "FAILED"}")

                // Delay before next recipient (skip after last)
                if (isActive && index < numbers.size - 1) {
                    delay(delayMs)
                }
            }

            isCurrentlySending = false
            onComplete(sent, failed)
            Log.d(TAG, "All done. Sent: $sent, Failed: $failed")
        }
    }

    // ── Core Send Logic ───────────────────────────────────────────────────────

    private suspend fun sendMessageTo(number: String, message: String): Boolean {
        return try {
            // Step 1: Open TextNow new message screen directly via deep link
            openTextNowNewMessage(number)
            delay(UI_WAIT_MS)

            // Step 2: Find the message input field and type the message
            val messageSent = tryWithRetry(attempts = 3, delayBetween = 1000L) {
                fillAndSendMessage(message)
            }

            messageSent
        } catch (e: CancellationException) {
            throw e  // always re-throw cancellation
        } catch (e: Exception) {
            Log.e(TAG, "Error sending to $number: ${e.message}")
            false
        }
    }

    private fun openTextNowNewMessage(number: String) {
        // Try intent with phone number pre-filled
        val intent = Intent(Intent.ACTION_VIEW).apply {
            // TextNow responds to sms: URI scheme
            data = android.net.Uri.parse("sms:$number")
            // Explicitly target TextNow packages
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        // Try primary TextNow package first, fall back to secondary
        var launched = false
        for (pkg in TEXTNOW_PACKAGES) {
            try {
                intent.setPackage(pkg)
                applicationContext.startActivity(intent)
                launched = true
                break
            } catch (e: Exception) {
                Log.d(TAG, "Package $pkg not available: ${e.message}")
            }
        }

        if (!launched) {
            // Fallback: open TextNow home screen
            for (pkg in TEXTNOW_PACKAGES) {
                try {
                    val launchIntent = applicationContext.packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        applicationContext.startActivity(launchIntent)
                        break
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Launch fallback failed for $pkg: ${e.message}")
                }
            }
        }
    }

    private fun fillAndSendMessage(message: String): Boolean {
        val root = rootInActiveWindow ?: run {
            Log.d(TAG, "No active window root")
            return false
        }

        // Strategy 1: find by resource ID (most reliable, version-dependent)
        // Strategy 2: find by hint text / content description
        // Strategy 3: find any EditText that looks like a compose field

        val messageField = findMessageInputField(root)
        if (messageField == null) {
            Log.d(TAG, "Message input field not found")
            root.recycle()
            return false
        }

        // Set the text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        }
        val textSet = messageField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!textSet) {
            Log.d(TAG, "Failed to set text in message field")
            root.recycle()
            return false
        }

        Log.d(TAG, "Message text set successfully")

        // Small wait for UI to process the text
        Thread.sleep(SEND_WAIT_MS)

        // Refresh root after text change
        val refreshedRoot = rootInActiveWindow ?: run {
            root.recycle()
            return false
        }

        // Find and tap the send button
        val sendButton = findSendButton(refreshedRoot)
        if (sendButton == null) {
            Log.d(TAG, "Send button not found")
            root.recycle()
            refreshedRoot.recycle()
            return false
        }

        val clicked = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.d(TAG, "Send button click: $clicked")

        root.recycle()
        refreshedRoot.recycle()
        return clicked
    }

    // ── Node Finders ──────────────────────────────────────────────────────────

    private fun findMessageInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Common resource IDs used by TextNow across versions
        val resourceIds = listOf(
            "com.enflick.android.tngo:id/message_edit_text",
            "com.enflick.android.tngo:id/compose_message",
            "com.enflick.android.tngo:id/messageEditText",
            "com.enflick.android.TextNow:id/message_edit_text",
            "com.enflick.android.TextNow:id/compose_message",
            "com.enflick.android.TextNow:id/messageEditText"
        )

        for (id in resourceIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                Log.d(TAG, "Found message field by ID: $id")
                return nodes[0]
            }
        }

        // Fallback: find by hint text
        val hintTexts = listOf("message", "type a message", "sms", "text message", "compose")
        val editTexts = findAllEditTexts(root)
        for (node in editTexts) {
            val hint = node.hintText?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (hintTexts.any { hint.contains(it) || desc.contains(it) }) {
                Log.d(TAG, "Found message field by hint/desc: hint='$hint' desc='$desc'")
                return node
            }
        }

        // Last resort: return the last/largest EditText (usually the compose box)
        return editTexts.lastOrNull()?.also {
            Log.d(TAG, "Using last EditText as fallback message field")
        }
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Common resource IDs for send button
        val resourceIds = listOf(
            "com.enflick.android.tngo:id/send_button",
            "com.enflick.android.tngo:id/sendButton",
            "com.enflick.android.tngo:id/btn_send",
            "com.enflick.android.TextNow:id/send_button",
            "com.enflick.android.TextNow:id/sendButton",
            "com.enflick.android.TextNow:id/btn_send"
        )

        for (id in resourceIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                Log.d(TAG, "Found send button by ID: $id")
                return nodes[0]
            }
        }

        // Fallback: find by text or content description
        val sendLabels = listOf("send", "send message")
        for (label in sendLabels) {
            val byText = root.findAccessibilityNodeInfosByText(label)
            val clickable = byText.firstOrNull { it.isClickable }
            if (clickable != null) {
                Log.d(TAG, "Found send button by text: $label")
                return clickable
            }
        }

        // Last resort: find any clickable ImageButton near the bottom of screen
        return findClickableImageButton(root)?.also {
            Log.d(TAG, "Using clickable ImageButton as send button fallback")
        }
    }

    private fun findAllEditTexts(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        traverseNodes(root) { node ->
            if (node.className?.contains("EditText") == true && node.isEditable) {
                result.add(node)
            }
        }
        return result
    }

    private fun findClickableImageButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        traverseNodes(root) { node ->
            if (found == null &&
                node.className?.contains("ImageButton") == true &&
                node.isClickable) {
                found = node
            }
        }
        return found
    }

    private fun traverseNodes(node: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        action(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                traverseNodes(child, action)
            }
        }
    }

    // ── Retry Helper ──────────────────────────────────────────────────────────

    private suspend fun tryWithRetry(
        attempts: Int,
        delayBetween: Long,
        block: () -> Boolean
    ): Boolean {
        for (attempt in 1..attempts) {
            if (!isActive) return false
            val result = block()
            if (result) return true
            if (attempt < attempts) {
                Log.d(TAG, "Attempt $attempt failed, retrying in ${delayBetween}ms")
                delay(delayBetween)
            }
        }
        return false
    }
}
