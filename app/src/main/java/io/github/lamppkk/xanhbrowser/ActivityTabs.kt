package io.github.lamppkk.xanhbrowser

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.lamppkk.xanhbrowser.databinding.ActivityTabsBinding
import kotlinx.coroutines.launch

class ActivityTabs : AppCompatActivity() {
    private lateinit var binding: ActivityTabsBinding
    private lateinit var repository: BrowserRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityTabsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        repository = BrowserRepository(this)

        val adapter = TabAdapter(
            onOpen = { tab ->
                lifecycleScope.launch {
                    repository.selectTab(tab.id)
                    finish()
                }
            },
            onClose = { tab ->
                lifecycleScope.launch { repository.closeTab(tab.id, getString(R.string.app_website)) }
            },
        )
        binding.tabs.layoutManager = LinearLayoutManager(this)
        binding.tabs.adapter = adapter
        binding.newTab.setOnClickListener {
            lifecycleScope.launch {
                repository.createTab(getString(R.string.app_website))
                finish()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.tabs.collect(adapter::submitList)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
