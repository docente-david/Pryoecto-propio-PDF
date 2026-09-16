package com.imagenesapdf.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.imagenesapdf.app.databinding.SheetOptionsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Hoja inferior con nombre de archivo, tamaño de página, orientación y calidad. */
class OptionsSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onOptionsConfirmed(options: PdfOptions)
    }

    private var _binding: SheetOptionsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Nombre: el último que se escribió en esta sesión o uno con fecha y hora.
        val defaultName = arguments?.getString(ARG_NAME)?.takeIf { it.isNotBlank() }
            ?: "Documento_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        binding.fileNameInput.setText(defaultName)
        binding.fileNameInput.setSelection(defaultName.length)
        binding.fileNameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { confirm(); true } else false
        }

        // Restaurar últimas preferencias
        when (prefs.getString(KEY_SIZE, PageSize.A4.name)) {
            PageSize.LETTER.name -> binding.chipLetter.isChecked = true
            PageSize.FIT.name -> binding.chipFit.isChecked = true
            else -> binding.chipA4.isChecked = true
        }
        when (prefs.getString(KEY_ORIENTATION, Orientation.AUTO.name)) {
            Orientation.PORTRAIT.name -> binding.chipPortrait.isChecked = true
            Orientation.LANDSCAPE.name -> binding.chipLandscape.isChecked = true
            else -> binding.chipAuto.isChecked = true
        }
        binding.qualitySlider.value = prefs.getInt(KEY_QUALITY, 85).coerceIn(30, 100).toFloat()

        updateOrientationVisibility()
        updateQualityLabel(binding.qualitySlider.value.toInt())

        binding.pageSizeGroup.setOnCheckedStateChangeListener { _, _ -> updateOrientationVisibility() }
        binding.qualitySlider.addOnChangeListener { _, value, _ -> updateQualityLabel(value.toInt()) }
        binding.cancelButton.setOnClickListener { dismiss() }
        binding.generateButton.setOnClickListener { confirm() }
    }

    private fun updateOrientationVisibility() {
        val fit = binding.chipFit.isChecked
        binding.orientationLabel.isVisible = !fit
        binding.orientationGroup.isVisible = !fit
    }

    private fun updateQualityLabel(q: Int) {
        binding.qualityValue.text = getString(R.string.quality_value, q)
        binding.qualityHint.text = getString(
            when {
                q >= 90 -> R.string.quality_hint_high
                q >= 65 -> R.string.quality_hint_medium
                else -> R.string.quality_hint_low
            }
        )
    }

    private fun confirm() {
        val name = binding.fileNameInput.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.fileNameLayout.error = getString(R.string.file_name_error)
            return
        }
        binding.fileNameLayout.error = null

        val size = when {
            binding.chipLetter.isChecked -> PageSize.LETTER
            binding.chipFit.isChecked -> PageSize.FIT
            else -> PageSize.A4
        }
        val orientation = when {
            binding.chipPortrait.isChecked -> Orientation.PORTRAIT
            binding.chipLandscape.isChecked -> Orientation.LANDSCAPE
            else -> Orientation.AUTO
        }
        val quality = binding.qualitySlider.value.toInt()

        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_SIZE, size.name)
            putString(KEY_ORIENTATION, orientation.name)
            putInt(KEY_QUALITY, quality)
        }

        (activity as? Listener)?.onOptionsConfirmed(PdfOptions(name, size, orientation, quality))
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "OptionsSheet"
        private const val ARG_NAME = "name"
        private const val PREFS = "pdf_options"
        private const val KEY_SIZE = "page_size"
        private const val KEY_ORIENTATION = "orientation"
        private const val KEY_QUALITY = "quality"

        fun newInstance(lastName: String?): OptionsSheet = OptionsSheet().apply {
            arguments = Bundle().apply { putString(ARG_NAME, lastName) }
        }
    }
}
