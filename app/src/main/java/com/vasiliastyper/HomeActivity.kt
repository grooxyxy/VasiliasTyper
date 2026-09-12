package com.vasiliastyper

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import com.vasiliastyper.ui.StudioHome
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vasiliastyper.adapter.RecentProjectAdapter
import com.vasiliastyper.engine.BitmapSafety
import com.vasiliastyper.engine.ModelDownloader
import com.vasiliastyper.engine.YoloV8mBubbleDetector
import com.vasiliastyper.engine.AgnesAiSettings
import com.vasiliastyper.engine.AiChatSettings
import com.vasiliastyper.engine.GeminiSettings
import com.vasiliastyper.engine.ProjectHistoryManager
import com.vasiliastyper.engine.ProjectRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NEW_W = "new_w"
        const val EXTRA_NEW_H = "new_h"
        const val EXTRA_NEW_NAME = "new_name"
        const val EXTRA_OPEN_URI = "open_uri"
        const val EXTRA_REC_ID = "rec_id"
    }

    private lateinit var rvRecent: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var recentAdapter: RecentProjectAdapter
    private var allRecentProjects: List<ProjectRecord> = emptyList()
    private val composeProjects = mutableStateOf<List<ProjectRecord>>(emptyList())

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_URI, uri.toString())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContent {
                StudioHome(
                    projects = composeProjects.value,
                    onNewProject = ::showNewProjectDialog,
                    onOpenImage = ::pickImage,
                    onOpenProject = ::openProject,
                    onProjectInfo = ::showProjectInfo,
                    onProjects = ::showProjectLibrary,
                    onLearn = { startActivity(BlogActivity.intent(this)) },
                    onAi = { startActivity(Intent(this, AiChatActivity::class.java)) },
                    onSettings = ::showAiSettingsDialog,
                    onSearch = ::showProjectSearch
                )
            }
            loadHistoryAsync()
        } catch (t: Throwable) {
            showStartupFallback(t)
        }
    }

    override fun onResume() {
        super.onResume()
        loadHistoryAsync()
    }

    private fun showStartupFallback(error: Throwable) {
        try {
            setContentView(android.R.layout.simple_list_item_1)
            val tv = findViewById<TextView>(android.R.id.text1)
            tv.text = "VasiliasTyper gagal dimuat.\n\n${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            tv.setPadding(48, 48, 48, 48)
        } catch (_: Throwable) {
        }
    }

    private fun setupFooter() {
        findViewById<LinearLayout>(R.id.btnFooterProjects)?.setOnClickListener {
            showProjectLibrary()
        }
        findViewById<TextView>(R.id.btnTelegram)?.setOnClickListener {
            openUrl("https://t.me/AnergiaPV")
        }
        findViewById<TextView>(R.id.btnDiscord)?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Discord")
                .setMessage("Username Discord: enigmadom_\n\nBuka Discord untuk menghubungi.")
                .setPositiveButton("Buka Discord") { _, _ -> openUrl("https://discord.com/users/enigmadom_") }
                .setNegativeButton("Tutup", null)
                .show()
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Tautan tidak dapat dibuka.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showHomeMenu() {
        val view = layoutInflater.inflate(R.layout.dialog_home_menu, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        fun closeThen(action: () -> Unit) {
            dialog.dismiss()
            action()
        }

        view.findViewById<LinearLayout>(R.id.menuProjects).setOnClickListener {
            closeThen(::showProjectLibrary)
        }
        view.findViewById<LinearLayout>(R.id.menuLearning).setOnClickListener {
            closeThen { startActivity(BlogActivity.intent(this)) }
        }
        view.findViewById<LinearLayout>(R.id.menuTelegram).setOnClickListener {
            closeThen { openUrl("https://t.me/AnergiaPV") }
        }
        view.findViewById<LinearLayout>(R.id.menuDiscord).setOnClickListener {
            closeThen { openUrl("https://discord.com/users/enigmadom_") }
        }
        view.findViewById<LinearLayout>(R.id.menuAiConnection).setOnClickListener {
            closeThen { startActivity(Intent(this, AiChatActivity::class.java)) }
        }

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog.show()
    }

    private fun setupRecentList() {
        recentAdapter = RecentProjectAdapter(
            mutableListOf(),
            onClick = { rec -> openProject(rec) },
            onInfo = { rec -> showProjectInfo(rec) },
            onLongClick = { rec -> promptMoveProject(listOf(rec)) }
        )
        rvRecent.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvRecent.adapter = recentAdapter
        checkEmpty()
    }

    private fun loadHistoryAsync() {
        lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) {
                runCatching { ProjectHistoryManager.load(this@HomeActivity).toMutableList() }
                    .getOrElse { mutableListOf() }
            }
            if (!isFinishing && !isDestroyed) {
                allRecentProjects = records
                composeProjects.value = records
                if (this@HomeActivity::recentAdapter.isInitialized) {
                    recentAdapter.updateList(records)
                    checkEmpty()
                    updateDynamicHeader()
                }
            }
        }
    }

    private fun checkEmpty() {
        val empty = recentAdapter.itemCount == 0
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        rvRecent.visibility = if (empty) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.tvRecentCount)?.text = recentAdapter.itemCount.toString()
    }

    private fun updateDynamicHeader() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when (hour) {
            in 5..10 -> "Selamat pagi"
            in 11..14 -> "Selamat siang"
            in 15..18 -> "Selamat sore"
            else -> "Selamat malam"
        }
        val dayLabel = SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(Date())
        findViewById<TextView>(R.id.tvHomeGreeting)?.text = "$greeting · $dayLabel"

        val projectCount = allRecentProjects.size
        findViewById<TextView>(R.id.tvHeroStatus)?.text = when {
            projectCount == 0 -> "STUDIO SIAP"
            projectCount == 1 -> "1 PROYEK TERSIMPAN"
            else -> "$projectCount PROYEK TERSIMPAN"
        }
    }

    private fun showProjectSearch() {
        if (allRecentProjects.isEmpty()) {
            Toast.makeText(this, "Belum ada proyek untuk dicari.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(this)
        val content = dialogPanel().apply {
            setPadding(dp(22), dp(20), dp(22), dp(18))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(dialogTitle("Cari proyek"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(dialogCloseButton { dialog.dismiss() })
        content.addView(header)
        content.addView(dialogSubtitle("Temukan proyek berdasarkan nama tanpa meninggalkan beranda.").apply {
            setPadding(0, dp(5), 0, dp(16))
        })

        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.dialog_field_bg)
            setPadding(dp(12), 0, dp(8), 0)
        }
        val searchIcon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(Color.parseColor("#A78BFA"))
        }
        searchBox.addView(searchIcon, LinearLayout.LayoutParams(dp(22), dp(22)))
        val input = EditText(this).apply {
            hint = "Ketik nama proyek..."
            setHintTextColor(Color.parseColor("#777382"))
            setTextColor(Color.parseColor("#F2F2F4"))
            textSize = 15f
            setSingleLine(true)
            background = null
            setPadding(dp(12), 0, dp(6), 0)
        }
        searchBox.addView(input, LinearLayout.LayoutParams(0, dp(54), 1f))
        val clear = TextView(this).apply {
            text = "×"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9895A4"))
            visibility = View.INVISIBLE
            setOnClickListener { input.text.clear() }
        }
        searchBox.addView(clear, LinearLayout.LayoutParams(dp(36), dp(44)))
        content.addView(searchBox)

        val resultStatus = dialogSubtitle("${allRecentProjects.size} proyek tersedia").apply {
            setPadding(dp(2), dp(12), 0, dp(14))
        }
        content.addView(resultStatus)

        fun matchingProjects(): List<ProjectRecord> {
            val query = input.text.toString().trim()
            return if (query.isBlank()) allRecentProjects
            else allRecentProjects.filter { it.name.contains(query, ignoreCase = true) }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val matches = matchingProjects()
                clear.visibility = if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
                resultStatus.text = if (s.isNullOrBlank()) {
                    "${allRecentProjects.size} proyek tersedia"
                } else if (matches.isEmpty()) {
                    "Tidak ada proyek yang cocok"
                } else {
                    "${matches.size} proyek cocok"
                }
                resultStatus.setTextColor(Color.parseColor(if (matches.isEmpty()) "#F87171" else "#9895A4"))
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(dialogTextButton("Tampilkan semua") {
            recentAdapter.updateList(allRecentProjects)
            checkEmpty()
            dialog.dismiss()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        actions.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        actions.addView(dialogPrimaryButton("Cari") {
            val query = input.text.toString().trim()
            if (query.isBlank()) {
                input.error = "Masukkan nama proyek"
                return@dialogPrimaryButton
            }
            val filtered = matchingProjects()
            recentAdapter.updateList(filtered)
            checkEmpty()
            dialog.dismiss()
            Toast.makeText(
                this,
                if (filtered.isEmpty()) "Proyek tidak ditemukan" else "${filtered.size} proyek ditemukan",
                Toast.LENGTH_SHORT
            ).show()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        content.addView(actions)

        showHomeDialog(dialog, content)
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun confirmClearHistory() {
        if (allRecentProjects.isEmpty()) {
            Toast.makeText(this, "Belum ada riwayat proyek.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Bersihkan riwayat?")
            .setMessage("Semua proyek dari daftar homepage akan dihapus. Folder tetap tersimpan.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Bersihkan") { _, _ ->
                ProjectHistoryManager.clear(this)
                loadHistoryAsync()
            }
            .show()
    }

    private fun showProjectLibrary() {
        startActivity(Intent(this, ProjectLibraryActivity::class.java))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedBackground(color: String, radius: Int, strokeColor: String? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(radius).toFloat()
            strokeColor?.let { setStroke(dp(1), Color.parseColor(it)) }
        }

    private fun dialogPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground("#18171E", 22, "#35313F")
    }

    private fun dialogTitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 21f
        setTextColor(Color.parseColor("#F2F2F4"))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun dialogSubtitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(Color.parseColor("#9895A4"))
    }

    private fun dialogCloseButton(action: () -> Unit): TextView = TextView(this).apply {
        text = "×"
        textSize = 23f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#C4C4C8"))
        background = roundedBackground("#24232C", 12)
        contentDescription = "Tutup"
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
    }

    private fun statChip(value: String, label: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = roundedBackground("#211D2D", 14, "#3E315C")
        addView(TextView(this@HomeActivity).apply {
            text = value
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#B69CFF"))
            gravity = Gravity.CENTER
        })
        addView(dialogSubtitle(label).apply { gravity = Gravity.CENTER })
    }

    private fun libraryRow(badge: String, title: String, subtitle: String, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            minimumHeight = dp(66)
            background = getDrawable(R.drawable.home_menu_item_bg)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }

            addView(TextView(this@HomeActivity).apply {
                text = badge
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#C4B5FD"))
                background = roundedBackground("#2B2142", 12, "#4E3A70")
            }, LinearLayout.LayoutParams(dp(42), dp(42)))

            val labels = LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                addView(TextView(this@HomeActivity).apply {
                    text = title
                    textSize = 14.5f
                    setTextColor(Color.parseColor("#F2F2F4"))
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                })
                addView(dialogSubtitle(subtitle).apply { setPadding(0, dp(3), 0, 0) })
            }
            addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@HomeActivity).apply {
                text = "›"
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#8B5CF6"))
            }, LinearLayout.LayoutParams(dp(30), dp(42)))
        }

    private fun dialogTextButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#C4B5FD"))
        background = roundedBackground("#24212D", 12, "#3A3446")
        setOnClickListener { action() }
    }

    private fun dialogPrimaryButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = roundedBackground("#7347E8", 12, "#9B7AF5")
        setOnClickListener { action() }
    }

    private fun showHomeDialog(
        dialog: Dialog,
        content: View,
        maxHeightRatio: Float = 0.62f
    ) {
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val maxHeight = (resources.displayMetrics.heightPixels * maxHeightRatio).toInt()
            setLayout(resources.displayMetrics.widthPixels - dp(32), maxHeight)
            attributes = attributes.apply { dimAmount = 0.72f }
        }
    }

    private fun showProjectsInFolder(folder: String?) {
        val projects = ProjectHistoryManager.projectsInFolder(this, folder)
        if (projects.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(folder ?: "Semua Proyek")
                .setMessage("Belum ada proyek di lokasi ini.")
                .setNegativeButton("Tutup", null)
                .setPositiveButton("Kelola Folder") { _, _ -> showProjectLibrary() }
                .show()
            return
        }
        val labels = projects.map { record ->
            val location = ProjectHistoryManager.normalizeFolderName(record.folder)
            "${record.name}\n${record.width}×${record.height} · $location"
        }
        AlertDialog.Builder(this)
            .setTitle(folder ?: "Semua Proyek")
            .setItems(labels.toTypedArray()) { _, index -> openProject(projects[index]) }
            .setNeutralButton("Pindahkan") { _, _ -> promptMoveProject(projects) }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun promptCreateFolder() {
        promptForFolderName("Buat Folder", "Nama folder") { name ->
            if (ProjectHistoryManager.createFolder(this, name)) {
                Toast.makeText(this, "Folder dibuat", Toast.LENGTH_SHORT).show()
                showProjectLibrary()
            }
        }
    }

    private fun promptRenameFolder() {
        val folders = ProjectHistoryManager.folders(this)
        if (folders.isEmpty()) {
            Toast.makeText(this, "Belum ada folder yang dapat di-rename.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pilih Folder")
            .setItems(folders.toTypedArray()) { _, index ->
                val oldName = folders[index]
                promptForFolderName("Rename Folder", "Nama folder baru", oldName) { newName ->
                    if (ProjectHistoryManager.renameFolder(this, oldName, newName)) {
                        loadHistoryAsync()
                        Toast.makeText(this, "Folder berhasil di-rename", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun promptMoveProject(source: List<ProjectRecord> = allRecentProjects) {
        if (source.isEmpty()) {
            Toast.makeText(this, "Belum ada proyek yang dapat dipindahkan.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pilih Proyek")
            .setItems(source.map { it.name }.toTypedArray()) { _, projectIndex ->
                val project = source[projectIndex]
                val destinations = listOf(com.vasiliastyper.engine.DEFAULT_PROJECT_FOLDER) +
                    ProjectHistoryManager.folders(this)
                AlertDialog.Builder(this)
                    .setTitle("Pindahkan ke Folder")
                    .setItems(destinations.toTypedArray()) { _, folderIndex ->
                        ProjectHistoryManager.moveProject(this, project.id, destinations[folderIndex])
                        loadHistoryAsync()
                        Toast.makeText(this, "Proyek dipindahkan", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("Folder Baru") { _, _ ->
                        promptForFolderName("Folder Baru", "Nama folder") { newFolder ->
                            ProjectHistoryManager.createFolder(this, newFolder)
                            ProjectHistoryManager.moveProject(this, project.id, newFolder)
                            loadHistoryAsync()
                        }
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun promptForFolderName(
        title: String,
        hint: String,
        initial: String = "",
        onValidName: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            this.hint = hint
            setSingleLine(true)
            setText(initial)
            setSelection(text.length)
            setPadding(36, 12, 36, 12)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized = ProjectHistoryManager.normalizeFolderName(input.text.toString())
                if (normalized == com.vasiliastyper.engine.DEFAULT_PROJECT_FOLDER) {
                    input.error = "Nama folder tidak boleh kosong"
                } else {
                    onValidName(normalized)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun openProject(rec: ProjectRecord) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_REC_ID, rec.id)
            if (rec.sourceUri != null) {
                putExtra(EXTRA_OPEN_URI, rec.sourceUri)
            } else {
                putExtra(EXTRA_NEW_W, rec.width)
                putExtra(EXTRA_NEW_H, rec.height)
                putExtra(EXTRA_NEW_NAME, rec.name)
            }
        }
        startActivity(intent)
        finish()
    }

    private fun showProjectInfo(rec: ProjectRecord) {
        val view = layoutInflater.inflate(R.layout.dialog_project_info, null)
        val dateFormat = SimpleDateFormat("EEEE, d MMMM yyyy · HH:mm", Locale.getDefault())
        val createdAt = rec.createdAtMs.takeIf { it > 0L } ?: rec.dateMs
        val editedAt = rec.lastEditedAtMs.takeIf { it > 0L } ?: rec.dateMs
        val folder = ProjectHistoryManager.normalizeFolderName(rec.folder)

        view.findViewById<TextView>(R.id.tvInfoProjectName).text = rec.name.ifBlank { "Tanpa Nama" }
        view.findViewById<TextView>(R.id.tvInfoProjectLocation).text = "Folder · $folder"
        view.findViewById<TextView>(R.id.tvInfoCanvasSize).text = "Dimensi kanvas  ${rec.width} × ${rec.height} px"
        view.findViewById<TextView>(R.id.tvInfoStorageSize).text = "Ukuran data  ${formatStorageSize(estimateProjectBytes(rec))}"
        view.findViewById<TextView>(R.id.tvInfoProjectType).text =
            "Jenis proyek  ${if (rec.type == "image") "Gambar" else "Kanvas kosong"}"
        view.findViewById<TextView>(R.id.tvInfoCreatedAt).text = "Dibuat  ${dateFormat.format(Date(createdAt))}"
        view.findViewById<TextView>(R.id.tvInfoEditedAt).text = "Terakhir diedit  ${dateFormat.format(Date(editedAt))}"
        view.findViewById<TextView>(R.id.tvInfoWorkDuration).text =
            "Waktu pengerjaan  ${formatWorkDuration(rec.workDurationMs)}"

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton("Hapus") { _, _ -> confirmDeleteProject(rec) }
            .setNeutralButton("Pindahkan") { _, _ -> promptMoveProject(listOf(rec)) }
            .setPositiveButton("Buka Proyek") { _, _ -> openProject(rec) }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(getColor(R.color.ps_danger))
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(getColor(R.color.ink_violet))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.vt_secondary))
        }
        dialog.show()
    }

    private fun confirmDeleteProject(rec: ProjectRecord) {
        AlertDialog.Builder(this)
            .setTitle("Hapus proyek?")
            .setMessage("${rec.name.ifBlank { "Tanpa Nama" }} akan dihapus dari riwayat proyek.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                ProjectHistoryManager.remove(this, rec.id)
                loadHistoryAsync()
            }
            .show()
    }

    private fun estimateProjectBytes(rec: ProjectRecord): Long {
        val textBytes = (rec.layersJson?.toByteArray(Charsets.UTF_8)?.size ?: 0) +
            (rec.thumbB64?.length?.times(3)?.div(4) ?: 0) +
            (rec.sourceUri?.toByteArray(Charsets.UTF_8)?.size ?: 0)
        val canvasEstimate = rec.width.toLong() * rec.height.toLong() * 4L
        return if (textBytes > 0) textBytes.toLong() else canvasEstimate
    }

    private fun formatStorageSize(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun formatWorkDuration(durationMs: Long): String {
        if (durationMs < 60_000L) return "kurang dari 1 menit"
        val totalMinutes = durationMs / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> "$hours jam $minutes menit"
            hours > 0L -> "$hours jam"
            else -> "$minutes menit"
        }
    }

    private fun updateAiStatus() {
        val chatKeys = AiChatSettings.load(this)
        val agnesReady = chatKeys.agnes.isNotBlank() || AgnesAiSettings.isConfigured(this)
        val chatReady = chatKeys.openAi.isNotBlank() || chatKeys.claude.isNotBlank()
        val status = when {
            chatReady && agnesReady -> "AI Chat dan Agnes Vision siap"
            chatReady -> "AI Chat siap · Agnes Vision belum diatur"
            agnesReady -> "Agnes Vision siap · AI Chat belum diatur"
            else -> "ChatGPT, Claude, dan Agnes Vision"
        }
        findViewById<TextView>(R.id.tvAiSettingsStatus)?.text = status
    }

    private fun showAiSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_home_settings, null)
        val geminiInput = view.findViewById<EditText>(R.id.etGeminiApiKey)
        val agnesKeyInput = view.findViewById<EditText>(R.id.etAgnesApiKey)
        val promptInput = view.findViewById<EditText>(R.id.etAgnesPrompt)
        val agnes = AgnesAiSettings.load(this)

        geminiInput.setText(GeminiSettings.getApiKey(this).orEmpty())
        agnesKeyInput.setText(agnes.apiKey)
        promptInput.setText(agnes.prompt)

        // ── Model AI lokal: status + unduh ──────────────────────────────
        val modelStatus = view.findViewById<TextView>(R.id.tvModelStatus)
        val btnDownload = view.findViewById<Button>(R.id.btnDownloadLama)
        val btnCheck = view.findViewById<Button>(R.id.btnCheckModels)
        fun refreshModelStatus() {
            val lama = ModelDownloader.isLamaMangaReady(this)
            val yolo = YoloV8mBubbleDetector.isAvailable(this)
            modelStatus?.text =
                "LaMa Manga: ${if (lama) "tersedia (bundled)" else "belum ada (dibundle saat build)"}\n" +
                "Bubble YOLOv8m: ${if (yolo) "siap" else "belum ada (dibundle saat build)"}"
            btnDownload?.isEnabled = !lama
            btnDownload?.text = if (lama) "Model LaMa Sudah Tersedia" else "Unduh Model LaMa Manga (~40 MB)"
        }
        refreshModelStatus()
        btnCheck?.setOnClickListener { refreshModelStatus() }
        btnDownload?.setOnClickListener {
            btnDownload.isEnabled = false
            btnDownload.text = "Mengunduh… 0%"
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    ModelDownloader.downloadLamaManga(this@HomeActivity) { downloaded, total ->
                        val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
                        runOnUiThread { btnDownload.text = "Mengunduh… $pct%" }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@HomeActivity, "Model LaMa Manga berhasil diunduh", Toast.LENGTH_LONG).show()
                        refreshModelStatus()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@HomeActivity, "Unduh gagal: ${e.message}", Toast.LENGTH_LONG).show()
                        refreshModelStatus()
                    }
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton("Batal", null)
            .setNeutralButton("Hapus kunci") { _, _ ->
                GeminiSettings.clear(this)
                AgnesAiSettings.save(this, "", AgnesAiSettings.DEFAULT_PROMPT)
                updateAiStatus()
                Toast.makeText(this, "Kredensial lokal dihapus", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Simpan") { _, _ ->
                GeminiSettings.setApiKey(this, geminiInput.text.toString())
                AgnesAiSettings.save(
                    this,
                    agnesKeyInput.text.toString(),
                    promptInput.text.toString()
                )
                updateAiStatus()
                Toast.makeText(this, "Pengaturan AI tersimpan", Toast.LENGTH_SHORT).show()
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.vt_secondary))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(getColor(R.color.ps_text))
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(getColor(R.color.ps_danger))
        }
        dialog.show()
    }

    private fun showNewProjectDialog() {
        val presets = arrayOf(
            "Manga Page (1080×1520)",
            "A4 Portrait (794×1123)",
            "Square (1080×1080)",
            "4K (3840×2160)",
            "20K Print (disesuaikan aman untuk perangkat)",
            "25K Print (disesuaikan aman untuk perangkat)",
            "Custom…"
        )
        AlertDialog.Builder(this)
            .setTitle("New Project")
            .setItems(presets) { _, which ->
                when (which) {
                    0 -> launchNew("Manga Page", 1080, 1520)
                    1 -> launchNew("A4", 794, 1123)
                    2 -> launchNew("Square", 1080, 1080)
                    3 -> launchNew("4K", 3840, 2160)
                    4 -> launchNew("20K Print", 7016, 9921)
                    5 -> launchNew("25K Print", 8858, 12520)
                    6 -> showCustomSizeDialog()
                }
            }.show()
    }

    private fun showCustomSizeDialog() {
        val etW = android.widget.EditText(this).apply {
            hint = "Width (px)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("1080")
        }
        val etH = android.widget.EditText(this).apply {
            hint = "Height (px)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("1520")
        }
        val ll = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 16, 40, 16)
            addView(etW)
            addView(etH)
        }
        AlertDialog.Builder(this)
            .setTitle("Custom Size")
            .setView(ll)
            .setPositiveButton("Create") { _, _ ->
                val w = etW.text.toString().toIntOrNull()?.coerceIn(100, 25000) ?: 1080
                val h = etH.text.toString().toIntOrNull()?.coerceIn(100, 25000) ?: 1520
                launchNew("Custom", w, h)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchNew(name: String, w: Int, h: Int) {
        val (safeW, safeH) = BitmapSafety.fitCanvasToDevice(w, h)
        if (safeW != w || safeH != h) {
            android.widget.Toast.makeText(
                this,
                "Ukuran disesuaikan ke ${safeW}×${safeH} agar aplikasi tetap stabil di perangkat ini.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_NEW_W, safeW)
            putExtra(EXTRA_NEW_H, safeH)
            putExtra(EXTRA_NEW_NAME, name)
        }
        startActivity(intent)
    }

    private fun pickImage() {
        imagePickerLauncher.launch(arrayOf(
            "image/*",
            "image/vnd.adobe.photoshop",
            "application/x-photoshop",
            "application/octet-stream"
        ))
    }
}
