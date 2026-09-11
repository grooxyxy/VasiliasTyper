package com.vasiliastyper.adapter

  import android.view.LayoutInflater
  import android.view.ViewGroup
  import android.widget.ImageView
  import android.widget.TextView
  import androidx.recyclerview.widget.RecyclerView
  import com.vasiliastyper.R
  import com.vasiliastyper.engine.ProjectHistoryManager
  import com.vasiliastyper.engine.ProjectRecord
  import java.text.SimpleDateFormat
  import java.util.Date
  import java.util.Locale

  class RecentProjectAdapter(
      private val records: MutableList<ProjectRecord>,
      private val onClick: (ProjectRecord) -> Unit,
      private val onInfo: (ProjectRecord) -> Unit,
      private val onLongClick: (ProjectRecord) -> Unit = {}
  ) : RecyclerView.Adapter<RecentProjectAdapter.VH>() {

      inner class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
          val tvName:    TextView  = view.findViewById(R.id.tvProjectName)
          val tvMeta:    TextView  = view.findViewById(R.id.tvProjectMeta)
          val imgThumb:  ImageView = view.findViewById(R.id.imgThumb)
          val btnInfo:   TextView  = view.findViewById(R.id.btnProjectInfo)
      }

      override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
          val v = LayoutInflater.from(parent.context)
              .inflate(R.layout.item_recent_project, parent, false)
          return VH(v)
      }

      override fun onBindViewHolder(h: VH, position: Int) {
          val rec = records.getOrNull(position) ?: return
          try {
              val date = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(rec.dateMs))
              val folder = ProjectHistoryManager.normalizeFolderName(rec.folder)
              h.tvName.text = rec.name.ifBlank { "Untitled" }
              h.tvMeta.text = "${rec.width}×${rec.height}  |  $folder\n$date"

              // v2.0: show thumbnail if available
              if (!rec.thumbB64.isNullOrBlank()) {
                  val bmp = ProjectHistoryManager.decodeThumb(rec.thumbB64)
                  if (bmp != null) h.imgThumb.setImageBitmap(bmp)
                  else h.imgThumb.setImageDrawable(null)
              } else {
                  h.imgThumb.setImageDrawable(null)
              }

              h.itemView.setOnClickListener { onClick(rec) }
              h.itemView.setOnLongClickListener {
                  onLongClick(rec)
                  true
              }
              h.btnInfo.setOnClickListener { onInfo(rec) }
          } catch (_: Throwable) {
              h.tvName.text = rec.name.ifBlank { "Untitled" }
              h.tvMeta.text = "${rec.width}×${rec.height}"
              h.imgThumb.setImageDrawable(null)
              h.itemView.setOnClickListener { onClick(rec) }
              h.itemView.setOnLongClickListener {
                  onLongClick(rec)
                  true
              }
              h.btnInfo.setOnClickListener { onInfo(rec) }
          }
      }

      override fun getItemCount() = records.size

      // v2.0: refresh list from outside
      fun updateList(newRecords: List<ProjectRecord>) {
          records.clear()
          records.addAll(newRecords)
          notifyDataSetChanged()
      }
  }
  