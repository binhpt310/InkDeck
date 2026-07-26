package dev.inkdeck.ai

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import dev.inkdeck.ai.store.AiProfile
import dev.inkdeck.ai.store.ProviderKind
import dev.inkdeck.ai.ui.MessageAdapter
import dev.inkdeck.ai.ui.ProviderSettingsView
import dev.inkdeck.eink.EinkAnim
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.refresh.EinkRefresher
import dev.inkdeck.eink.refresh.RefresherHost
import dev.inkdeck.eink.widget.EinkButton
import dev.inkdeck.eink.widget.EinkIconButton
import dev.inkdeck.eink.widget.EinkRecyclerView
import dev.inkdeck.eink.widget.EmptyStateView
import dev.inkdeck.eink.widget.ListPickerDialog
import dev.inkdeck.eink.widget.PagedScrollRail
import dev.inkdeck.eink.R as EinkR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The AI tab — design.md §10.
 *
 * ## Chrome
 *
 * This screen draws its own 56 dp bar (active model · history · providers), so the shell's
 * generic title bar is redundant over it. **Recommendation for the coordinator:** give the `ai`
 * tab `hidesTitleBar = true` in `MainActivity`, as the terminal tab already does. Without it the
 * transcript loses 56 dp of a 682 dp column to a bar that says "AI" and nothing else.
 *
 * ## Refresh
 *
 * Two cases, and they are not the same (design.md §13):
 *
 *  - a message appears or the transcript is replaced → the viewport changes → `[F]`;
 *  - a streamed chunk lands on the last bubble → `[P]`, at most twice a second, and the ghost
 *    budget decides when that becomes a flush.
 *
 * Deciding between them by comparing message *count* rather than content is deliberate: the last
 * bubble's text changes on every chunk, so any content-based diff would classify every chunk as
 * a new state and flush the whole panel twice a second.
 */
class AiFragment : Fragment(R.layout.fragment_ai), ProviderSettingsView.Listener {

    private val viewModel: AiViewModel by viewModels()

    private lateinit var modelChip: EinkButton
    private lateinit var historyButton: EinkIconButton
    private lateinit var providersButton: EinkIconButton
    private lateinit var list: EinkRecyclerView
    private lateinit var rail: PagedScrollRail
    private lateinit var empty: EmptyStateView
    private lateinit var input: EditText
    private lateinit var sendButton: EinkIconButton
    private lateinit var settings: ProviderSettingsView

    private val adapter = MessageAdapter()

    /** Message count last handed to the adapter. -1 forces the first render to be a full submit. */
    private var renderedCount = -1

    private val refresher: EinkRefresher?
        get() = (activity as? RefresherHost)?.refresher

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        modelChip = view.findViewById(R.id.aiModel)
        historyButton = view.findViewById(R.id.aiHistory)
        providersButton = view.findViewById(R.id.aiProviders)
        list = view.findViewById(R.id.aiList)
        rail = view.findViewById(R.id.aiRail)
        empty = view.findViewById(R.id.aiEmpty)
        input = view.findViewById(R.id.aiInput)
        sendButton = view.findViewById(R.id.aiSend)
        settings = view.findViewById(R.id.aiSettings)

        // The adapter outlives the view; the count must not, or a re-created view would take the
        // first state emission for a streamed chunk and rebind one row over an empty list.
        renderedCount = -1

        modelChip.setOnClickListener { pickProfile() }

        historyButton.setIconResource(R.drawable.ic_ai_history)
        historyButton.setOnClickListener { pickConversation() }

        providersButton.setIconResource(R.drawable.ic_ai_providers)
        providersButton.setOnClickListener { openSettings() }

        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        // Bubbles must end before the floating rail or their right edge sits underneath it.
        list.setPadding(
            0, 0,
            resources.getDimensionPixelSize(EinkR.dimen.ink_rail_width) +
                EinkTheme.dp(requireContext(), 8f).toInt(),
            0,
        )
        rail.refresher = refresher
        rail.attach(list)

        // design.md §8.3: a blinking caret is a 500 ms animation, which on this panel is a
        // refresh twice a second for as long as the composer holds focus.
        input.isCursorVisible = false
        input.inputType = input.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        sendButton.setOnClickListener { onSendOrStop() }

        settings.listener = this
        settings.refresher = refresher

        EinkAnim.strip(view)
        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    // ------------------------------------------------------------------ render

    private fun render(state: AiUiState) {
        val profile = state.active
        modelChip.text = profile?.model ?: getString(R.string.ai_no_profile)
        modelChip.contentDescription = getString(
            R.string.ai_a11y_model,
            profile?.name.orEmpty(),
            profile?.model.orEmpty(),
        )

        adapter.isStreaming = state.isStreaming
        adapter.workingStep = state.workingStep

        when {
            state.messages.size != renderedCount -> {
                adapter.submit(state.messages)
                renderedCount = state.messages.size
                scrollToEnd()
                refresher?.flush("ai-transcript n=${state.messages.size}")
            }

            state.messages.isNotEmpty() -> {
                // The ≤ 2 Hz case. notePartial returns true when it already flushed on tripping
                // the ghost budget; calling flush() otherwise would defeat the budget entirely.
                adapter.updateLast(state.messages.last())
                scrollToEnd()
                refresher?.notePartial(SURFACE, "ai-stream-chunk")
            }
        }

        sendButton.setIconResource(
            if (state.isStreaming) R.drawable.ic_ai_stop else R.drawable.ic_ai_send
        )
        sendButton.contentDescription =
            getString(if (state.isStreaming) R.string.ai_stop else R.string.ai_send)

        val showEmpty = state.messages.isEmpty()
        empty.visibility = if (showEmpty) View.VISIBLE else View.GONE
        list.visibility = if (showEmpty) View.GONE else View.VISIBLE
        rail.visibility = if (showEmpty) View.GONE else View.VISIBLE
        if (showEmpty) renderEmpty(profile)
    }

    private fun renderEmpty(profile: AiProfile?) {
        val needsSetup = profile == null || (!profile.hasKey && profile.baseUrl.startsWith("https"))
        empty.show(
            state = EmptyStateView.State.EMPTY,
            title = getString(if (needsSetup) R.string.ai_empty_setup_title else R.string.ai_empty_title),
            detail = getString(if (needsSetup) R.string.ai_empty_setup_detail else R.string.ai_empty_detail),
            actionLabel = if (needsSetup) getString(R.string.ai_providers_title) else null,
            onAction = if (needsSetup) ({ openSettings() }) else null,
        )
    }

    /**
     * Two steps because one is not enough: [EinkRecyclerView.scrollToPosition] aligns the *top*
     * of the last row, and a long answer is several viewports tall — the reader would be sent
     * back to the start of the paragraph on every chunk. Scrolling by a value larger than the
     * content clamps at the true bottom.
     */
    private fun scrollToEnd() {
        val last = adapter.itemCount - 1
        if (last < 0) return
        list.scrollToPosition(last)
        list.post { list.scrollBy(0, SCROLL_TO_BOTTOM) }
    }

    // ------------------------------------------------------------------ actions

    private fun onSendOrStop() {
        if (viewModel.state.value.isStreaming) {
            viewModel.stop()
            return
        }
        val text = input.text.toString()
        if (text.isBlank()) return
        input.setText("")
        hideKeyboard()
        viewModel.send(text)
    }

    private fun pickProfile() {
        val profiles = viewModel.state.value.profiles
        if (profiles.isEmpty()) {
            openSettings()
            return
        }
        val activeId = viewModel.state.value.active?.id
        ListPickerDialog(
            context = requireContext(),
            title = getString(R.string.ai_pick_profile),
            options = profiles.map { "${it.name} · ${it.model}" },
            selected = profiles.indexOfFirst { it.id == activeId },
            refresher = refresher,
        ) { index -> viewModel.setActiveProfile(profiles[index].id) }.show()
    }

    /**
     * History and "new chat" share one picker. A separate screen for ten conversations would be
     * 56 dp of chrome and a navigation level for a list that fits in a picker.
     */
    private fun pickConversation() {
        val conversations = viewModel.conversations()
        val options = listOf(getString(R.string.ai_new_chat)) + conversations.map { it.title }
        ListPickerDialog(
            context = requireContext(),
            title = getString(R.string.ai_history),
            options = options,
            selected = 0,
            refresher = refresher,
        ) { index ->
            renderedCount = -1
            if (index == 0) viewModel.newConversation() else viewModel.openConversation(conversations[index - 1].id)
        }.show()
    }

    // ------------------------------------------------------------------ settings overlay

    private fun openSettings() {
        settings.refresh(AiGraph.profiles(requireContext()))
        settings.visibility = View.VISIBLE
        hideKeyboard()
        refresher?.flush("ai-providers-open")
    }

    /** Called by the host's Back handling; true means the press was consumed. */
    fun closeSettingsIfOpen(): Boolean {
        if (settings.visibility != View.VISIBLE) return false
        return settings.onBack()
    }

    override fun onClose() {
        settings.visibility = View.GONE
        viewModel.reloadProfiles()
        refresher?.flush("ai-providers-close")
    }

    override fun onUse(profile: AiProfile) {
        viewModel.setActiveProfile(profile.id)
        settings.refresh(AiGraph.profiles(requireContext()))
        refresher?.notePartial(SURFACE, "ai-profile-active")
    }

    override fun onSave(
        existing: AiProfile?,
        name: String,
        baseUrl: String,
        model: String,
        kind: ProviderKind,
        key: CharArray?,
    ) {
        if (name.isBlank() || baseUrl.isBlank() || model.isBlank()) {
            key?.fill(' ')
            toast(getString(R.string.ai_profile_incomplete))
            return
        }
        val saved = AiGraph.registerProfile(
            context = requireContext(),
            name = name,
            baseUrl = baseUrl,
            model = model,
            kind = kind,
            key = key,
            existingId = existing?.id,
        )
        if (saved == null) {
            toast(getString(R.string.ai_error_vault_locked))
            return
        }
        settings.onBack()
        settings.refresh(AiGraph.profiles(requireContext()))
        viewModel.reloadProfiles()
    }

    override fun onDelete(profile: AiProfile) {
        AiGraph.deleteProfile(requireContext(), profile.id)
        settings.onBack()
        settings.refresh(AiGraph.profiles(requireContext()))
        viewModel.reloadProfiles()
    }

    /**
     * The passphrase path. Only reachable when the user has opted into
     * `SecretVault.Protection.PASSPHRASE`; a device-protected vault opens with no interaction, so
     * showing a prompt for it would be a dialog with nothing behind it.
     *
     * PBKDF2 at 120 000 iterations takes a second or two on two ~1 GHz cores — off the main
     * thread, or the panel freezes mid-tap.
     */
    override fun onUnlockRequested() {
        val field = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isCursorVisible = false
            setHint(R.string.ai_vault_passphrase_hint)
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.ai_vault_unlock)
            .setView(field)
            .setPositiveButton(R.string.ai_vault_unlock) { _, _ ->
                val passphrase = field.text.toString().toCharArray()
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        AiGraph.vault(requireContext()).unlock(passphrase).also {
                            passphrase.fill(' ')
                        }
                    }
                    if (!ok) toast(getString(R.string.ai_vault_wrong))
                    settings.refresh(AiGraph.profiles(requireContext()))
                }
            }
            .setNegativeButton(EinkR.string.ink_cancel, null)
            .show()
            .also { it.window?.setWindowAnimations(0) }
    }

    // ------------------------------------------------------------------ plumbing

    private fun hideKeyboard() {
        val imm = requireContext()
            .getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(requireView().windowToken, 0)
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val SURFACE = "ai-chat"

        /** Larger than any transcript; RecyclerView clamps at the end of the content. */
        const val SCROLL_TO_BOTTOM = 1_000_000
    }
}
