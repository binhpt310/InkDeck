package dev.inkdeck.telegram

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.inkdeck.eink.EinkAnim
import dev.inkdeck.eink.refresh.EinkRefresher
import dev.inkdeck.eink.refresh.RefresherHost
import dev.inkdeck.eink.widget.EinkButton
import dev.inkdeck.eink.widget.EinkIconButton
import dev.inkdeck.eink.widget.SegmentedControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * design.md §12.
 *
 * The screen answers three questions and nothing else: is it connected, who is allowed to talk to
 * it, and are secrets being deleted. Everything below that — the warning box and the command
 * reference — is reference material the sketch puts on the same screen because there is nowhere
 * else it would be read.
 *
 * ### Reads are off the main thread
 *
 * The paired chat id lives in the vault (see [TelegramStore] for why), and reading it means an
 * AES-GCM unwrap plus a decrypt. Cheap, but not free on two ~1 GHz cores, and it happens on every
 * status change. [renderPairing] hops to IO and back.
 *
 * ### Refresh
 *
 * Toggling either control or re-pairing changes text in several places at once and is `[F]`
 * (design.md §13's "whole viewport replaced" case, one row short of it). A status line ticking
 * down a retry countdown is emphatically `[P]` — flushing the panel once a second because the
 * network is down would be worse than the outage.
 */
class TelegramSettingsFragment : Fragment(R.layout.fragment_telegram) {

    private lateinit var store: TelegramStore

    private lateinit var statusLine: TextView
    private lateinit var statusDetail: TextView
    private lateinit var pairHeader: TextView
    private lateinit var pairValue: TextView
    private lateinit var pairBadge: TextView
    private lateinit var pairHint: TextView
    private lateinit var toggleEnabled: SegmentedControl
    private lateinit var toggleAutoDelete: SegmentedControl
    private lateinit var actionRepair: EinkButton
    private lateinit var actionDisconnect: EinkButton

    private val refresher: EinkRefresher?
        get() = (activity as? RefresherHost)?.refresher

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = TelegramStore(requireContext())

        statusLine = view.findViewById(R.id.statusLine)
        statusDetail = view.findViewById(R.id.statusDetail)
        pairHeader = view.findViewById(R.id.pairHeader)
        pairValue = view.findViewById(R.id.pairValue)
        pairBadge = view.findViewById(R.id.pairBadge)
        pairHint = view.findViewById(R.id.pairHint)
        toggleEnabled = view.findViewById(R.id.toggleEnabled)
        toggleAutoDelete = view.findViewById(R.id.toggleAutoDelete)
        actionRepair = view.findViewById(R.id.actionRepair)
        actionDisconnect = view.findViewById(R.id.actionDisconnect)

        view.findViewById<EinkIconButton>(R.id.actionBack).apply {
            setIconResource(R.drawable.ic_back)
            setOnClickListener { goBack() }
        }

        val onOff = listOf(getString(R.string.tg_on), getString(R.string.tg_off))

        toggleEnabled.segments = onOff
        toggleEnabled.selectedIndex = if (store.enabled) 0 else 1
        toggleEnabled.onSelected = { index ->
            TelegramGraph.setEnabled(requireContext(), index == 0)
            // Turning it on or off rewrites the status line, both action buttons and the
            // pairing block. That is the viewport.
            refresher?.flush("telegram-enabled=${index == 0}")
        }

        toggleAutoDelete.segments = onOff
        toggleAutoDelete.selectedIndex = if (store.autoDelete) 0 else 1
        toggleAutoDelete.onSelected = { index ->
            store.autoDelete = index == 0
            // The control redraws itself; nothing else on screen moves.
            refresher?.notePartial(SURFACE, "telegram-autodelete")
        }

        actionRepair.apply {
            text = getString(R.string.tg_repair)
            variant = EinkButton.Variant.SECONDARY
            setOnClickListener { repair() }
        }
        actionDisconnect.apply {
            text = getString(R.string.tg_disconnect)
            variant = EinkButton.Variant.SECONDARY
            setOnClickListener { disconnect() }
        }

        EinkAnim.strip(view)
        observe()
    }

    override fun onResume() {
        super.onResume()
        // The loop may have paired, lost the network or been swept while this screen was hidden.
        renderPairing()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                TelegramGraph.status.collect { render(it) }
            }
        }
    }

    // ------------------------------------------------------------------ render

    private fun render(status: TelegramState.Status) {
        val (line, detail) = when (status.phase) {
            TelegramState.Phase.CONNECTED -> {
                val name = status.botUsername ?: store.botUsername
                val head = if (name != null) {
                    getString(R.string.tg_status_connected, "@$name")
                } else {
                    getString(R.string.tg_status_connected_anon)
                }
                head to getString(R.string.tg_status_connected_detail)
            }
            TelegramState.Phase.UNPAIRED ->
                getString(R.string.tg_status_unpaired) to
                    getString(R.string.tg_status_unpaired_detail)
            TelegramState.Phase.NO_TOKEN ->
                getString(R.string.tg_status_no_token) to
                    getString(R.string.tg_status_no_token_detail)
            TelegramState.Phase.VAULT_LOCKED ->
                getString(R.string.tg_status_vault_locked) to
                    getString(R.string.tg_status_vault_locked_detail)
            TelegramState.Phase.RETRYING ->
                getString(R.string.tg_status_retrying, status.retryInSeconds) to
                    getString(R.string.tg_status_retrying_detail)
            TelegramState.Phase.STOPPED ->
                getString(R.string.tg_status_off) to getString(R.string.tg_status_off_detail)
        }

        statusLine.text = line
        statusDetail.text = detail
        renderPairing()
    }

    /**
     * Paired: the chat id and the allowlist badge. Unpaired: the six-digit code and what to do
     * with it. One block, because they are the same question — who may talk to this device.
     */
    private fun renderPairing() {
        viewLifecycleOwner.lifecycleScope.launch {
            val paired = withContext(Dispatchers.IO) {
                val vault = store.openVault()
                if (vault == null) PairedState.VaultLocked
                else store.pairedChatId(vault)?.let { PairedState.Paired(it) } ?: PairedState.None
            }
            if (!isAdded) return@launch

            when (paired) {
                is PairedState.Paired -> {
                    pairHeader.setText(R.string.tg_section_paired)
                    pairValue.text = getString(R.string.tg_paired_id, paired.chatId.toString())
                    pairBadge.visibility = View.VISIBLE
                    pairHint.visibility = View.GONE
                }
                PairedState.None -> {
                    val code = withContext(Dispatchers.IO) { store.pairingCode() }
                    pairHeader.setText(R.string.tg_pair_code_label)
                    // Spaced so it can be read off a dithered panel and typed without a slip.
                    pairValue.text = code.chunked(3).joinToString(" ")
                    pairBadge.visibility = View.GONE
                    pairHint.visibility = View.VISIBLE
                    pairHint.text = getString(R.string.tg_pair_instructions, code)
                }
                PairedState.VaultLocked -> {
                    pairHeader.setText(R.string.tg_section_paired)
                    pairValue.setText(R.string.tg_vault_unreadable)
                    pairBadge.visibility = View.GONE
                    pairHint.visibility = View.GONE
                }
            }
        }
    }

    private sealed class PairedState {
        data class Paired(val chatId: Long) : PairedState()
        object None : PairedState()
        object VaultLocked : PairedState()
    }

    // ------------------------------------------------------------------ actions

    /**
     * Re-pair rolls the code *and* drops the current chat. Generating a new code while the old
     * chat still worked would leave two ways in, and the button reads as "move this to a
     * different chat", not "print a spare key".
     */
    private fun repair() {
        TelegramGraph.unpairAsync(requireContext()) {
            if (!isAdded) return@unpairAsync
            toast(getString(R.string.tg_repaired_toast))
            renderPairing()
            refresher?.flush("telegram-repair")
        }
    }

    private fun disconnect() {
        TelegramGraph.unpairAsync(requireContext()) {
            if (!isAdded) return@unpairAsync
            toast(getString(R.string.tg_disconnected_toast))
            renderPairing()
            refresher?.flush("telegram-disconnect")
        }
    }

    private fun goBack() {
        if (parentFragmentManager.backStackEntryCount > 0) {
            parentFragmentManager.popBackStack()
        } else {
            @Suppress("DEPRECATION")
            activity?.onBackPressed()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val SURFACE = "telegram-settings"
    }
}
