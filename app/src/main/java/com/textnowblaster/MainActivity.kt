package com.textnowblaster

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.textnowblaster.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val phoneNumbers = mutableListOf<String>()

    companion object {
        const val REQUEST_PICK_FILE = 1001
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
        // Sync button states with service
        val service = TextNowAccessibilityService.instance
        if (service != null && service.isSending()) {
            setRunningState(true)
            observeServiceProgress()
        } else {
            setRunningState(false)
        }
    }

    // ── UI Setup ──────────────────────────────────────────────────────────────

    private fun setupDelaySeekBar() {
        // SeekBar 0-55 maps to 5-60 seconds
        binding.tvDelayValue.text = "${getDelaySeconds()} sec"
        binding.seekDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvDelayValue.text = "${getDelaySeconds()} sec"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupButtons() {
        binding.btnLoadFile.setOnClickListener { openFilePicker() }
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_FILE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri -> loadNumbersFromUri(uri) }
        }
    }

    private fun loadNumbersFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val lines = inputStream?.bufferedReader()?.readLines() ?: emptyList()
            inputStream?.close()

            phoneNumbers.clear()
            for (line in lines) {
                val cleaned = line.trim()
                    .replace(Regex("[\\s\\-().+]"), "")  // strip formatting
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

    // ── Start / Stop ──────────────────────────────────────────────────────────

    private fun onStartClicked() {
        if (phoneNumbers.isEmpty()) {
            Toast.makeText(this, "Please load a phone numbers file first.", Toast.LENGTH_SHORT).show()
            return
        }

        val message = binding.etMessage.text.toString().trim()
        if (message.isEmpty()) {
            Toast.makeText(this, "Please enter a message to send.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isAccessibilityEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Accessibility Service Required")
                .setMessage("TextNow Blaster needs the Accessibility Service enabled to automate TextNow.\n\nTap 'Open Settings', find 'TextNow Blaster Automation', and enable it.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Confirm Send")
            .setMessage("Send the following message to ${phoneNumbers.size} numbers?\n\n\"$message\"\n\nDelay: ${getDelaySeconds()} seconds between each.")
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

    private fun observeServiceProgress() {
        val service = TextNowAccessibilityService.instance ?: return
        service.startSending(
            numbers = ArrayList(phoneNumbers),
            message = "",
            delayMs = 0,
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
                }
            }
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun getDelaySeconds(): Int = binding.seekDelay.progress + 5

    private fun setRunningState(running: Boolean) {
        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
        binding.btnLoadFile.isEnabled = !running
        binding.seekDelay.isEnabled = !running
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
