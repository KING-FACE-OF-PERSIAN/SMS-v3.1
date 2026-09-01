package com.example.consentsms

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.consentsms.databinding.ItemReportBinding

class ReportAdapter(private val items: MutableList<RecipientReport>) :
    RecyclerView.Adapter<ReportAdapter.ReportViewHolder>() {

    inner class ReportViewHolder(val binding: ItemReportBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReportViewHolder {
        val binding = ItemReportBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ReportViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ReportViewHolder, position: Int) {
        val item = items[position]
        holder.binding.numberText.text = item.number
        holder.binding.countText.text = "(${item.completedAttempts}/${item.totalAttempts})"
        holder.binding.statusText.text = when {
            item.completedAttempts == 0 -> "در انتظار"
            item.remainingAttempts == 0 && item.failedCount == 0 -> "ارسال شده"
            item.remainingAttempts == 0 && item.successCount == 0 -> "ناموفق"
            else -> "در حال انجام"
        }
    }

    override fun getItemCount(): Int = items.size

    fun refresh() {
        notifyDataSetChanged()
    }
}
