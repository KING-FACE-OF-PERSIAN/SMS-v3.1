package com.example.consentsms

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.consentsms.databinding.ItemScheduleBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScheduleAdapter(private val items: MutableList<ScheduledMessage>) :
    RecyclerView.Adapter<ScheduleAdapter.ScheduleViewHolder>() {

    private val formatter = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault())

    inner class ScheduleViewHolder(val binding: ItemScheduleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScheduleViewHolder {
        val binding = ItemScheduleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ScheduleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ScheduleViewHolder, position: Int) {
        val item = items[position]
        val recipientCount = item.numbersText
            .split(Regex("[,;\\s]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .size
        holder.binding.scheduleTitle.text = "ارسال زمان‌بندی‌شده برای $recipientCount شماره"
        holder.binding.scheduleMeta.text =
            "${formatter.format(Date(item.triggerAtMillis))}  |  تکرار: ${item.repeatCount}"
    }

    override fun getItemCount(): Int = items.size

    fun refresh() {
        notifyDataSetChanged()
    }
}
