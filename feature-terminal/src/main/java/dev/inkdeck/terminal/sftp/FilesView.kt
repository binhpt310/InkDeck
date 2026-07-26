package dev.inkdeck.terminal.sftp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.refresh.EinkRefresher
import dev.inkdeck.eink.widget.EinkButton
import dev.inkdeck.eink.widget.EinkIconButton
import dev.inkdeck.eink.widget.EinkRecyclerView
import dev.inkdeck.terminal.R

/**
 * The SFTP file browser — design.md §7.2 and §7.3.
 *
 * One view serves both postures. Portrait puts it in front of the terminal as a 400 dp overlay
 * drawer; landscape pins it beside the terminal as a 220 dp split. It never squeezes the
 * terminal in portrait: at 572 dp a persistent sidebar would leave ~45 columns, which is not a
 * terminal any more (§3.3).
 *
 * No thumbnails, no previews — type glyphs only (`▲` parent, `▣` directory, `▢` file). Rendering
 * image previews on a 16 fps grayscale panel costs a refresh per row and tells you less than the
 * filename does.
 *
 * Deviation from §5.5: no paged-scroll rail. The rail is 56 dp, which is a quarter of the 220 dp
 * landscape sidebar. Fling is still disabled ([EinkRecyclerView]), so scrolling is drag-only and
 * lands where you put it.
 */
class FilesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        /** "cd here" — send a `cd` to the shell rather than only moving the browser. */
        fun onChangeShellDirectory(path: String)
        fun onRequestClose()
        fun onRequestUpload(remoteDirectory: String)

        /** Tap on a file: show its contents. */
        fun onOpenFile(entry: SftpBrowser.Entry)
        fun onError(message: String)
        fun onInfo(message: String)
    }

    var listener: Listener? = null
    var refresher: EinkRefresher? = null

    private var browser: SftpBrowser? = null

    private val crumbRow = LinearLayout(context)
    private val crumbScroll = HorizontalScrollView(context)
    private val list = EinkRecyclerView(context)
    private val adapter = FileAdapter()
    private val emptyLabel = TextView(context)

    private val dividerPaint = Paint().apply {
        color = EinkTheme.ink200(context)
        strokeWidth = EinkTheme.dp(context, 1f)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(EinkTheme.paper(context))
        // Opaque and clickable so taps cannot fall through to the terminal underneath when this
        // is an overlay.
        isClickable = true

        addView(buildHeader(), LayoutParams(LayoutParams.MATCH_PARENT, barHeight()))
        addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, dividerHeight()))

        addView(buildNavRow(), LayoutParams(LayoutParams.MATCH_PARENT, barHeight()))
        addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, dividerHeight()))

        list.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@FilesView.adapter
        }
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        emptyLabel.apply {
            setTextAppearance(dev.inkdeck.eink.R.style.TextAppearance_InkDeck_Caption)
            gravity = Gravity.CENTER
            visibility = GONE
            setPadding(space(4), space(6), space(4), space(6))
        }
        addView(emptyLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, dividerHeight()))
        addView(buildActions(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    // ---------------------------------------------------------------- api

    fun attach(browser: SftpBrowser) {
        this.browser = browser
    }

    /** Exposed so the file viewer can pull contents through the same channel. */
    fun readFile(
        entry: SftpBrowser.Entry,
        maxBytes: Int,
        onResult: (SftpBrowser.ReadResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        browser?.read(entry, maxBytes, onResult, onError) ?: onError("Not connected.")
    }

    fun open(path: String? = null) {
        val b = browser ?: return
        b.list(
            path,
            onResult = { resolved, entries -> render(resolved, entries) },
            onError = { message ->
                // Leave the breadcrumb showing where we actually still are, not where the failed
                // attempt was headed.
                renderBreadcrumb(b.path)
                listener?.onError(message)
            },
        )
    }

    fun refresh() {
        val b = browser ?: return
        b.refresh(
            onResult = { resolved, entries -> render(resolved, entries) },
            onError = { listener?.onError(it) },
        )
    }

    val currentPath: String get() = browser?.path.orEmpty()

    private fun render(path: String, entries: List<SftpBrowser.Entry>) {
        renderBreadcrumb(path)
        adapter.submit(entries)
        emptyLabel.visibility = if (entries.isEmpty()) VISIBLE else GONE
        emptyLabel.text = context.getString(R.string.files_empty)
        // A whole new listing replaces the viewport, so §13 makes it [F].
        refresher?.flush("sftp-list")
    }

    // ---------------------------------------------------------------- chrome

    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(
            EinkIconButton(context).apply {
                setIconResource(R.drawable.ic_close)
                contentDescription = context.getString(R.string.files_close)
                setOnClickListener { listener?.onRequestClose() }
            },
            LayoutParams(barHeight(), barHeight()),
        )
        addView(
            TextView(context).apply {
                text = context.getString(R.string.files_title)
                setTextAppearance(dev.inkdeck.eink.R.style.TextAppearance_InkDeck_Title2)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            EinkIconButton(context).apply {
                setIconResource(R.drawable.ic_reconnect)
                contentDescription = context.getString(R.string.files_refresh)
                setOnClickListener { refresh() }
            },
            LayoutParams(barHeight(), barHeight()),
        )
    }

    /**
     * Home · Up · breadcrumb.
     *
     * The first cut showed the path as a plain label, which meant the only way out of a
     * directory was the `..` row — one level per tap, and no way to jump. Every segment here is
     * tappable, so `/home/binh/.cache/electron` is one tap from `/home` or from `/`.
     *
     * The breadcrumb scrolls horizontally and is kept pinned to its right-hand end, because the
     * deepest segment is the one you are in and the one you are most likely to want. It costs a
     * 56 dp row, which is real on a 220 dp landscape sidebar — but a file browser you cannot
     * navigate is not worth its width either.
     */
    private fun buildNavRow(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(
            EinkIconButton(context).apply {
                setIconResource(R.drawable.ic_home)
                contentDescription = context.getString(R.string.files_home)
                setOnClickListener { goHome() }
            },
            LayoutParams(navButton(), barHeight()),
        )
        addView(
            EinkIconButton(context).apply {
                setIconResource(R.drawable.ic_up)
                contentDescription = context.getString(R.string.files_up)
                setOnClickListener { goUp() }
            },
            LayoutParams(navButton(), barHeight()),
        )

        crumbRow.orientation = HORIZONTAL
        crumbRow.gravity = Gravity.CENTER_VERTICAL
        crumbScroll.apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(crumbRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            // Long-press anywhere on the path to type one directly — faster than walking to
            // /var/log one tap at a time.
            setOnLongClickListener { promptGoToPath(); true }
        }
        addView(crumbScroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    fun goHome() {
        val home = browser?.homePath.orEmpty()
        open(home.ifEmpty { null })
    }

    fun goUp() {
        val here = currentPath
        if (here.isEmpty() || here == "/") return
        open(parentOf(here))
    }

    private fun promptGoToPath() {
        val field = android.widget.EditText(context).apply {
            setText(currentPath)
            isSingleLine = true
            setTextAppearance(dev.inkdeck.eink.R.style.TextAppearance_InkDeck_MonoUi)
            minHeight = touchMin()
        }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.files_go_to)
            .setView(
                LinearLayout(context).apply {
                    val pad = space(4)
                    setPadding(pad, pad, pad, pad)
                    addView(field)
                }
            )
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val target = field.text.toString().trim()
                if (target.isNotEmpty()) open(target)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
            .also { it.window?.setWindowAnimations(0) }
    }

    /** Rebuild the crumb strip for [path] and scroll it to the deepest segment. */
    private fun renderBreadcrumb(path: String) {
        crumbRow.removeAllViews()

        fun crumb(label: String, target: String, current: Boolean) {
            crumbRow.addView(
                TextView(context).apply {
                    text = label
                    setTextAppearance(dev.inkdeck.eink.R.style.TextAppearance_InkDeck_MonoUi)
                    setTextColor(
                        if (current) EinkTheme.ink900(context) else EinkTheme.ink700(context)
                    )
                    gravity = Gravity.CENTER_VERTICAL
                    isSingleLine = true
                    val h = space(2)
                    setPadding(h, 0, h, 0)
                    minHeight = touchMin()
                    isClickable = !current
                    if (!current) setOnClickListener { open(target) }
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
            )
        }

        val segments = path.split('/').filter { it.isNotEmpty() }
        crumb("/", "/", current = segments.isEmpty())

        var accumulated = ""
        segments.forEachIndexed { index, segment ->
            accumulated += "/$segment"
            // No separator before the first segment — the root crumb is already a "/", and
            // emitting both rendered as "/ / home".
            if (index > 0) {
                crumbRow.addView(
                    TextView(context).apply {
                        text = "/"
                        setTextAppearance(dev.inkdeck.eink.R.style.TextAppearance_InkDeck_MonoUi)
                        setTextColor(EinkTheme.ink300(context))
                        gravity = Gravity.CENTER_VERTICAL
                    },
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
                )
            }
            crumb(segment, accumulated, current = index == segments.lastIndex)
        }

        crumbScroll.post { crumbScroll.fullScroll(FOCUS_RIGHT) }
    }

    private fun buildActions(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        val pad = space(2)
        setPadding(pad, pad, pad, pad)

        fun action(labelRes: Int, onClick: () -> Unit) = EinkButton(context).apply {
            text = context.getString(labelRes)
            setOnClickListener { onClick() }
        }

        addView(
            action(R.string.files_upload) {
                listener?.onRequestUpload(currentPath)
            },
            LayoutParams(0, touchMin(), 1f).also { it.marginEnd = space(1) },
        )
        addView(
            action(R.string.files_new_dir) { promptNewDirectory() },
            LayoutParams(0, touchMin(), 1f).also { it.marginEnd = space(1) },
        )
        addView(
            action(R.string.files_cd) {
                currentPath.takeIf { it.isNotEmpty() }?.let { listener?.onChangeShellDirectory(it) }
            },
            LayoutParams(0, touchMin(), 1f),
        )
    }

    private fun promptNewDirectory() {
        val field = android.widget.EditText(context).apply {
            hint = context.getString(R.string.files_new_dir_hint)
            isSingleLine = true
            setTextAppearance(dev.inkdeck.eink.R.style.TextAppearance_InkDeck_Body)
            minHeight = touchMin()
        }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.files_new_dir)
            .setView(
                LinearLayout(context).apply {
                    val pad = space(4)
                    setPadding(pad, pad, pad, pad)
                    addView(field)
                }
            )
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                browser?.mkdir(
                    name,
                    onDone = { refresh() },
                    onError = { listener?.onError(it) },
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
            .also { it.window?.setWindowAnimations(0) }
    }

    private fun showEntryMenu(entry: SftpBrowser.Entry) {
        if (entry.isParent) return
        val options = arrayOf(
            context.getString(R.string.files_download),
            context.getString(R.string.files_rename),
            context.getString(R.string.files_delete),
        )
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(entry.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> download(entry)
                    1 -> promptRename(entry)
                    2 -> confirmDelete(entry)
                }
            }
            .show()
            .also { it.window?.setWindowAnimations(0) }
    }

    fun download(entry: SftpBrowser.Entry) {
        if (entry.isDirectory) {
            listener?.onError(context.getString(R.string.files_no_dir_download))
            return
        }
        val target = java.io.File(
            context.getExternalFilesDir(null),
            "download/${entry.name}",
        )
        listener?.onInfo(context.getString(R.string.files_downloading, entry.name))
        browser?.download(
            entry,
            target,
            onDone = { file -> listener?.onInfo(context.getString(R.string.files_saved, file.absolutePath)) },
            onError = { listener?.onError(it) },
        )
    }

    private fun promptRename(entry: SftpBrowser.Entry) {
        val field = android.widget.EditText(context).apply {
            setText(entry.name)
            isSingleLine = true
            setTextAppearance(dev.inkdeck.eink.R.style.TextAppearance_InkDeck_Body)
            minHeight = touchMin()
        }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.files_rename)
            .setView(
                LinearLayout(context).apply {
                    val pad = space(4)
                    setPadding(pad, pad, pad, pad)
                    addView(field)
                }
            )
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isEmpty() || name == entry.name) return@setPositiveButton
                browser?.rename(entry.name, name, onDone = { refresh() }, onError = { listener?.onError(it) })
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
            .also { it.window?.setWindowAnimations(0) }
    }

    private fun confirmDelete(entry: SftpBrowser.Entry) {
        val message = if (entry.isDirectory) {
            context.getString(R.string.files_delete_dir_confirm, entry.name)
        } else {
            context.getString(R.string.files_delete_confirm, entry.name)
        }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.files_delete)
            .setMessage(message)
            .setPositiveButton(R.string.files_delete) { _, _ ->
                browser?.delete(entry, onDone = { refresh() }, onError = { listener?.onError(it) })
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
            .also { it.window?.setWindowAnimations(0) }
    }

    fun uploadFrom(stream: java.io.InputStream, name: String) {
        listener?.onInfo(context.getString(R.string.files_uploading, name))
        browser?.upload(
            stream,
            name,
            onDone = {
                listener?.onInfo(context.getString(R.string.files_uploaded, name))
                refresh()
            },
            onError = { listener?.onError(it) },
        )
    }

    // ---------------------------------------------------------------- adapter

    private inner class FileAdapter : RecyclerView.Adapter<FileRow>() {

        private val items = mutableListOf<SftpBrowser.Entry>()

        fun submit(entries: List<SftpBrowser.Entry>) {
            items.clear()
            items += entries
            // No DiffUtil: the animator is null anyway (§14 item 1) and a directory change
            // replaces every row, so a full rebind is both correct and cheaper.
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = FileRow(
            FileRowView(context).apply {
                // LinearLayoutManager's default LayoutParams are WRAP_CONTENT in both axes, so
                // without this a row is only as wide as its filename — and everything to the
                // right of the text is dead space that swallows taps.
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
        )

        override fun onBindViewHolder(holder: FileRow, position: Int) {
            val entry = items[position]
            holder.view.bind(entry)
            holder.view.setOnClickListener {
                when {
                    entry.isParent -> open(parentOf(currentPath))
                    entry.isDirectory -> open("$currentPath/${entry.name}")
                    // Tap opens the file. The actions menu moved to long-press: reading is what
                    // you almost always want, and a menu standing between you and the contents
                    // of every file is friction on every single tap.
                    else -> listener?.onOpenFile(entry)
                }
            }
            holder.view.setOnLongClickListener {
                showEntryMenu(entry)
                true
            }
        }

        override fun getItemCount() = items.size
    }

    private inner class FileRow(val view: FileRowView) : RecyclerView.ViewHolder(view)

    private fun parentOf(path: String): String {
        if (path == "/" || path.isEmpty()) return "/"
        val trimmed = path.trimEnd('/')
        val cut = trimmed.lastIndexOf('/')
        return if (cut <= 0) "/" else trimmed.substring(0, cut)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // Right-hand edge, so the split posture reads as two panes rather than one wide one.
        canvas.drawLine(width - 0.5f, 0f, width - 0.5f, height.toFloat(), dividerPaint)
    }

    private fun divider() = View(context).apply { setBackgroundColor(EinkTheme.ink200(context)) }
    private fun barHeight() = resources.getDimensionPixelSize(dev.inkdeck.eink.R.dimen.ink_bar_height)

    /**
     * 48 dp, under the 56 dp target minimum. Two 56 dp buttons plus a usable breadcrumb do not
     * fit in a 220 dp landscape sidebar; the full 56 dp height is kept, so the shortfall is in
     * the axis where a mis-tap between two adjacent buttons is least likely.
     */
    private fun navButton() = EinkTheme.dp(context, 48f).toInt()
    private fun touchMin() = resources.getDimensionPixelSize(dev.inkdeck.eink.R.dimen.ink_touch_min)
    private fun dividerHeight() = resources.getDimensionPixelSize(dev.inkdeck.eink.R.dimen.ink_divider)
    private fun space(step: Int) = EinkTheme.dp(context, 4f * step).toInt()
}

/** One 56 dp row: glyph, name, size. design.md §5.4. */
private class FileRowView(context: Context) : LinearLayout(context) {

    private val glyph = TextView(context)
    private val name = TextView(context)
    private val size = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = resources.getDimensionPixelSize(dev.inkdeck.eink.R.dimen.ink_row_min)
        isClickable = true
        isFocusable = true
        val pad = EinkTheme.dp(context, 8f).toInt()
        setPadding(pad, 0, pad, 0)

        glyph.apply {
            setTextAppearance(dev.inkdeck.eink.R.style.TextAppearance_InkDeck_Body)
            setTextColor(EinkTheme.ink900(context))
        }
        addView(glyph, LayoutParams(EinkTheme.dp(context, 24f).toInt(), LayoutParams.WRAP_CONTENT))

        name.apply {
            setTextAppearance(dev.inkdeck.eink.R.style.TextAppearance_InkDeck_Body)
            setTextColor(EinkTheme.ink900(context))
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        size.apply {
            setTextAppearance(dev.inkdeck.eink.R.style.TextAppearance_InkDeck_Caption)
        }
        addView(size, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(entry: SftpBrowser.Entry) {
        glyph.text = entry.glyph
        name.text = if (entry.isLink) "${entry.name} →" else entry.name
        size.text = entry.displaySize
    }
}
