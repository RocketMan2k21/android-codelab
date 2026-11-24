package com.sap.codelab.view.detail

import android.os.Bundle
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sap.codelab.R
import com.sap.codelab.databinding.ActivityViewMemoBinding
import com.sap.codelab.domain.model.hasLocation
import com.sap.codelab.repository.App
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal const val BUNDLE_MEMO_ID: String = "memoId"

/**
 * Activity that allows a user to see the details of a memo.
 */
internal class ViewMemo : AppCompatActivity() {

    private lateinit var binding: ActivityViewMemoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewMemoBinding.inflate(layoutInflater)
        binding.contentCreateMemo.run {
            root.isVisible = false
            memoTitle.isEnabled = false
            memoDescription.isEnabled = false
        }
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeActionContentDescription(R.string.action_back)

        val memoId = intent.getLongExtra(BUNDLE_MEMO_ID, -1)
        val app = application as App
        val factory = viewModelFactory {
            initializer {
                ViewMemoViewModel(memoId, app.memoRepository, app.monitoringUseCase)
            }
        }
        val model = ViewModelProvider(this, factory)[ViewMemoViewModel::class.java]
        binding.markMemoDone.setOnClickListener { model.markDone() }

        onBackPressedDispatcher.addCallback(this) {
            // Let the save finish before Home reloads its list.
            lifecycleScope.launch {
                model.state.first { !it.isCompleting }
                if (isFinishing) return@launch
                if (isTaskRoot) {
                    supportParentActivityIntent?.let { startActivity(it) }
                }
                finish()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.state.collect(::render) }
                launch {
                    model.errorMessages.collect { message ->
                        Toast.makeText(this@ViewMemo, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun render(state: ViewMemoViewModel.State) {
        val memo = state.memo
        val isDone = memo?.isDone == true
        binding.markMemoDone.apply {
            isVisible = memo != null
            isChecked = isDone || state.isCompleting
            isEnabled = memo != null && !isDone && !state.isCompleting
            setText(if (isDone) R.string.memo_done else R.string.action_mark_done)
        }
        binding.contentCreateMemo.run {
            root.isVisible = memo != null
            if (memo == null) return

            memoTitle.setText(memo.title)
            memoDescription.setText(memo.description)
            memoDetailsLocation.isVisible = true
            memoCoordinates.text = if (memo.hasLocation) {
                getString(R.string.selected_memo_location, memo.reminderLatitude, memo.reminderLongitude)
            } else {
                getString(R.string.memo_no_location)
            }
        }
    }
}
