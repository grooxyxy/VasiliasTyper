package com.vasiliastyper

/** Offline learning hub content. Articles are bundled so every guide works without internet. */
object BlogContent {
    data class Section(val heading: String, val body: String)

    data class Article(
        val slug: String,
        val category: String,
        val title: String,
        val summary: String,
        val readTime: String,
        val sections: List<Section>
    )

    val articles: List<Article> = listOf(
        Article(
            slug = "whats-new-webtoon-sfx",
            category = "CHANGELOG · WEBTOON SFX",
            title = "Preset SFX Webtoon kini lebih hidup dan tetap editable",
            summary = "28 preset baru meniru ritme lettering webtoon: impact, motion, reaction, quiet, dan decorative.",
            readTime = "4 menit",
            sections = listOf(
                Section("Yang baru", "Style Manager kini memasang 28 preset SFX editable. Koleksi mencakup BOOM!, KABOOM, SLAM, THUD, PUSH, GRAB, SQUEEZE, PAT PAT, PLOP, FWIP, SWISH, WHOOSH, SPRING, SHAAA, GASP, AAAGH!, HAHAHA, SOB, SNIFF, UM…, TREMBLE, CHATTER, TSSS~, GLANCE, DING DONG, SILENCE…, MAGIC, SPARKLE, dan FIRE."),
                Section("Lettering yang lebih dekat dengan referensi", "Empat font komik open-source ikut dibundel: Kalam Regular, Kalam Bold, Bangers, dan Permanent Marker. Preset memadukan bobot, italic, tracking, outline, shadow, dan gradient untuk membedakan benturan keras, gerak cepat, suara lembut, serta reaksi emosional."),
                Section("Preview yang informatif", "Style Manager tidak lagi hanya menampilkan ‘Ag’. Setiap preset mempunyai contoh kata sendiri, kategori, dan petunjuk singkat. Contoh ini hanya preview dan tidak akan mengganti tulisan pengguna."),
                Section("Aman untuk style buatan sendiri", "Tombol Pulihkan Preset hanya mengganti preset bawaan dengan nama yang sama. Style dan folder buatan pengguna tidak dihapus."),
                Section("Pusat Belajar offline", "Homepage kini memiliki kartu changelog dan tutorial. Semua artikel tersimpan di aplikasi sehingga dapat dibuka tanpa koneksi internet.")
            )
        ),
        Article(
            slug = "tutorial-webtoon-sfx",
            category = "TUTORIAL · TEXT STUDIO",
            title = "Cara memakai dan mengedit preset Webtoon SFX",
            summary = "Mulai dari tulisan biasa, terapkan preset, lalu ubah font, warna, outline, shadow, gradient, dan spacing.",
            readTime = "6 menit",
            sections = listOf(
                Section("1. Buat atau pilih teks", "Di editor, tambahkan Text atau pilih elemen teks yang sudah ada. Ketik SFX Anda sendiri—misalnya BRAK!, SRRRT, atau kata dalam bahasa Korea. Preset tidak mengunci dan tidak mengganti isi teks."),
                Section("2. Buka koleksi preset", "Di Text Studio tekan Load, lalu pilih folder Webtoon SFX. Tekan Pakai pada style yang diinginkan. Jika koleksi tidak terlihat, buka Style Manager dan tekan Pasang / Pulihkan Preset SFX Webtoon."),
                Section("3. Sesuaikan tipografi", "Pada tab TEXT, ubah Font, Size, Bold, Italic, Tracking, Leading, dan Align. Untuk gerak cepat gunakan italic dan tracking agak renggang; untuk benturan berat gunakan font padat dan tracking negatif."),
                Section("4. Edit fill dan outline", "Ketuk patch warna teks untuk memilih fill. Aktifkan Outline lalu atur warna, lebar, dan opacity. Fill putih dengan outline hitam cocok untuk DING DONG; fill hitam tanpa outline cocok untuk lettering ringan seperti SNIFF atau GLANCE."),
                Section("5. Tambahkan shadow dan gradient", "Di tab EFEK, aktifkan Shadow untuk kedalaman atau arah gerak. Gradient fill mendukung beberapa color stop dan sudut 0–359°. Semua efek dapat ditumpuk tanpa meratakan teks menjadi gambar."),
                Section("6. Bentuk dan transformasi", "Gunakan Perspective atau Mesh untuk komposisi yang lebih dinamis. Transformasi elemen tetap terpisah dari preset agar posisi dan bentuk yang sudah dibuat tidak hilang saat style diganti."),
                Section("7. Simpan variasi sendiri", "Setelah selesai, tekan Save di Text Studio, beri nama, lalu pilih folder. Style baru akan menyimpan font dan semua efek saat ini, sedangkan wording tetap bebas diedit setiap kali style digunakan.")
            )
        ),
        Article(
            slug = "feature-guide",
            category = "PANDUAN · SEMUA FITUR",
            title = "Tutorial lengkap semua fitur MVP VasiliasTyper",
            summary = "Alur praktis dari folder proyek sampai selection, cleanup, text, layer, OCR, AI, dan export.",
            readTime = "12 menit",
            sections = listOf(
                Section("1. Homepage, proyek, dan folder", "Tekan Kanvas Baru untuk membuat dokumen atau Buka Gambar untuk mengimpor gambar. Buka menu Proyek dan Folder dari footer, judul Proyek Terbaru, atau menu utama. Di sana Anda dapat membuka semua proyek, membuat folder, memindahkan proyek ke folder, serta me-rename folder tanpa mengubah isi proyek."),
                Section("2. Workspace dan navigasi kanvas", "Workspace mendukung beberapa tab, pan, zoom, undo/redo, auto-save, serta pemulihan layer dan teks. Gunakan menu Project untuk simpan/buka, View untuk tampilan kanvas, dan History untuk kembali ke perubahan sebelumnya."),
                Section("3. Selection", "Pilih Magic Wand untuk area dengan warna serupa, Rectangle atau Oval untuk bentuk teratur, dan Lasso/Polyline untuk area bebas. Selection dapat ditambah, dikurangi, dibalik, dibatalkan, atau dipakai sebagai batas proses cleanup."),
                Section("4. Cleanup dan inpaint", "Fill White/Black cocok untuk bubble sederhana. Bubble Clean membersihkan teks di balon. Inpaint, Brush Inpaint, Content Aware, RemovR, dan UNWM merekonstruksi area kompleks. Mulai dari area kecil dan gunakan undo jika hasil belum sesuai."),
                Section("5. Text Studio dasar", "Tambah Text lalu isi tulisan. Atur Font Bank, ukuran, Bold, Italic, alignment, tracking, leading, uppercase/lowercase/title case, dan warna. Multi Style memberi gaya berbeda pada bagian teks yang dipilih."),
                Section("6. Semua efek teks", "Buka tab EFEK. Setiap efek kini memiliki kolom kontrol sendiri seperti Blur: aktifkan Outline, Shadow, Gradient, Texture, Warp, atau Blur secara independen. Outline mengatur warna/lebar/opacity; Shadow mengatur radius, offset, warna, dan opacity; Gradient mengatur color stop serta angle; Texture memuat gambar; Blur menyediakan Gaussian dan Motion."),
                Section("7. Bentuk teks dan preset", "Perspective dan Mesh membentuk teks secara non-destruktif. Save menyimpan style ke folder, Load membuka Style Manager, dan preset Webtoon SFX memberi fondasi yang tetap editable. Isi teks tidak terkunci oleh preset."),
                Section("8. Layer", "Panel Layer dapat menambah, menduplikasi, menghapus, me-rename, menyembunyikan, dan mengunci layer. Atur opacity dan blending, lalu gunakan Merge Down, Merge Visible, atau Flatten hanya ketika struktur layer sudah final."),
                Section("9. OCR, script, dan terjemahan", "OCR lokal membaca teks sambil menjaga region. Script Panel membantu memasukkan baris secara berurutan. Jika Gemini API key tersedia, OCR dan translation dapat memakai model multimodal; hasil asli maupun terjemahan tetap dapat diedit dan disalin."),
                Section("10. AI dan pengaturan", "Atur Gemini dan Agnes AI dari Koneksi AI di homepage. Kunci disimpan lokal pada perangkat. Fitur tetap menyediakan alur lokal/fallback saat layanan AI tidak dikonfigurasi atau koneksi tidak tersedia."),
                Section("11. Simpan dan export", "Pastikan proyek tersimpan dan berada di folder yang tepat. Sebelum export, cek visibilitas layer, tepi outline, blur, dan transparansi pada zoom 100%. Pilih PNG untuk kualitas/alpha, JPG untuk ukuran kecil, atau WebP untuk kompromi ukuran dan kualitas."),
                Section("12. Alur MVP yang disarankan", "Buat folder proyek, buat/buka kanvas, seleksi area teks, lakukan cleanup, jalankan OCR atau masukkan script, tambahkan teks dan efek, rapikan layer, simpan proyek, lalu export. Simpan variasi style untuk mempercepat halaman berikutnya.")
            )
        )
    )

    fun find(slug: String?): Article? = articles.firstOrNull { it.slug == slug }
}
