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

    binding.etDelayInput.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) {
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
