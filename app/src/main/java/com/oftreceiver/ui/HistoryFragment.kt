package com.oftreceiver.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.oftreceiver.data.AppDatabase
import com.oftreceiver.data.TransferRecord
import com.oftreceiver.databinding.FragmentHistoryBinding
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: HistoryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Apply status bar insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.historyTopBar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, insets.top + 16, v.paddingRight, v.paddingBottom)
            windowInsets
        }

        adapter = HistoryAdapter { record ->
            deleteRecord(record)
        }

        binding.historyList.layoutManager = LinearLayoutManager(requireContext())
        binding.historyList.adapter = adapter

        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.getInstance(requireContext())
            val records = db.transferDao().getAll()

            if (records.isEmpty()) {
                binding.emptyState.visibility = View.VISIBLE
                binding.historyList.visibility = View.GONE
                binding.transferCount.text = ""
            } else {
                binding.emptyState.visibility = View.GONE
                binding.historyList.visibility = View.VISIBLE
                binding.transferCount.text = "${records.size} file${if (records.size != 1) "s" else ""}"
                adapter.submitList(records)
            }
        }
    }

    private fun deleteRecord(record: TransferRecord) {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.getInstance(requireContext())
            db.transferDao().deleteById(record.id)
            loadHistory()
        }
    }
}
