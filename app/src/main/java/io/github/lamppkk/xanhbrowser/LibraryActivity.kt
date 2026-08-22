package io.github.lamppkk.xanhbrowser

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lamppkk.xanhbrowser.databinding.ActivityLibraryBinding
import io.github.lamppkk.xanhbrowser.sync.PlacesMutationPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class LibraryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLibraryBinding
    private lateinit var repository: BrowserRepository
    private lateinit var mode: Mode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        repository = BrowserRepository(this)
        mode = Mode.from(intent.getStringExtra(EXTRA_MODE))
        supportActionBar?.title = getString(mode.title)

        val adapter = LibraryAdapter(
            onOpen = { row ->
                if (mode == Mode.DOWNLOADS) {
                    openDownload(row.id)
                } else {
                    setResult(Activity.RESULT_OK, Intent().setData(row.url.toUri()))
                    finish()
                }
            },
            onEdit = if (mode == Mode.BOOKMARKS) {
                { row -> showBookmarkRename(row) }
            } else null,
            onDelete = { row ->
                lifecycleScope.launch {
                    try {
                        when (mode) {
                            Mode.HISTORY -> repository.deleteHistory(row.id)
                            Mode.BOOKMARKS -> repository.deleteBookmark(row.id)
                            Mode.DOWNLOADS -> {
                                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).remove(row.id)
                                repository.deleteDownload(row.id)
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        Toast.makeText(
                            this@LibraryActivity,
                            R.string.browser_data_delete_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
        binding.items.layoutManager = LinearLayoutManager(this)
        binding.items.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val rows = when (mode) {
                    Mode.HISTORY -> repository.history.map { entries ->
                        entries.map { LibraryRow(it.id, it.url, it.title, formatTime(it.visitedAt)) }
                    }
                    Mode.BOOKMARKS -> repository.bookmarks.map { entries ->
                        entries.map { LibraryRow(it.id, it.url, it.title, it.url) }
                    }
                    Mode.DOWNLOADS -> repository.downloads.map { entries ->
                        entries.map {
                            LibraryRow(it.id, it.url, it.fileName, downloadStatus(it))
                        }
                    }
                }
                rows.collect(adapter::submitList)
            }
        }
    }

    private fun showBookmarkRename(row: LibraryRow) {
        val input = EditText(this).apply {
            setText(row.title)
            hint = getString(R.string.bookmark_title)
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(PlacesMutationPolicy.MAX_TITLE_BYTES))
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_bookmark)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rename_bookmark) { _, _ ->
                lifecycleScope.launch {
                    try {
                        repository.renameBookmark(row.id, input.text.toString())
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        Toast.makeText(
                            this@LibraryActivity,
                            R.string.bookmark_rename_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun formatTime(value: Long): String = DateFormat.getDateTimeInstance().format(Date(value))

    private fun downloadStatus(record: DownloadRecord): String {
        val label = when (record.status) {
            "successful" -> getString(R.string.download_successful)
            "failed" -> getString(R.string.download_failed)
            "running" -> getString(R.string.download_running)
            else -> getString(R.string.download_queued)
        }
        return if (record.reason != 0) "$label (${record.reason})" else label
    }

    private fun openDownload(id: Long) {
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val uri = manager.getUriForDownloadedFile(id)
        if (uri == null) return
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, manager.getMimeTypeForDownloadedFile(id) ?: "application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (view.resolveActivity(packageManager) != null) startActivity(view)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    enum class Mode(val value: String, val title: Int) {
        HISTORY("history", R.string.history),
        BOOKMARKS("bookmarks", R.string.bookmarks),
        DOWNLOADS("downloads", R.string.downloads);

        companion object {
            fun from(value: String?) = entries.firstOrNull { it.value == value } ?: HISTORY
        }
    }

    companion object {
        const val EXTRA_MODE = "library_mode"
    }
}
