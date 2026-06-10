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
        private const val ATTACH_WAIT_MS = 3000L
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
            openTextNowNewMessage(number)
            delay(UI_WAIT_MS)

            tryWithRetry(attempts = 3, delayBetween = 1000L) {
                fillMessageAndSend(message, imageUri)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error sending to $number: ${e.message}")
            false
        }
    }

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

    private fun fillMessageAndSend(message: String, imageUri: Uri?): Boolean {
        val root = rootInActiveWindow ?: return false

        // Step 1: type message text first (while number is still in place)
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
            if (!textSet) {
                Log.d(TAG, "Failed to set message text")
                root.recycle()
                return false
            }
            Thread.sleep(SEND_WAIT_MS)
        }

        root.recycle()

        // Step 2: attach image if provided (after message is typed)
        if (imageUri != null) {
            if (!attachImage(imageUri)) {
                Log.d(TAG, "Image attach failed, continuing without image")
            }
            Thread.sleep(ATTACH_WAIT_MS)
        }

        // Step 3: tap send
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

    private fun attachImage(imageUri: Uri): Boolean {
        // Grant TextNow read permission on the URI
        for (pkg in TEXTNOW_PACKAGES) {
            try {
                applicationContext.grantUriPermission(pkg, imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { }
        }

        val root = rootInActiveWindow ?: return false
        val attachButton = findAttachButton(root)
        root.recycle()

        if (attachButton == null) {
            Log.d(TAG, "Attach button not found")
            return false
        }

        attachButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(ATTACH_WAIT_MS)

        // Try tapping Gallery/Photos in the picker that appears
        return tapGalleryInPicker()
    }

    private fun tapGalleryInPicker(): Boolean {
        Thread.sleep(1500L)
        val root = rootInActiveWindow ?: return false

        val labels = listOf("gallery", "photos", "image", "photo library", "files")
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            val node = nodes.firstOrNull { it.isClickable }
                ?: nodes.firstOrNull()?.let { findClickableParent(it) }
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                root.recycle()
                Log.d(TAG, "Tapped picker option: $label")
                return true
            }
        }

        root.recycle()
        Log.d(TAG, "No gallery option found in picker")
        return false
    }

    // ── Node Finders ──────────────────────────────────────────────────────────

    private fun findAttachButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val resourceIds = listOf(
            "com.enflick.android.tngo:id/attach_button",
            "com.enflick.android.tngo:id/attachButton",
            "com.enflick.android.tngo:id/btn_attach",
            "com.enflick.android.tngo:id/media_button",
            "com.enflick.android.TextNow:id/attach_button",
            "com.enflick.android.TextNow:id/attachButton",
            "com.enflick.android.TextNow:id/btn_attach",
            "com.enflick.android.TextNow:id/media_button"
        )
        for (id in resourceIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) return nodes[0]
        }

        val labels = listOf("attach", "attachment", "add attachment", "media", "paperclip", "image")
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            val node = nodes.firstOrNull { it.isClickable }
            if (node != null) return node
        }

        var found: AccessibilityNodeInfo? = null
        traverseNodes(root) { node ->
            if (found == null) {
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                if ((desc.contains("attach") || desc.contains("media") ||
                            desc.contains("image") || desc.contains("photo")) && node.isClickable) {
                    found = node
                }
            }
        }
        return found
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
