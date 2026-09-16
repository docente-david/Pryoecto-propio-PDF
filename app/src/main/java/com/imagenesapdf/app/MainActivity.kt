package com.imagenesapdf.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.imagenesapdf.app.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity(), OptionsSheet.Listener {

    private lateinit var binding: ActivityMainBinding
    private val images = mutableListOf<Uri>()
    private lateinit var adapter: ImageAdapter
    private var lastFileName: String? = null
    private var pendingOptions: PdfOptions? = null
    private var generateJob: Job? = null

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)
    ) { uris -> addImages(uris) }

    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val options = pendingOptions ?: return@registerForActivityResult
        pendingOptions = null
        if (granted) {
            generatePdf(options)
        } else {
            Snackbar.make(binding.root, R.string.permission_needed, Snackbar.LENGTH_LONG)
                .setAction(R.string.permission_settings) {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    )
                }.show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        savedInstanceState?.let { BundleCompat.getParcelableArrayList(it, STATE_IMAGES, Uri::class.java) }
            ?.let { images.addAll(it) }
        lastFileName = savedInstanceState?.getString(STATE_NAME)

        setupRecycler()

        binding.selectButton.setOnClickListener { launchPicker() }
        binding.addButton.setOnClickListener { launchPicker() }
        binding.createButton.setOnClickListener {
            if (images.isEmpty()) launchPicker()
            else OptionsSheet.newInstance(lastFileName).show(supportFragmentManager, OptionsSheet.TAG)
        }

        if (savedInstanceState == null) handleShareIntent(intent)
        updateUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelableArrayList(STATE_IMAGES, ArrayList(images))
        outState.putString(STATE_NAME, lastFileName)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_clear).isVisible = images.isNotEmpty()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_clear -> {
            val count = images.size
            images.clear()
            adapter.notifyItemRangeRemoved(0, count)
            updateUi()
            true
        }
        R.id.action_about -> {
            val version = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (_: Exception) { "" }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_name)
                .setMessage(getString(R.string.about_message, version))
                .setPositiveButton(R.string.result_done, null)
                .show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ---------------------------------------------------------------- Lista de imágenes

    private fun setupRecycler() {
        adapter = ImageAdapter(images) { pos -> removeImage(pos) }
        binding.recycler.layoutManager = GridLayoutManager(this, 3)
        binding.recycler.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                adapter.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                adapter.refreshBadges()
            }

            override fun isLongPressDragEnabled(): Boolean = true
        })
        touchHelper.attachToRecyclerView(binding.recycler)
    }

    private fun launchPicker() {
        val remaining = MAX_IMAGES - images.size
        if (remaining <= 0) {
            Snackbar.make(binding.root, getString(R.string.images_limit, MAX_IMAGES), Snackbar.LENGTH_SHORT).show()
            return
        }
        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val room = MAX_IMAGES - images.size
        val toAdd = uris.filter { it !in images }.take(room)
        if (toAdd.isEmpty()) {
            if (uris.size > room) {
                Snackbar.make(binding.root, getString(R.string.images_limit, MAX_IMAGES), Snackbar.LENGTH_SHORT).show()
            }
            return
        }
        val start = images.size
        images.addAll(toAdd)
        adapter.notifyItemRangeInserted(start, toAdd.size)
        binding.recycler.smoothScrollToPosition(images.size - 1)
        updateUi()
        val msg = if (toAdd.size == 1) getString(R.string.image_added) else getString(R.string.images_added, toAdd.size)
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
    }

    private fun removeImage(position: Int) {
        if (position !in images.indices) return
        images.removeAt(position)
        adapter.notifyItemRemoved(position)
        adapter.refreshBadges()
        updateUi()
    }

    private fun handleShareIntent(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) addImages(uris)
    }

    private fun updateUi() {
        val hasImages = images.isNotEmpty()
        binding.emptyState.isVisible = !hasImages
        binding.recycler.isVisible = hasImages
        binding.countText.isVisible = hasImages
        binding.hintText.isVisible = hasImages
        binding.bottomBar.isVisible = hasImages
        binding.countText.text =
            if (images.size == 1) getString(R.string.images_count_one) else getString(R.string.images_count_many, images.size)
        invalidateOptionsMenu()
    }

    // ---------------------------------------------------------------- Generación del PDF

    override fun onOptionsConfirmed(options: PdfOptions) {
        lastFileName = options.fileName
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingOptions = options
            requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        generatePdf(options)
    }

    private fun generatePdf(options: PdfOptions) {
        if (generateJob?.isActive == true) return
        val uris = images.toList()
        val total = uris.size
        showProgress(true)
        binding.progressBar.isIndeterminate = true
        binding.progressText.text = getString(R.string.progress_preparing)

        generateJob = lifecycleScope.launch {
            var target: PdfSaver.Target? = null
            try {
                target = withContext(Dispatchers.IO) { PdfSaver.create(this@MainActivity, options.fileName) }
                target.stream.use { stream ->
                    ImagePdfWriter(this@MainActivity).write(uris, options, stream) { done, n ->
                        withContext(Dispatchers.Main) {
                            binding.progressBar.isIndeterminate = false
                            binding.progressBar.max = n
                            binding.progressBar.setProgressCompat(done, true)
                            binding.progressText.text =
                                if (done < n) getString(R.string.progress_page, done + 1, n) else getString(R.string.progress_saving)
                        }
                    }
                }
                val finalName = withContext(Dispatchers.IO) { PdfSaver.finish(this@MainActivity, target, true) }
                    ?: PdfSaver.sanitize(options.fileName)
                val size = withContext(Dispatchers.IO) { PdfSaver.querySize(this@MainActivity, target.uri, target.file) }
                showProgress(false)
                showResult(target.uri, finalName, size, total)
            } catch (e: CancellationException) {
                target?.let { withContext(Dispatchers.IO) { PdfSaver.finish(this@MainActivity, it, false) } }
                throw e
            } catch (e: ImagePdfWriter.ImageReadException) {
                target?.let { withContext(Dispatchers.IO) { PdfSaver.finish(this@MainActivity, it, false) } }
                showProgress(false)
                Snackbar.make(binding.root, getString(R.string.error_image, e.index + 1), Snackbar.LENGTH_LONG).show()
            } catch (e: Exception) {
                target?.let { runCatching { withContext(Dispatchers.IO) { PdfSaver.finish(this@MainActivity, it, false) } } }
                showProgress(false)
                val detail = e.localizedMessage ?: e.javaClass.simpleName
                val msg = if (target == null) getString(R.string.error_storage) else getString(R.string.error_generic, detail)
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showProgress(show: Boolean) {
        binding.progressOverlay.isVisible = show
        binding.addButton.isEnabled = !show
        binding.createButton.isEnabled = !show
    }

    private fun showResult(uri: Uri, name: String, sizeBytes: Long, pages: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.result_title)
            .setMessage(getString(R.string.result_message, name, formatSize(sizeBytes), pages))
            .setPositiveButton(R.string.result_share) { _, _ -> sharePdf(uri, name) }
            .setNeutralButton(R.string.result_open) { _, _ -> openPdf(uri) }
            .setNegativeButton(R.string.result_done, null)
            .show()
    }

    private fun sharePdf(uri: Uri, name: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_title)))
    }

    private fun openPdf(uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(view)
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(binding.root, R.string.error_no_viewer, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
        else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    companion object {
        private const val MAX_IMAGES = 60
        private const val STATE_IMAGES = "images"
        private const val STATE_NAME = "name"
    }
}
