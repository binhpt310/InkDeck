package dev.inkdeck.ai.ui

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.inkdeck.ai.R
import dev.inkdeck.ai.store.AiProfile
import dev.inkdeck.ai.store.ProfileStore
import dev.inkdeck.ai.store.ProviderKind
import dev.inkdeck.eink.EinkAnim
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.refresh.EinkRefresher
import dev.inkdeck.eink.widget.EinkButton
import dev.inkdeck.eink.widget.EinkIconButton
import dev.inkdeck.eink.widget.EinkScrollView
import dev.inkdeck.eink.widget.PagedScrollRail
import dev.inkdeck.eink.widget.SegmentedControl
import dev.inkdeck.eink.R as EinkR

/**
 * BYOK provider settings — design.md §10.1.
 *
 * A full-bleed overlay inside the AI tab, not a second Activity and not a back-stack Fragment:
 * same reasoning as the task editor in design.md §8.3. An Activity transition is a window
 * animation the panel renders as a wipe, and the tab bar underneath must not move.
 *
 * ## What this screen deliberately cannot do
 *
 * **Show or copy a key.** The list draws [AiProfile.keyHint] — a masked tail computed once when
 * the key was stored — so drawing this screen never opens the vault at all. There is no reveal
 * affordance, because there is no use for one that is not also the fastest way to leak a key off
 * a device you are holding in a café. Replacing a key means pasting a new one.
 */
class ProviderSettingsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onUse(profile: AiProfile)

        /** @param key null means "leave the stored key alone". Zeroed by the receiver. */
        fun onSave(
            existing: AiProfile?,
            name: String,
            baseUrl: String,
            model: String,
            kind: ProviderKind,
            key: CharArray?,
        )

        fun onDelete(profile: AiProfile)

        /** The vault is passphrase-protected and locked; the host owns the prompt. */
        fun onUnlockRequested()

        fun onClose()
    }

    var listener: Listener? = null
    var refresher: EinkRefresher? = null

    private val titleView = TextView(context)
    private val addButton = EinkIconButton(context)
    private val column = LinearLayout(context)
    private val scroll = EinkScrollView(context)
    private val rail = PagedScrollRail(context)

    private val margin = resources.getDimensionPixelSize(EinkR.dimen.ink_screen_margin)
    private val gap = resources.getDimensionPixelSize(EinkR.dimen.ink_space_2)
    private val touch = resources.getDimensionPixelSize(EinkR.dimen.ink_touch_min)

    private var profiles: List<AiProfile> = emptyList()
    private var activeId: String? = null
    private var vaultLocked = false

    init {
        orientation = VERTICAL
        setBackgroundColor(EinkTheme.paper(context))
        // Swallow taps so they cannot reach the chat underneath while this is up.
        isClickable = true

        addView(buildHeader(), LayoutParams(LayoutParams.MATCH_PARENT, touch))
        addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, dividerHeight()))

        column.orientation = VERTICAL
        column.setPadding(margin, margin, margin, margin)
        scroll.addView(
            column,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        val stack = FrameLayout(context)
        stack.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        stack.addView(
            rail,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = gap
            },
        )
        // Content must end before the floating rail or its right edge sits underneath.
        scroll.setPadding(0, 0, resources.getDimensionPixelSize(EinkR.dimen.ink_rail_width), 0)
        scroll.clipToPadding = true
        rail.attach(scroll)

        addView(stack, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        EinkAnim.strip(this)
    }

    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            EinkIconButton(context).apply {
                setIconResource(R.drawable.ic_ai_back)
                contentDescription = context.getString(R.string.ai_back)
                setOnClickListener { onBack() }
            },
            LayoutParams(touch, touch),
        )
        titleView.apply {
            setTextAppearance(EinkR.style.TextAppearance_InkDeck_Title2)
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(titleView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addButton.apply {
            setIconResource(R.drawable.ic_ai_add)
            contentDescription = context.getString(R.string.ai_provider_add)
            setOnClickListener { showEditor(null) }
        }
        header.addView(addButton, LayoutParams(touch, touch))
        return header
    }

    // ---------------------------------------------------------------- entry points

    /** Show the list. [refresh] is also the way the host tells us a save landed. */
    fun refresh(store: ProfileStore) {
        profiles = store.all()
        activeId = store.active()?.id
        vaultLocked = dev.inkdeck.ai.AiGraph.needsPassphrase(context)
        if (editing) showEditor(editingProfile) else showList()
    }

    /** @return true if the press was consumed. */
    fun onBack(): Boolean {
        if (editing) {
            showList()
            refresher?.flush("ai-provider-editor-close")
            return true
        }
        listener?.onClose()
        return true
    }

    private var editing = false
    private var editingProfile: AiProfile? = null

    // ---------------------------------------------------------------- list

    private fun showList() {
        editing = false
        editingProfile = null
        titleView.text = context.getString(R.string.ai_providers_title)
        addButton.visibility = VISIBLE

        column.removeAllViews()

        if (vaultLocked) {
            column.addView(note(context.getString(R.string.ai_vault_locked_note)))
            column.addView(
                EinkButton(context).apply {
                    text = context.getString(R.string.ai_vault_unlock)
                    variant = EinkButton.Variant.PRIMARY
                    setOnClickListener { listener?.onUnlockRequested() }
                },
                LayoutParams(LayoutParams.MATCH_PARENT, touch).also { it.bottomMargin = margin },
            )
        }

        profiles.forEach { column.addView(card(it), cardParams()) }

        if (profiles.isEmpty()) {
            column.addView(note(context.getString(R.string.ai_providers_empty)))
        }

        column.addView(note(context.getString(R.string.ai_providers_footer)))
        scroll.scrollTo(0, 0)
    }

    private fun cardParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        .also { it.bottomMargin = gap * 2 }

    private fun card(profile: AiProfile): LinearLayout {
        val active = profile.id == activeId
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundResource(R.drawable.bg_ai_card)
            val p = resources.getDimensionPixelSize(EinkR.dimen.ink_card_padding)
            setPadding(p, p, p, p)
        }

        // The active marker is a filled vs hollow glyph, never a tint: §14 item 3, and on a
        // five-step ramp two pale fills are the same texture once dithered.
        card.addView(
            TextView(context).apply {
                setTextAppearance(EinkR.style.TextAppearance_InkDeck_BodyLarge)
                text = context.getString(
                    if (active) R.string.ai_profile_row_active else R.string.ai_profile_row,
                    profile.name,
                )
            }
        )
        card.addView(mono(profile.baseUrl))
        card.addView(mono(profile.model))
        card.addView(
            caption(
                if (profile.hasKey) {
                    context.getString(R.string.ai_profile_key_vault, profile.keyHint)
                } else {
                    context.getString(R.string.ai_profile_key_none)
                }
            )
        )

        val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, gap, 0, 0)
        }
        if (!active) {
            actions.addView(
                EinkButton(context).apply {
                    text = context.getString(R.string.ai_profile_use)
                    variant = EinkButton.Variant.PRIMARY
                    setOnClickListener { listener?.onUse(profile) }
                },
                LayoutParams(0, touch, 1f).also { it.marginEnd = gap },
            )
        }
        actions.addView(
            EinkButton(context).apply {
                text = context.getString(R.string.ai_profile_edit)
                setOnClickListener { showEditor(profile) }
            },
            LayoutParams(0, touch, 1f),
        )
        card.addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        card.contentDescription = context.getString(
            if (active) R.string.ai_a11y_profile_active else R.string.ai_a11y_profile,
            profile.name,
            profile.model,
        )
        return card
    }

    // ---------------------------------------------------------------- editor

    private fun showEditor(profile: AiProfile?) {
        editing = true
        editingProfile = profile
        titleView.text = context.getString(
            if (profile == null) R.string.ai_provider_new else R.string.ai_provider_edit
        )
        addButton.visibility = GONE

        column.removeAllViews()

        val kind = SegmentedControl(context).apply {
            segments = listOf(
                context.getString(R.string.ai_kind_openai),
                context.getString(R.string.ai_kind_anthropic),
            )
            selectedIndex = if (profile?.kind == ProviderKind.ANTHROPIC) 1 else 0
        }

        val name = field(profile?.name.orEmpty(), R.string.ai_field_name_hint)
        val baseUrl = field(
            profile?.baseUrl ?: ProfileStore.DEFAULT_ANTHROPIC_BASE,
            R.string.ai_field_base_hint,
            uri = true,
        )
        val model = field(
            profile?.model ?: ProfileStore.DEFAULT_ANTHROPIC_MODEL,
            R.string.ai_field_model_hint,
        )
        val key = field("", R.string.ai_field_key_hint, password = true)

        // Switching kind rewrites the two fields the user is least able to guess — but only when
        // they still hold a default, never over something typed.
        kind.onSelected = { index ->
            val anthropic = index == 1
            if (baseUrl.text.toString() in DEFAULT_BASES) {
                baseUrl.setText(
                    if (anthropic) ProfileStore.DEFAULT_ANTHROPIC_BASE
                    else ProfileStore.DEFAULT_OPENAI_BASE
                )
            }
        }

        column.addView(label(R.string.ai_field_name))
        column.addView(name, fieldParams())
        column.addView(label(R.string.ai_field_kind))
        column.addView(kind, LayoutParams(LayoutParams.MATCH_PARENT, touch).also { it.bottomMargin = gap })
        column.addView(label(R.string.ai_field_base))
        column.addView(baseUrl, fieldParams())
        column.addView(label(R.string.ai_field_model))
        column.addView(model, fieldParams())
        column.addView(label(R.string.ai_field_key))
        column.addView(key, fieldParams())
        column.addView(
            caption(
                context.getString(
                    if (profile?.hasKey == true) {
                        R.string.ai_field_key_replace
                    } else {
                        R.string.ai_field_key_new
                    }
                )
            )
        )

        column.addView(
            EinkButton(context).apply {
                text = context.getString(R.string.ai_save)
                variant = EinkButton.Variant.PRIMARY
                setOnClickListener {
                    val typed = key.text.toString()
                    listener?.onSave(
                        existing = profile,
                        name = name.text.toString().trim(),
                        baseUrl = baseUrl.text.toString().trim(),
                        model = model.text.toString().trim(),
                        kind = if (kind.selectedIndex == 1) {
                            ProviderKind.ANTHROPIC
                        } else {
                            ProviderKind.OPENAI_COMPATIBLE
                        },
                        key = typed.ifEmpty { null }?.toCharArray(),
                    )
                    // The EditText keeps an editable copy of whatever was pasted. Clearing it is
                    // not a guarantee — the String above and the IME's own clipboard history are
                    // outside our reach — but leaving a key sitting in a live view is a choice.
                    key.setText("")
                }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, touch).also { it.topMargin = margin },
        )

        if (profile != null) {
            column.addView(
                EinkButton(context).apply {
                    text = context.getString(R.string.ai_profile_delete)
                    setOnClickListener { confirmDelete(profile) }
                },
                LayoutParams(LayoutParams.MATCH_PARENT, touch).also { it.topMargin = gap },
            )
        }

        scroll.scrollTo(0, 0)
    }

    private fun confirmDelete(profile: AiProfile) {
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.ai_profile_delete)
            .setMessage(context.getString(R.string.ai_profile_delete_confirm, profile.name))
            .setPositiveButton(R.string.ai_profile_delete) { _, _ -> listener?.onDelete(profile) }
            .setNegativeButton(EinkR.string.ink_cancel, null)
            .show()
            .also { it.window?.setWindowAnimations(0) }
    }

    // ---------------------------------------------------------------- small builders

    private fun fieldParams() =
        LayoutParams(LayoutParams.MATCH_PARENT, touch).also { it.bottomMargin = gap }

    private fun field(
        value: String,
        hint: Int,
        password: Boolean = false,
        uri: Boolean = false,
    ) = EditText(context).apply {
        setTextAppearance(EinkR.style.TextAppearance_InkDeck_Body)
        setBackgroundResource(R.drawable.bg_ai_input)
        val p = resources.getDimensionPixelSize(EinkR.dimen.ink_space_3)
        setPadding(p, 0, p, 0)
        setHint(hint)
        setText(value)
        // A blinking caret is a 500 ms animation, i.e. a panel refresh twice a second for as long
        // as the field holds focus — design.md §8.3.
        isCursorVisible = false
        isSingleLine = true
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            uri -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
    }

    private fun label(res: Int) = TextView(context).apply {
        setTextAppearance(EinkR.style.TextAppearance_InkDeck_Caption)
        setText(res)
        setPadding(0, gap, 0, gap / 2)
    }

    private fun caption(text: String) = TextView(context).apply {
        setTextAppearance(EinkR.style.TextAppearance_InkDeck_Caption)
        this.text = text
        setPadding(0, gap / 2, 0, 0)
    }

    private fun mono(text: String) = TextView(context).apply {
        setTextAppearance(EinkR.style.TextAppearance_InkDeck_MonoUi)
        this.text = text
    }

    private fun note(text: String) = TextView(context).apply {
        setTextAppearance(EinkR.style.TextAppearance_InkDeck_Caption)
        this.text = text
        setPadding(0, gap, 0, gap)
    }

    private fun divider() = android.view.View(context).apply {
        setBackgroundColor(EinkTheme.ink200(context))
    }

    private fun dividerHeight() = resources.getDimensionPixelSize(EinkR.dimen.ink_divider)

    private companion object {
        val DEFAULT_BASES = setOf(
            ProfileStore.DEFAULT_ANTHROPIC_BASE,
            ProfileStore.DEFAULT_OPENAI_BASE,
            "",
        )
    }
}
