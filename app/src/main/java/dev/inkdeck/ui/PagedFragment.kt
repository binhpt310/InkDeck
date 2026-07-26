package dev.inkdeck.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import dev.inkdeck.R
import dev.inkdeck.databinding.FragmentPagedBinding
import dev.inkdeck.eink.EinkAnim
import dev.inkdeck.eink.refresh.RefresherHost

/**
 * Base for any screen that scrolls: wires the paged rail (design.md §5.5) and strips motion
 * from the inflated hierarchy so no subclass has to remember to.
 */
abstract class PagedFragment : Fragment(R.layout.fragment_paged) {

    private var _binding: FragmentPagedBinding? = null
    protected val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPagedBinding.bind(view)

        binding.rail.refresher = (activity as? RefresherHost)?.refresher
        binding.rail.attach(binding.scroll)

        EinkAnim.strip(view)
        onContent(binding.container)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** Populate the scrollable column. */
    abstract fun onContent(container: LinearLayout)
}
