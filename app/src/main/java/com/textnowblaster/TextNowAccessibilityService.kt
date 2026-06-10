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

        private val TEXTNOW_PACKAGES = setOf(
            "com.enflick.android.tngo",
            "com.enflick.android.TextNow"
        )

        private const val UI_WAIT_MS = 2000L
        private const val SEND_WAIT_MS = 1500L
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var sendingJob: Job? = null
    private var isCurrentlySending = false

    private var progressCallback: ((Int, Int) -> Unit)? = null
    private var completeCallback: ((Int, Int) -> Unit)? = null

    private var waitingForWindow = false
    private var windowResumeCallback: (() -> Unit)? = null

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

                if (isActive && index < numbers.size - 1) {
                    delay(delayMs)
                }
            }

            isCurrentlySending = false
            onComplete(sent, failed)
            Log.d(TAG, "All done. Sent: $sent, Failed: $failed")
        }
    }

    private suspend fun sendMessageTo(number: String, message: String): Boolean {
        return try {
            openTextNowNewMessage(number)
            delay(UI_WAIT_MS)

            val messageSent = tryWithRetry(attempts = 3, delayBetween = 1000L) {
                fillAndSendMessage(message)
            }

            messageSent
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error sending to $number: ${e.message}")
            false
        }
    }

    private fun openTextNowNewMessage(number: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse("sms:$number")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

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

        val messageField = findMessageInputField(root)
        if (messageField == null) {
            Log.d(TAG, "Message input field not found")
            root.recycle()
            return false
        }

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
        Thread.sleep(SEND_WAIT_MS)

        val refreshedRoot = rootInActiveWindow ?: run {
            root.recycle()
            return false
        }

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

    private fun findMessageInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
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

        val hintTexts = listOf("message", "type a message", "sms", "text message", "compose")
        val editTexts = findAllEditTexts(root)
        for (node in editTexts) {
            val hint = node.hintText?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (hintTexts.any { hint.contains(it) || desc.contains(it) }) {
                Log.d(TAG, "Found message field by hint/desc: hint='$hint' desc='$desc'")
                return node
            }
        }

        return editTexts.lastOrNull()?.also {
            Log.d(TAG, "Using last EditText as fallback message field")
        }
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
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

        val sendLabels = listOf("send", "send message")
        for (label in sendLabels) {
            val byText = root.findAccessibilityNodeInfosByText(label)
            val clickable = byText.firstOrNull { it.isClickable }
            if (clickable != null) {
                Log.d(TAG, "Found send button by text: $label")
                return clickable
            }
        }

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

    private suspend fun tryWithRetry(
        attempts: Int,
        delayBetween: Long,
        block: () -> Boolean
    ): Boolean {
        for (attempt in 1..attempts) {
            if (!currentCoroutineContext().isActive) return false
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
