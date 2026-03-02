/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import androidx.annotation.DrawableRes
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.view

class ButtonsBarUi(override val ctx: Context, private val theme: Theme) : Ui {

    override val root = view(::FlexboxLayout) {
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.SPACE_AROUND
    }

    private fun toolButton(@DrawableRes icon: Int, idString: String) = ToolButton(ctx, icon, theme).also {
        it.tag = idString
    }

    val undoButton = toolButton(R.drawable.ic_baseline_undo_24, "undo").apply {
        contentDescription = ctx.getString(R.string.undo)
    }

    val redoButton = toolButton(R.drawable.ic_baseline_redo_24, "redo").apply {
        contentDescription = ctx.getString(R.string.redo)
    }

    val cursorMoveButton = toolButton(R.drawable.ic_cursor_move, "cursorMove").apply {
        contentDescription = ctx.getString(R.string.text_editing)
    }

    val clipboardButton = toolButton(R.drawable.ic_clipboard, "clipboard").apply {
        contentDescription = ctx.getString(R.string.clipboard)
    }

    val quickPhraseButton = toolButton(R.drawable.ic_baseline_chat_24, "quickPhrase").apply {
        contentDescription = ctx.getString(R.string.quickphrase)
    }

    val moreButton = toolButton(R.drawable.ic_baseline_more_horiz_24, "more").apply {
        contentDescription = ctx.getString(R.string.status_area)
    }

    val voiceButton = toolButton(R.drawable.ic_baseline_keyboard_voice_24, "voice").apply {
        contentDescription = ctx.getString(R.string.switch_to_voice_input)
    }

    private val allButtons by lazy {
        mapOf(
            "undo" to undoButton,
            "redo" to redoButton,
            "cursorMove" to cursorMoveButton,
            "clipboard" to clipboardButton,
            "quickPhrase" to quickPhraseButton,
            "more" to moreButton,
            "voice" to voiceButton
        )
    }

    init {
        val prefs = org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance()
        val orderString = prefs.internal.buttonsBarOrder.getValue()
        val orders = orderString.split(",")
        
        // 按配置的顺序添加按钮
        orders.forEach { key ->
            allButtons[key]?.let { button ->
                val size = ctx.dp(40)
                root.addView(button, FlexboxLayout.LayoutParams(size, size))
            }
        }
        
        // 处理可能新加的或未配置在里面的按钮放在末尾
        allButtons.forEach { (_, button) ->
            if (button.parent == null) {
                val size = ctx.dp(40)
                root.addView(button, FlexboxLayout.LayoutParams(size, size))
            }
        }

        setupDragAndDrop(prefs)
    }

    private fun setupDragAndDrop(prefs: org.fcitx.fcitx5.android.data.prefs.AppPrefs) {
        allButtons.values.forEach { button ->
            button.setOnLongClickListener { v ->
                val shadow = android.view.View.DragShadowBuilder(v)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    v.startDragAndDrop(null, shadow, v, 0)
                } else {
                    @Suppress("DEPRECATION")
                    v.startDrag(null, shadow, v, 0)
                }
                v.alpha = 0f // 拖动时原始 view 隐藏，只留 shadow
                true
            }
        }

        root.setOnDragListener { v, event ->
            val flexbox = v as? FlexboxLayout ?: return@setOnDragListener false
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> {
                    true
                }
                android.view.DragEvent.ACTION_DRAG_ENTERED -> true
                android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                    val draggedView = event.localState as? android.view.View ?: return@setOnDragListener false
                    val x = event.x
                    
                    var hoverIndex = -1
                    for (i in 0 until flexbox.childCount) {
                        val child = flexbox.getChildAt(i)
                        val childCenterX = child.left + child.width / 2
                        if (x < childCenterX) {
                            hoverIndex = i
                            break
                        }
                    }
                    if (hoverIndex == -1) {
                        hoverIndex = flexbox.childCount - 1
                    }
                    
                    val currentIndex = flexbox.indexOfChild(draggedView)
                    if (currentIndex != -1 && currentIndex != hoverIndex) {
                        android.transition.TransitionManager.beginDelayedTransition(flexbox, android.transition.ChangeBounds().apply { duration = 150 })
                        flexbox.removeView(draggedView)
                        val insertIndex = if (hoverIndex > currentIndex) hoverIndex - 1 else hoverIndex
                        flexbox.addView(draggedView, insertIndex)
                    }
                    true
                }
                android.view.DragEvent.ACTION_DROP -> {
                    val draggedView = event.localState as? android.view.View ?: return@setOnDragListener false
                    draggedView.alpha = 1f
                    
                    // 保存新顺序
                    val newOrder = mutableListOf<String>()
                    for (i in 0 until flexbox.childCount) {
                        (flexbox.getChildAt(i).tag as? String)?.let { newOrder.add(it) }
                    }
                    prefs.internal.buttonsBarOrder.setValue(newOrder.joinToString(","))
                    true
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    val draggedView = event.localState as? android.view.View ?: return@setOnDragListener false
                    draggedView.alpha = 1f
                    true
                }
                else -> false
            }
        }
    }
}
