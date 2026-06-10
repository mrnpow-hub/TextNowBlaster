package com.textnowblaster

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
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

        private const val UI_WAIT_MS = 2500L
        private const val SEND_WAIT_MS = 1500L
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var sendingJob: Job? = null
    private var isCurrentlySending = false

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

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    fun isSending() = isCurrentlySending

    fun stopSending() {
        sendingJob?.cancel()
        isCurrentlySending = false
    }

    fun startSending(
        numbers: ArrayList<String>,
        message: String,
        imageUri: Uri?,
        delayMs: Long,
        onProgress: (Int, Int) -> Unit,
        onComplete: (Int, Int) -> Unit
    ) {
        if (isCurrentlySending) return
        isCurrentlySending = true

        sendingJob = serviceScope.launch {
            var sent = 0
            var failed = 0
            val total = numbers.size

            for ((index, number) in numbers.withIndex()) {
                if (!isActive) break

                onProgress(index + 1, total)
                Log.d(TAG, "Sending to $number (${index + 1}/$total)")

                val success = sendMessageTo(number, message, imageUri)
                if (success) sent++ else failed++

                if (isActive && index < numbers.size - 1) {
                    delay(delayMs)
                }
            }

            isCurrentlySending = false
            onComplete(sent, failed)
        }
    }

    private suspend fun sendMessageTo(number: String, message: String, imageUri: Uri?): Boolean {
        return try {
            if (imageUri != null) {
                // Open TextNow with image via ACTION_SEND, then fill number and message after
                openTextNowWithImage(imageUri)
                delay(UI_WAIT_MS)
                tryWithRetry(attempts = 3, delayBetween = 1000L) {
                    fillNumberAndMessage(number, message)
                }
            } else {
                // No image — use sms: intent to open correct conversation
                openTextNowNewMessage(number)
                delay(UI_WAIT_MS)
                tryWithRetry(attempts = 3, delayBetween = 1000L) {
                    fillMessageAndSend(message)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error sending to $number: ${e.message}")
            false
        }
    }

    // ── Open TextNow ──────────────────────────────────────────────────────────

    private fun openTextNowNewMessage(number: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("sms:$number")
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
                Log.d(TAG, "Package $pkg not available")
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
                } catch (e: Exception) { }
            }
        }
    }

    private fun openTextNowWithImage(imageUri: Uri) {
        for (pkg in TEXTNOW_PACKAGES) {
            try {
                applicationContext.grantUriPermission(pkg, imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { }
        }

        for (pkg in TEXTNOW_PACKAGES) {
            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_STREAM, imageUri)
                    setPackage(pkg)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                applicationContext.startActivity(intent)
                Log.d(TAG, "Launched $pkg with image")
                return
            } catch (e: Exception) {
                Log.d(TAG, "Could not launch $pkg with image: ${e.message}")
            }
        }
    }

    // ── Send Logic ────────────────────────────────────────────────────────────

    private fun fillNumberAndMessage(number: String, message: String): Boolean {
        val root = rootInActiveWindow ?: return false

        // Step 1: fill the number field
        val numberField = findNumberInputField(root)
        if (numberField != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, number)
            }
            val numberSet = numberField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "Number field set: $numberSet")
            Thread.sleep(800L)

            // Tap the number field to confirm/select the suggestion that appears
            numberField.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(1000L)
        } else {
            Log.d(TAG, "Number field not found")
            root.recycle()
            return false
        }

        root.recycle()

        // Step 2: fill the message field
        if (message.isNotEmpty()) {
            val refreshedRoot = rootInActiveWindow ?: return false
            val messageField = findMessageInputField(refreshedRoot)
            if (messageField == null) {
                Log.d(TAG, "Message field not found")
                refreshedRoot.recycle()
                return false
            }

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
            }
            val textSet = messageField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            refreshedRoot.recycle()
            if (!textSet) {
                Log.d(TAG, "Failed to set message text")
                return false
            }
            Thread.sleep(SEND_WAIT_MS)
        }

        // Step 3: tap send
        val finalRoot = rootInActiveWindow ?: return false
        val sendButton = findSendButton(finalRoot)
        finalRoot.recycle()

        if (sendButton == null) {
            Log.d(TAG, "Send button not found")
            return false
        }

        val clicked = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.d(TAG, "Send clicked: $clicked")
        return clicked
    }

    private fun fillMessageAndSend(message: String): Boolean {
        val root = rootInActiveWindow ?: return false

        // Check number field
        val numberField = findNumberInputField(root)
        if (numberField != null) {
            val currentText = numberField.text?.toString() ?: ""
            Log.d(TAG, "Number field text: '$currentText'")
            if (currentText.isBlank()) {
                Log.d(TAG, "Number field is empty — retrying")
                root.recycle()
                return false
            }
        } else {
            Log.d(TAG, "Number field not found — assuming already in conversation")
        }

        // Type the message
        if (message.isNotEmpty()) {
            val messageField = findMessageInputField(root)
            if (messageField == null) {
                Log.d(TAG, "Message field not found")
                root.recycle()
                return false
            }

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
            }
            val textSet = messageField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            root.recycle()
            if (!textSet) {
                Log.d(TAG, "Failed to set message text")
                return false
            }
            Thread.sleep(SEND_WAIT_MS)
        } else {
            root.recycle()
        }

        // Tap send
        val refreshedRoot = rootInActiveWindow ?: return false
        val sendButton = findSendButton(refreshedRoot)
        refreshedRoot.recycle()

        if (sendButton == null) {
            Log.d(TAG, "Send button not found")
            return false
        }

        val clicked = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.d(TAG, "Send clicked: $clicked")
        return clicked
    }

    // ── Node Finders ──────────────────────────────────────────────────────────

    private fun findNumberInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val resourceIds = listOf(
            "com.enflick.android.tngo:id/recipient_edit_text",
            "com.enflick.android.tngo:id/recipientEditText",
            "com.enflick.android.tngo:id/to_field",
            "com.enflick.android.tngo:id/toField",
            "com.enflick.android.tngo:id/contacts_edit_text",
            "com.enflick.android.TextNow:id/recipient_edit_text",
            "com.enflick.android.TextNow:id/recipientEditText",
            "com.enflick.android.TextNow:id/to_field",
            "com.enflick.android.TextNow:id/toField",
            "com.enflick.android.TextNow:id/contacts_edit_text"
        )
        for (id in resourceIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) return nodes[0]
        }

        val hintTexts = listOf("to", "recipient", "enter name", "enter number", "send to")
        val editTexts = findAllEditTexts(root)
        for (node in editTexts) {
            val hint = node.hintText?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (hintTexts.any { hint.contains(it) || desc.contains(it) }) return node
        }

        return editTexts.firstOrNull()
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
            if (nodes.isNotEmpty()) return nodes[0]
        }

        val hintTexts = listOf("message", "type a message", "sms", "text message", "compose")
        val editTexts = findAllEditTexts(root)
        for (node in editTexts) {
            val hint = node.hintText?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (hintTexts.any { hint.contains(it) || desc.contains(it) }) return node
        }

        return editTexts.lastOrNull()
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
            if (nodes.isNotEmpty()) return nodes[0]
        }

        val sendLabels = listOf("send", "send message")
        for (label in sendLabels) {
            val byText = root.findAccessibilityNodeInfosByText(label)
            val clickable = byText.firstOrNull { it.isClickable }
            if (clickable != null) return clickable
        }

        return findClickableImageButton(root)
    }

    private fun findAllEditTexts(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        traverseNodes(root) { node ->
            if (node.className?.contains("EditText") == true && node.isEditable) result.add(node)
        }
        return result
    }

    private fun findClickableImageButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        traverseNodes(root) { node ->
            if (found == null && node.className?.contains("ImageButton") == true && node.isClickable) {
                found = node
            }
        }
        return found
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        repeat(5) {
            if (current?.isClickable == true) return current
            current = current?.parent
        }
        return null
    }

    private fun traverseNodes(node: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        action(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { traverseNodes(it, action) }
        }
    }

    private suspend fun tryWithRetry(attempts: Int, delayBetween: Long, block: () -> Boolean): Boolean {
        for (attempt in 1..attempts) {
            if (!currentCoroutineContext().isActive) return false
            if (block()) return true
            if (attempt < attempts) delay(delayBetween)
        }
        return false
    }
}
