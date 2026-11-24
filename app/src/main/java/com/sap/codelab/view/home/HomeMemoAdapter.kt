package com.sap.codelab.view.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.sap.codelab.databinding.RecyclerviewMemoBinding
import com.sap.codelab.domain.model.Memo

internal class HomeMemoAdapter(
    private val onMemoClick: View.OnClickListener,
    private val onCompletionChange: CompoundButton.OnCheckedChangeListener
) : ListAdapter<Memo, MemoViewHolder>(MemoDiffCallback()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemoViewHolder {
        val binding =
            RecyclerviewMemoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MemoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MemoViewHolder, position: Int) {
        holder.update(getItem(position), onMemoClick, onCompletionChange)
    }

    private class MemoDiffCallback : DiffUtil.ItemCallback<Memo>() {
        override fun areItemsTheSame(oldItem: Memo, newItem: Memo) = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Memo, newItem: Memo) = oldItem == newItem
    }
}
