package com.textnowblaster

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.textnowblaster.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val phoneNumbers = mutableListOf<String>()
    private var selectedImageUri: Uri? = null

    companion object {
        const val REQUEST_PICK_FILE = 1001
        const val REQUEST_PICK_IMAGE = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDelaySeekBar()
        setupButtons()
        updateAccessibilityStatus()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        val service = TextNowAccessibilityService.instance
        if (service != null && service.isSending()) {
            setRunningState(true)
        } else {
            setRunningState(false)
        }
    }

    private fun setupDelaySeekBar() {
        updateDelayDisplay(getDelaySeconds())

        binding.seekDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val seconds = progress + 5
                    binding.etDelayInput.setText(seconds.toString())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.etDelayInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val typed = s.toString().toIntOrNull() ?: return
                val clamped = typed.coerceIn(5, 60)
                binding.seekDelay.progress = clamped - 5
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun updateDelayDisplay(seconds: Int) {
        binding.etDelayInput.setText(seconds.toString())
        binding.seekDelay.progress = seconds - 5
    }

    private fun getDelaySeconds(): Int {
        return binding.etDelayInput.text.toString().toIntOrNull()?.coerceIn(5, 60)
            ?: (binding.seekDelay.progress + 5)
    }

    private fun setupButtons() {
        binding.btnLoadFile.setOnClickListener { openFilePicker() }
        binding.btnPickImage.setOnClickListener { openImagePicker() }
        binding.btnClearImage.setOnClickListener { clearImage() }
        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnStop.setOnClickListener { onStopClicked() }
        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    // ── File Picker ───────────────────────────────────────────────────────────

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "text/plain"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Select phone numbers file"), REQUEST_PICK_FILE)
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Select image"), REQUEST_PICK_IMAGE)
    }

    private fun clearImage() {
        selectedImageUri = null
        binding.tvImageStatus.text = "No image selected"
        binding.tvImageStatus.setTextColor(0xFF888888.toInt())
        binding.ivPreview.visibility = android.view.View.GONE
        binding.ivPreview.setImageDrawable(null)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when {
            requestCode == REQUEST_PICK_FILE && resultCode == Activity.RESULT_OK -> {
                data?.data?.let { uri -> loadNumbersFromUri(uri) }
            }
            requestCode == REQUEST_PICK_IMAGE && resultCode == Activity.RESULT_OK -> {
                data?.data?.let { uri -> loadImage(uri) }
            }
        }
    }

    private fun loadNumbersFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val lines = inputStream?.bufferedReader()?.readLines() ?: emptyList()
            inputStream?.close()

            phoneNumbers.clear()
            for (line in lines) {
                val cleaned = line.trim().replace(Regex("[\\s\\-().+]"), "")
                if (cleaned.isNotEmpty() && cleaned.all { it.isDigit() } && cleaned.length >= 7) {
                    phoneNumbers.add(cleaned)
                }
            }

            val skipped = lines.count { it.isNotBlank() } - phoneNumbers.size
            binding.tvNumberCount.text = "✅  ${phoneNumbers.size} valid numbers loaded" +
                    if (skipped > 0) "  ($skipped skipped)" else ""
            binding.tvNumbersList.text = phoneNumbers.take(50).joinToString("\n") +
                    if (phoneNumbers.size > 50) "\n... and ${phoneNumbers.size - 50} more" else ""

            if (phoneNumbers.isEmpty()) {
                Toast.makeText(this, "No valid numbers found in file.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadImage(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // Not all URIs support persistable permissions
        }

        selectedImageUri = uri

        binding.ivPreview.visibility = android.view.View.VISIBLE
        binding.ivPreview.setImageURI(uri)

        val fileName = getFileName(uri) ?: uri.lastPathSegment ?: "image"
        binding.tvImageStatus.text = "✅  $fileName"
        binding.tvImageStatus.setTextColor(0xFF2E7D32.toInt())
    }

    private fun getFileName(uri: Uri): String? {
        return try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) it.getString(idx) else null
                } else null
            }
        } catch (e: Exception) { null }
    }

    // ── Start / Stop ──────────────────────────────────────────────────────────

    private fun onStartClicked() {
        if (phoneNumbers.isEmpty()) {
            Toast.makeText(this, "Please load a phone numbers file first.", Toast.LENGTH_SHORT).show()
            return
        }

        val message = binding.etMessage.text.toString().trim()
        if (message.isEmpty() && selectedImageUri == null) {
            Toast.makeText(this, "Please enter a message or pick an image.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isAccessibilityEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Accessibility Service Required")
                .setMessage("TextNow Blaster needs the Accessibility Service enabled.\n\nTap 'Open Settings', find 'TextNow Blaster Automation', and enable it.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val imageNote = if (selectedImageUri != null) "\n+ image attached" else "\n(no image)"
        AlertDialog.Builder(this)
            .setTitle("Confirm Send")
            .setMessage("Send to ${phoneNumbers.size} numbers?\n\n\"$message\"$imageNote\n\nDelay: ${getDelaySeconds()} sec between each.")
            .setPositiveButton("Send") { _, _ -> startSending(message) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startSending(message: String) {
        val service = TextNowAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "Accessibility service not running. Please enable it.", Toast.LENGTH_LONG).show()
            return
        }

        setRunningState(true)
        binding.layoutProgress.visibility = android.view.View.VISIBLE
        binding.tvProgress.text = "Starting..."
        binding.progressBar.progress = 0

        service.startSending(
            numbers = ArrayList(phoneNumbers),
            message = message,
            imageUri = selectedImageUri,
            delayMs = getDelaySeconds() * 1000L,
            onProgress = { current, total ->
                runOnUiThread {
                    binding.tvProgress.text = "Sending $current / $total"
                    binding.progressBar.progress = ((current.toFloat() / total) * 100).toInt()
                }
            },
            onComplete = { sent, failed ->
                runOnUiThread {
                    setRunningState(false)
                    binding.tvProgress.text = "Done! ✅  Sent: $sent  ❌  Failed: $failed"
                    Toast.makeText(this, "Finished! Sent: $sent, Failed: $failed", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun onStopClicked() {
        TextNowAccessibilityService.instance?.stopSending()
        setRunningState(false)
        binding.tvProgress.text = "Stopped by user."
        Toast.makeText(this, "Sending stopped.", Toast.LENGTH_SHORT).show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun setRunningState(running: Boolean) {
        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
        binding.btnLoadFile.isEnabled = !running
        binding.btnPickImage.isEnabled = !running
        binding.btnClearImage.isEnabled = !running
        binding.seekDelay.isEnabled = !running
        binding.etDelayInput.isEnabled = !running
        if (running) binding.layoutProgress.visibility = android.view.View.VISIBLE
    }

    private fun isAccessibilityEnabled(): Boolean {
        val serviceName = "$packageName/${TextNowAccessibilityService::class.java.name}"
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            enabled?.contains(serviceName) == true
        } catch (e: Exception) { false }
    }

    private fun updateAccessibilityStatus() {
        if (isAccessibilityEnabled()) {
            binding.tvStatus.text = "✅  Accessibility service: ACTIVE"
            binding.tvStatus.setTextColor(0xFF2E7D32.toInt())
        } else {
            binding.tvStatus.text = "⚠️  Accessibility service: DISABLED — tap button below to enable"
            binding.tvStatus.setTextColor(0xFFC62828.toInt())
        }
    }
}
