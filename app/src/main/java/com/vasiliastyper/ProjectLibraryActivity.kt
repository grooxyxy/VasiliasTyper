package com.vasiliastyper

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import com.vasiliastyper.engine.DEFAULT_PROJECT_FOLDER
import com.vasiliastyper.engine.ProjectHistoryManager
import com.vasiliastyper.engine.ProjectRecord
import com.vasiliastyper.ui.StudioLibrary

/**
 * Project library rebuilt in Jetpack Compose.
 * Storage and navigation intentionally stay on the mature ProjectHistoryManager API.
 */
class ProjectLibraryActivity : AppCompatActivity() {
    private val records = mutableStateOf<List<ProjectRecord>>(emptyList())
    private val folders = mutableStateOf<List<String>>(emptyList())
    private val selectedFolder = mutableStateOf<String?>(null)
    private val searchQuery = mutableStateOf("")
    private val selectedProjectIds = mutableStateOf<Set<String>>(emptySet())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StudioLibrary(
                records = records.value,
                folders = folders.value,
                selectedFolder = selectedFolder.value,
                query = searchQuery.value,
                onQuery = { searchQuery.value = it },
                onFolder = { selectedFolder.value = it },
                onBack = ::finish,
                onCreateFolder = ::createFolder,
                onRenameFolder = ::renameCurrentFolder,
                onOpen = ::openProject,
                onActions = ::showProjectActions,
                selectedProjectIds = selectedProjectIds.value,
                onToggleSelection = ::toggleProjectSelection,
                onSelectAllVisible = ::selectAllVisibleProjects,
                onDeleteSelected = ::confirmDeleteSelectedProjects,
                onClearSelection = { selectedProjectIds.value = emptySet() }
            )
        }
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        records.value = ProjectHistoryManager.load(this)
        selectedProjectIds.value = selectedProjectIds.value.intersect(records.value.mapTo(mutableSetOf()) { it.id })
        folders.value = ProjectHistoryManager.folders(this)
        val current = selectedFolder.value
        if (current != null && current != DEFAULT_PROJECT_FOLDER && current !in folders.value) {
            selectedFolder.value = null
        }
    }

    private fun openProject(record: ProjectRecord) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(HomeActivity.EXTRA_REC_ID, record.id)
            record.sourceUri?.let { putExtra(HomeActivity.EXTRA_OPEN_URI, it) } ?: run {
                putExtra(HomeActivity.EXTRA_NEW_W, record.width)
                putExtra(HomeActivity.EXTRA_NEW_H, record.height)
                putExtra(HomeActivity.EXTRA_NEW_NAME, record.name)
            }
        })
    }

    private fun toggleProjectSelection(record: ProjectRecord) {
        selectedProjectIds.value = selectedProjectIds.value.toMutableSet().apply {
            if (!add(record.id)) remove(record.id)
        }
    }

    private fun selectAllVisibleProjects(visible: List<ProjectRecord>) {
        val visibleIds = visible.mapTo(mutableSetOf()) { it.id }
        selectedProjectIds.value = if (visibleIds.isNotEmpty() && visibleIds.all { it in selectedProjectIds.value }) {
            selectedProjectIds.value - visibleIds
        } else {
            selectedProjectIds.value + visibleIds
        }
    }

    private fun confirmDeleteSelectedProjects() {
        val ids = selectedProjectIds.value
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Hapus ${ids.size} proyek?")
            .setMessage("Semua proyek terpilih akan dihapus dari riwayat. Tindakan ini tidak dapat dibatalkan.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                val removed = ProjectHistoryManager.removeMany(this, ids)
                selectedProjectIds.value = emptySet()
                reload()
                Toast.makeText(this, "$removed proyek berhasil dihapus", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showProjectActions(record: ProjectRecord) {
        AlertDialog.Builder(this)
            .setTitle(record.name)
            .setItems(arrayOf("Buka proyek", "Pindahkan ke koleksi", "Hapus dari riwayat")) { _, which ->
                when (which) {
                    0 -> openProject(record)
                    1 -> chooseDestination(record)
                    2 -> confirmDelete(record)
                }
            }
            .show()
    }

    private fun createFolder() = requestFolderName("Koleksi baru", "") { name ->
        ProjectHistoryManager.createFolder(this, name)
        selectedFolder.value = name
        reload()
    }

    private fun renameCurrentFolder() {
        val old = selectedFolder.value ?: return
        if (old == DEFAULT_PROJECT_FOLDER) return
        requestFolderName("Ubah nama koleksi", old) { name ->
            if (ProjectHistoryManager.renameFolder(this, old, name)) {
                selectedFolder.value = name
                reload()
            }
        }
    }

    private fun requestFolderName(title: String, initial: String, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            hint = "Nama koleksi"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = ProjectHistoryManager.normalizeFolderName(input.text.toString())
                if (name == DEFAULT_PROJECT_FOLDER) {
                    input.error = "Nama koleksi tidak boleh kosong"
                } else {
                    onSave(name)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun chooseDestination(record: ProjectRecord) {
        val destinations = listOf(DEFAULT_PROJECT_FOLDER) + folders.value
        AlertDialog.Builder(this)
            .setTitle("Pindahkan ${record.name}")
            .setItems(destinations.toTypedArray()) { _, index ->
                ProjectHistoryManager.moveProject(this, record.id, destinations[index])
                reload()
            }
            .setNeutralButton("Koleksi baru") { _, _ ->
                requestFolderName("Koleksi baru", "") { name ->
                    ProjectHistoryManager.createFolder(this, name)
                    ProjectHistoryManager.moveProject(this, record.id, name)
                    reload()
                }
            }
            .show()
    }

    private fun confirmDelete(record: ProjectRecord) {
        AlertDialog.Builder(this)
            .setTitle("Hapus dari riwayat?")
            .setMessage("File proyek tetap aman; hanya entri riwayat yang dihapus.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                ProjectHistoryManager.remove(this, record.id)
                reload()
                Toast.makeText(this, "Proyek dihapus dari riwayat", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
