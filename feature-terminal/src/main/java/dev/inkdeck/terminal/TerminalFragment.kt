package dev.inkdeck.terminal

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.inkdeck.data.ssh.HostEntry
import dev.inkdeck.data.ssh.HostStore
import dev.inkdeck.data.ssh.KnownHostsStore
import dev.inkdeck.data.vault.SecretVault
import dev.inkdeck.eink.EinkAnim
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.refresh.RefresherHost
import dev.inkdeck.eink.widget.EinkButton
import dev.inkdeck.terminal.databinding.FragmentTerminalBinding
import dev.inkdeck.terminal.sftp.FileViewerView
import dev.inkdeck.terminal.sftp.FilesView
import dev.inkdeck.terminal.sftp.SftpBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Terminal tab — Plan.md §4, design.md §7.1.
 *
 * Holds the setup flow (vault, import, host pick) and, once connected, the live session. Both
 * live in one Fragment because the shell keeps tabs alive with hide/show rather than replace, so
 * an SSH session survives a trip to another tab and back.
 */
class TerminalFragment : Fragment(R.layout.fragment_terminal),
    InkDeckHostKeyRepository.HostKeyApproval {

    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!

    private lateinit var vault: SecretVault
    private lateinit var hostStore: HostStore
    private lateinit var knownHosts: KnownHostsStore

    private var emulator: TerminalEmulator? = null
    private var session: SshSession? = null
    private var activeHost: HostEntry? = null

    private var sftp: SftpBrowser? = null
    private var filesOpen = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentTerminalBinding.bind(view)

        val ctx = requireContext().applicationContext
        vault = SecretVault.get(ctx)
        hostStore = HostStore(ctx)
        knownHosts = KnownHostsStore(ctx)

        EinkAnim.strip(view)

        binding.btnFiles.apply {
            setIconResource(R.drawable.ic_files)
            iconTint = EinkTheme.ink900(requireContext())
            setOnClickListener { toggleFiles() }
        }
        binding.scrim.setOnClickListener { setFilesOpen(false) }
        binding.files.listener = filesListener
        binding.files.refresher = (activity as? RefresherHost)?.refresher
        binding.btnReconnect.apply {
            setIconResource(R.drawable.ic_reconnect)
            iconTint = EinkTheme.ink900(requireContext())
            setOnClickListener { reconnect() }
        }

        binding.terminal.refresher = (activity as? RefresherHost)?.refresher
        binding.terminal.gridListener = TerminalView.GridListener { cols, rows ->
            session?.resize(cols, rows)
        }
        binding.terminal.onLatchesConsumed = { binding.keyRow.clearLatches() }

        binding.keyRow.listener = object : TerminalKeyRow.Listener {
            override fun onVtKey(vtKey: Int) {
                binding.terminal.sendVtKey(vtKey)
                binding.keyRow.clearLatches()
            }

            override fun onText(text: String) {
                text.forEach { binding.terminal.sendChar(it) }
                binding.keyRow.clearLatches()
            }

            override fun onRaw(bytes: ByteArray) {
                binding.terminal.sendBytes(bytes)
                binding.keyRow.clearLatches()
            }

            override fun onModifierLatched(ctrl: Boolean, alt: Boolean) {
                binding.terminal.ctrlLatched = ctrl
                binding.terminal.altLatched = alt
            }

            override fun onToggleKeyboard() = toggleSoftKeyboard()
        }

        renderSetup()
    }

    override fun onDestroyView() {
        sftp?.close()
        sftp = null
        session?.disconnect()
        session = null
        emulator = null
        _binding = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------------ files sidebar

    private val filesListener = object : FilesView.Listener {

        override fun onChangeShellDirectory(path: String) {
            // Quoted so paths with spaces survive, and sent as if typed so the shell's history
            // and prompt stay consistent with what the browser is showing.
            binding.terminal.sendBytes("cd '${path.replace("'", "'\\''")}'\r".toByteArray())
            if (!isLandscape()) setFilesOpen(false)
        }

        override fun onRequestClose() = setFilesOpen(false)

        override fun onRequestUpload(remoteDirectory: String) = pickFileToUpload()

        override fun onOpenFile(entry: SftpBrowser.Entry) = openViewer(entry)

        override fun onError(message: String) = showMessage("Files", message)

        override fun onInfo(message: String) = toast(message)
    }

    private var viewingEntry: SftpBrowser.Entry? = null

    private fun openViewer(entry: SftpBrowser.Entry) {
        viewingEntry = entry
        binding.viewer.apply {
            refresher = (activity as? RefresherHost)?.refresher
            listener = viewerListener
            showLoading(entry.name)
            visibility = View.VISIBLE
        }
        binding.files.readFile(
            entry,
            FileViewerView.MAX_VIEW_BYTES,
            onResult = { result -> if (_binding != null) binding.viewer.show(result) },
            onError = { message -> if (_binding != null) binding.viewer.showError(entry.name, message) },
        )
    }

    private val viewerListener = object : FileViewerView.Listener {
        override fun onClose() {
            binding.viewer.visibility = View.GONE
            viewingEntry = null
            (activity as? RefresherHost)?.refresher?.flush("file-viewer-close")
        }

        override fun onDownload() {
            viewingEntry?.let { binding.files.download(it) }
        }
    }

    /** @return true if the back press was consumed. Wired to MainActivity.onBackPressed. */
    fun closeViewerIfOpen(): Boolean {
        if (_binding == null || binding.viewer.visibility != View.VISIBLE) return false
        viewerListener.onClose()
        return true
    }

    /** Entry points for the floating menu (design.md §11.3 cells `⌨ Keys` and `▤ Files`). */
    fun toggleFilesFromMenu() = toggleFiles()

    fun toggleKeyboardFromMenu() = toggleSoftKeyboard()

    fun hasLiveSession(): Boolean = session?.isConnected == true

    private fun toggleFiles() = setFilesOpen(!filesOpen)

    private fun setFilesOpen(open: Boolean) {
        val ssh = session
        if (open && (ssh == null || !ssh.isConnected)) {
            toast(getString(R.string.files_not_connected))
            return
        }

        filesOpen = open
        if (open && sftp == null && ssh != null) {
            sftp = SftpBrowser(ssh).also { binding.files.attach(it) }
        }

        applyPosture()
        if (open) binding.files.open(binding.files.currentPath.ifEmpty { null })

        // Drawer open/close is [F] in design.md §13 — the scrim covers the screen.
        (activity as? RefresherHost)?.refresher?.flush("files-drawer=$open")
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * design.md §7.2 / §7.3. Landscape splits, portrait overlays — never the other way round.
     * A persistent sidebar in portrait would leave the terminal about 45 columns, which §3.3
     * rules out as unusable.
     */
    private fun applyPosture() {
        val landscape = isLandscape()
        val files = binding.files
        val holder = binding.terminalHolder

        val sidebarWidth = resources.getDimensionPixelSize(dev.inkdeck.eink.R.dimen.ink_sidebar_width)
        val drawerWidth = resources.getDimensionPixelSize(dev.inkdeck.eink.R.dimen.ink_drawer_width)

        files.layoutParams = (files.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (landscape) sidebarWidth else minOf(drawerWidth, resources.displayMetrics.widthPixels)
        }
        files.visibility = if (filesOpen) View.VISIBLE else View.GONE

        // In landscape the terminal is pushed aside; in portrait it stays put and is covered.
        holder.layoutParams = (holder.layoutParams as FrameLayout.LayoutParams).apply {
            marginStart = if (landscape && filesOpen) sidebarWidth else 0
        }
        binding.scrim.visibility = if (filesOpen && !landscape) View.VISIBLE else View.GONE

        files.requestLayout()
        holder.requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // MainActivity handles the orientation config change itself, so nothing is re-inflated
        // and the posture has to be re-applied by hand. The PTY follows via TerminalView's
        // onSizeChanged -> gridListener -> SshSession.resize.
        applyPosture()
        (activity as? RefresherHost)?.refresher?.flush("rotation")
    }

    private fun pickFileToUpload() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQUEST_UPLOAD)
        } catch (_: ActivityNotFoundException) {
            // No DocumentsUI on some OEM builds. The adb staging directory is the fallback that
            // always works on this device.
            showMessage("Upload", getString(R.string.files_no_picker))
        }
    }

    @Deprecated("Fragment result API needs activity-result registration; this Fragment is not recreated across config changes, so the classic call is simpler and safe here.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_UPLOAD || resultCode != android.app.Activity.RESULT_OK) return
        val uri = data?.data ?: return

        val name = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
        val stream = runCatching { requireContext().contentResolver.openInputStream(uri) }.getOrNull()
        if (stream == null) {
            showMessage("Upload", "Could not read the selected file.")
            return
        }
        binding.files.uploadFrom(stream, name)
    }

    private fun queryDisplayName(uri: android.net.Uri): String? =
        runCatching {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()

    // ------------------------------------------------------------------ setup flow

    /**
     * One panel that walks vault → import → host → connect, showing only the next actionable
     * step. Not the design.md §7.4 host picker — that screen is Phase 3 work; this is the
     * minimum honest path to a session.
     */
    private fun renderSetup() {
        val container = binding.setup
        container.removeAllViews()
        binding.hostName.text = activeHost?.alias ?: getString(R.string.term_no_host)

        when {
            !vault.isInitialised -> {
                addNote(
                    container,
                    "The vault holds your SSH private keys and API tokens, encrypted with " +
                        "AES-256-GCM.\n\n" +
                        "By default it opens by itself — no passphrase to type. You can turn a " +
                        "passphrase on later in this screen."
                )
                addButton(container, getString(R.string.action_create_vault), primary = true) {
                    createDeviceVault()
                }
                addButton(container, getString(R.string.action_create_vault_pass)) {
                    promptCreateVault()
                }
            }

            // A device-protected vault opens with no interaction, so never show a locked state
            // for one — that would be a prompt with nothing behind it.
            !vault.isUnlocked && vault.opensWithoutPassphrase -> {
                addNote(container, "Opening vault…")
                withVaultWork { vault.unlockAuto() }
            }

            !vault.isUnlocked -> {
                addNote(container, "The vault is locked.")
                addButton(container, getString(R.string.action_unlock_vault), primary = true) {
                    promptUnlockVault()
                }
            }

            else -> renderUnlockedSetup(container)
        }
    }

    private fun renderUnlockedSetup(container: LinearLayout) {
        val hosts = hostStore.load()
        val pending = AdbImport.pending(requireContext())

        if (hosts.isEmpty() || pending.isNotEmpty()) {
            addNote(
                container,
                "Push your key and ssh_config over USB — never through Telegram:\n\n" +
                    "adb push key.pem ${AdbImport.importDir(requireContext()).absolutePath}/\n" +
                    "adb push config ${AdbImport.importDir(requireContext()).absolutePath}/\n\n" +
                    if (pending.isEmpty()) {
                        "Nothing staged right now."
                    } else {
                        "Staged: " + pending.joinToString(", ") { it.name }
                    }
            )
            addButton(
                container,
                getString(R.string.action_import),
                primary = hosts.isEmpty(),
                enabled = pending.isNotEmpty(),
            ) { runImport() }
        }

        renderVaultProtection(container)

        if (hosts.isEmpty()) return

        addHeading(container, "HOSTS")
        hosts.forEach { host ->
            addNote(container, "${host.alias}\n${host.display}\n" + describeIdentity(host))
            addButton(
                container,
                "${getString(R.string.action_connect)} — ${host.alias}",
                primary = true,
                enabled = host.identityVaultId != null,
            ) { connect(host) }
        }
    }

    /**
     * The passphrase toggle, plus an honest statement of what the current mode protects.
     *
     * "Device" is not a synonym for "secure" here: on this hardware the keystore refuses to hold
     * a key and the fallback is a file in app-private storage, which stops other apps and stops
     * nobody with root or adb. Saying so is the point — a vault that overstates itself is worse
     * than one that does not exist, because it changes what the user is willing to put in it.
     */
    private fun renderVaultProtection(container: LinearLayout) {
        addHeading(container, "VAULT")

        val passphraseOn = vault.protection == SecretVault.Protection.PASSPHRASE
        val backing = when (vault.deviceKeySource) {
            "keystore-aes", "keystore-rsa" ->
                "Key held by the hardware keystore — other apps, adb and root cannot read it."
            "local-file" ->
                "Keystore unavailable on this device, so the key is a file in app-private " +
                    "storage. That stops other apps, but not root or adb on a debug build."
            else -> null
        }

        addNote(
            container,
            if (passphraseOn) {
                "Passphrase ON. The vault stays locked until you type it, and the passphrase is " +
                    "never stored anywhere."
            } else {
                "Passphrase OFF — the vault opens by itself.\n" + (backing ?: "")
            },
        )

        if (passphraseOn) {
            addButton(container, getString(R.string.action_passphrase_off)) {
                if (vault.disablePassphrase()) renderSetup()
            }
        } else {
            addButton(container, getString(R.string.action_passphrase_on)) {
                promptEnablePassphrase()
            }
        }
    }

    private fun describeIdentity(host: HostEntry): String {
        val pinned = knownHosts.find(host.hostName, host.port)
        val key = host.identityVaultId?.let { "key: $it" }
            ?: "no key in vault — push ${host.identityFileHint ?: "the .pem"} and re-import"
        val pin = if (pinned == null) "⚠ host key not pinned" else "✓ host key pinned"
        return "$key\n$pin · ${host.strictHostKeyChecking.name.lowercase()}"
    }

    // ------------------------------------------------------------------ vault dialogs

    private fun createDeviceVault() {
        withVaultWork {
            vault.createDeviceProtected()
            true
        }
    }

    private fun promptEnablePassphrase() {
        val first = passwordField(getString(R.string.vault_passphrase_hint))
        val second = passwordField(getString(R.string.vault_confirm_hint))
        showDialog(getString(R.string.vault_enable_title), stack(first, second)) {
            val a = first.text.toString()
            val b = second.text.toString()
            when {
                a.length < 8 -> toast(getString(R.string.vault_too_short))
                a != b -> toast(getString(R.string.vault_mismatch))
                else -> withVaultWork { vault.enablePassphrase(a.toCharArray()) }
            }
        }
    }

    private fun promptCreateVault() {
        val first = passwordField(getString(R.string.vault_passphrase_hint))
        val second = passwordField(getString(R.string.vault_confirm_hint))

        showDialog(getString(R.string.vault_create_title), stack(first, second)) {
            val a = first.text.toString()
            val b = second.text.toString()
            when {
                a.length < 8 -> toast(getString(R.string.vault_too_short))
                a != b -> toast(getString(R.string.vault_mismatch))
                else -> withVaultWork {
                    vault.createPassphraseProtected(a.toCharArray())
                    true
                }
            }
        }
    }

    private fun promptUnlockVault() {
        val field = passwordField(getString(R.string.vault_passphrase_hint))

        showDialog(getString(R.string.vault_unlock_title), stack(field)) {
            withVaultWork(onFalse = getString(R.string.vault_wrong_passphrase)) {
                vault.unlock(field.text.toString().toCharArray())
            }
        }
    }

    /**
     * 120 000 PBKDF2 iterations takes a second or two on this CPU, so it never runs on the main
     * thread. The panel gets one status line rather than a spinner (design.md §14 item 9).
     */
    private fun withVaultWork(onFalse: String? = null, work: () -> Boolean) {
        binding.setup.removeAllViews()
        addNote(binding.setup, getString(R.string.vault_deriving))
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { runCatching(work) }
            result
                .onSuccess { ok -> if (!ok && onFalse != null) toast(onFalse) }
                // Never swallow this. A vault that silently fails to be created looks identical
                // to one that was never attempted, and the cause here turned out to be a
                // platform keystore quirk that no amount of retrying would fix.
                .onFailure { showMessage("Vault error", "${it.javaClass.simpleName}: ${it.message}") }
            renderSetup()
        }
    }

    private fun runImport() {
        viewLifecycleOwner.lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching { AdbImport.importAll(requireContext(), vault, hostStore) }
            }
            report.onSuccess { r ->
                val summary = buildString {
                    append(
                        "Imported ${r.importedKeys.size} key(s), " +
                            "${r.importedHosts.size} host(s), " +
                            "${r.importedSecrets.size} secret(s)."
                    )
                    if (r.importedSecrets.isNotEmpty()) {
                        append("\n\nFrom .env: ").append(r.importedSecrets.joinToString(", "))
                    }
                    if (r.warnings.isNotEmpty()) append("\n\n").append(r.warnings.joinToString("\n"))
                }
                showMessage("Import", summary)
            }.onFailure { showMessage("Import failed", it.message ?: it.javaClass.simpleName) }
            renderSetup()
        }
    }

    // ------------------------------------------------------------------ session

    private fun connect(host: HostEntry) {
        val vaultId = host.identityVaultId ?: return
        activeHost = host

        val term = binding.terminal
        val emu = TerminalEmulator(sink = { bytes -> session?.write(bytes) })
        emu.setDisplay(term)
        term.setVDUBuffer(emu)
        term.input = emu
        emulator = emu

        val ssh = SshSession(host, knownHosts, this)
        ssh.onOutput = { buffer, length -> emu.feed(buffer, length) }
        ssh.onStatus = { status, message ->
            binding.root.post {
                if (_binding != null) onStatus(status, message)
            }
        }
        session = ssh

        showTerminal(true)
        binding.hostName.text = host.alias
        binding.statusText.text = getString(R.string.term_status_connecting)

        // The terminal was GONE while the setup panel was up, so it has no measured size yet and
        // cols/rows are still 0. Waiting one layout pass means the PTY is opened at the real
        // grid instead of an 80x24 guess that vim would then draw against — its status line at
        // row 24 of a 32-row view, wrapping at column 80 of a 60-column one.
        //
        // Getting the size right up front rather than correcting it with a window-change request
        // straight after connect: the immediate resize was enough to kill the session outright.
        term.post {
            if (_binding == null) return@post
            viewLifecycleOwner.lifecycleScope.launch {
                val pem = withContext(Dispatchers.IO) {
                    runCatching { vault.get(vaultId) }.getOrNull()
                }
                if (pem == null) {
                    showMessage("Connect failed", "Could not read '$vaultId' from the vault.")
                    showTerminal(false)
                    return@launch
                }
                ssh.connect(pem, term.cols.coerceAtLeast(1), term.rows.coerceAtLeast(1))
            }
        }
    }

    private fun onStatus(status: SshSession.Status, message: String?) {
        binding.statusText.text = when (status) {
            SshSession.Status.IDLE -> getString(R.string.term_status_idle)
            SshSession.Status.CONNECTING -> getString(R.string.term_status_connecting)
            SshSession.Status.CONNECTED -> getString(R.string.term_status_connected)
            SshSession.Status.DISCONNECTED -> getString(R.string.term_status_disconnected)
            SshSession.Status.FAILED -> getString(R.string.term_status_failed)
        }
        (activity as? RefresherHost)?.refresher?.flush("ssh-status=$status")

        if (status == SshSession.Status.CONNECTED) {
            binding.terminal.requestFocus()
        }
        if (status == SshSession.Status.FAILED && message != null) {
            showMessage("Connection failed", message)
            showTerminal(false)
        }
    }

    private fun reconnect() {
        val host = activeHost ?: return
        // The SFTP channel rides on the session being torn down, so it goes too — a new one is
        // opened lazily the next time the sidebar is used.
        sftp?.close()
        sftp = null
        setFilesOpen(false)
        session?.disconnect()
        session = null
        connect(host)
    }

    private fun showTerminal(visible: Boolean) {
        binding.terminal.visibility = if (visible) View.VISIBLE else View.GONE
        binding.keyRow.visibility = if (visible) View.VISIBLE else View.GONE
        binding.setupScroll.visibility = if (visible) View.GONE else View.VISIBLE
    }

    /**
     * SHOW_FORCED is avoided deliberately: it can leave the keyboard pinned open after the
     * Fragment goes away, and on a 16 fps panel a keyboard that will not dismiss is a real
     * problem rather than a cosmetic one.
     */
    private fun toggleSoftKeyboard() = binding.terminal.toggleKeyboard()

    // ------------------------------------------------------------------ host key approval

    /**
     * Called on the SSH thread and must block until the user decides, so the handshake can
     * continue or abort with the answer. The dialog is posted to the main thread and this thread
     * waits on a latch.
     */
    override fun approveUnknown(request: InkDeckHostKeyRepository.Request): Boolean {
        var approved = false
        val latch = CountDownLatch(1)

        val view = binding.root
        view.post {
            if (_binding == null) {
                latch.countDown()
                return@post
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.hostkey_new_title)
                .setMessage(
                    "${request.host}:${request.port} has not been seen before.\n\n" +
                        "${request.keyType}\n${request.fingerprint}\n\n" +
                        "Pinning it now means a later change — a possible interception — " +
                        "will be caught and reported."
                )
                .setCancelable(false)
                .setPositiveButton(R.string.hostkey_pin) { _, _ ->
                    approved = true
                    latch.countDown()
                }
                .setNegativeButton(R.string.action_cancel) { _, _ -> latch.countDown() }
                .show()
                .also { it.window?.setWindowAnimations(0) }
        }

        // Bounded so a backgrounded app cannot leave the SSH thread parked forever.
        if (!latch.await(HOST_KEY_PROMPT_TIMEOUT_S, TimeUnit.SECONDS)) return false
        return approved
    }

    override fun reportChanged(request: InkDeckHostKeyRepository.Request) {
        binding.root.post {
            if (_binding == null) return@post
            showMessage(
                getString(R.string.hostkey_changed_title),
                "${request.host}:${request.port} presented a different key.\n\n" +
                    "pinned:   ${request.previousFingerprint}\n" +
                    "offered:  ${request.fingerprint}\n\n" +
                    "The connection was refused. This is either a server rebuild or someone " +
                    "between you and it. Verify out of band before removing the pin."
            )
        }
    }

    // ------------------------------------------------------------------ small ui helpers

    private fun addNote(container: LinearLayout, text: String) {
        container.addView(
            TextView(requireContext()).apply {
                this.text = text
                setTextAppearance(dev.inkdeck.eink.R.style.TextAppearance_InkDeck_Body)
                setTextColor(EinkTheme.ink700(requireContext()))
            },
            marginParams(),
        )
    }

    private fun addHeading(container: LinearLayout, text: String) {
        container.addView(
            TextView(requireContext()).apply {
                this.text = text
                setTextAppearance(dev.inkdeck.eink.R.style.TextAppearance_InkDeck_Caption)
                setTextColor(EinkTheme.ink500(requireContext()))
            },
            marginParams(),
        )
    }

    private fun addButton(
        container: LinearLayout,
        label: String,
        primary: Boolean = false,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        container.addView(
            EinkButton(requireContext()).apply {
                text = label
                variant = if (primary) EinkButton.Variant.PRIMARY else EinkButton.Variant.SECONDARY
                isEnabled = enabled
                setOnClickListener { onClick() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(dev.inkdeck.eink.R.dimen.ink_touch_min),
            ).also { it.topMargin = resources.getDimensionPixelSize(dev.inkdeck.eink.R.dimen.ink_space_3) },
        )
    }

    private fun marginParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).also { it.topMargin = resources.getDimensionPixelSize(dev.inkdeck.eink.R.dimen.ink_space_4) }

    /** Vertical column with dialog padding, for the passphrase dialogs. */
    private fun stack(vararg children: View): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = resources.getDimensionPixelSize(dev.inkdeck.eink.R.dimen.ink_screen_margin)
            setPadding(pad, pad, pad, pad)
            children.forEach { addView(it) }
        }

    private fun passwordField(hint: String) = EditText(requireContext()).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setTextAppearance(dev.inkdeck.eink.R.style.TextAppearance_InkDeck_Body)
        minHeight = resources.getDimensionPixelSize(dev.inkdeck.eink.R.dimen.ink_touch_min)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun showDialog(title: String, content: View, onPositive: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(content)
            .setPositiveButton(R.string.action_ok) { _, _ -> onPositive() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
            .also { it.window?.setWindowAnimations(0) }
    }

    private fun showMessage(title: String, message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.action_ok, null)
            .show()
            .also { it.window?.setWindowAnimations(0) }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val HOST_KEY_PROMPT_TIMEOUT_S = 120L
        const val REQUEST_UPLOAD = 4201
    }
}
