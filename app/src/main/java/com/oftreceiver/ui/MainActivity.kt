package com.oftreceiver.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.oftreceiver.R
import com.oftreceiver.databinding.ActivityMainBinding
import com.oftreceiver.viewmodel.MainViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val scanFragment = ScanFragment()
    private val historyFragment = HistoryFragment()
    private var activeFragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enable edge-to-edge before setContentView
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply navigation bar insets to bottom nav
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = insets.bottom)
            windowInsets
        }

        // Set up fragments
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, historyFragment, "history")
                .hide(historyFragment)
                .add(R.id.fragmentContainer, scanFragment, "scan")
                .commit()
            activeFragment = scanFragment
        } else {
            // Restore fragments on config change
            val scan = supportFragmentManager.findFragmentByTag("scan")
            val history = supportFragmentManager.findFragmentByTag("history")
            if (scan != null && history != null) {
                activeFragment = if (history.isVisible) history else scan
            }
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_scan -> {
                    switchFragment(scanFragment)
                    true
                }
                R.id.nav_history -> {
                    switchFragment(historyFragment)
                    true
                }
                else -> false
            }
        }
    }

    private fun switchFragment(target: Fragment) {
        if (target == activeFragment) return
        val current = activeFragment ?: return

        supportFragmentManager.beginTransaction()
            .hide(current)
            .show(target)
            .commit()
        activeFragment = target
    }
}
