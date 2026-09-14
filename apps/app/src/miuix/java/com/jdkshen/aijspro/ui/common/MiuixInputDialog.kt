package com.jdkshen.aijspro.ui.common

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix 版通用「名称输入」弹窗：标题 + 输入框（可带勾选项）+ 校验错误 + 取消/确定。
 * 行为对齐旧 MaterialDialog 版本：输入为空或校验不通过时「确定」不可点，
 * 校验失败显示红字（重名 = 「文件已存在」，与 [ScriptOperations.validateInput] 同语义）。
 *
 * 返回 true = 已由 Miuix 处理；false = 宿主不是 ComponentActivity，调用方回退旧实现。
 */
object MiuixInputDialog {

    /** 返回非 null 即为错误文案（红字显示并禁用「确定」）。 */
    fun interface Validator {
        fun validate(text: String, checked: Boolean): String?
    }

    fun interface Confirmer {
        fun onConfirm(text: String, checked: Boolean)
    }

    @JvmStatic
    fun show(
        activity: Activity?,
        title: String,
        placeholder: String,
        initialText: String,
        checkBoxText: String?,
        initialChecked: Boolean,
        validate: Validator?,
        confirmText: String,
        cancelText: String,
        onConfirm: Confirmer
    ): Boolean {
        val dialog = MiuixDialogHost.showCard(activity) { dlg ->
            var text by remember { mutableStateOf(TextFieldValue(initialText)) }
            var checked by remember { mutableStateOf(initialChecked) }
            val error = validate?.validate(text.text, checked)
            val enabled = text.text.isNotEmpty() && error == null
            MiuixDialogCard {
                Text(title, fontSize = 20.sp, color = MiuixTheme.colorScheme.onSurface)
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = placeholder
                )
                if (error != null) {
                    Text(error, fontSize = 13.sp, color = Color(0xFFD32F2F))
                }
                if (checkBoxText != null) {
                    Row(
                        Modifier.fillMaxWidth().clickable { checked = !checked },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked, { checked = it })
                        Text(checkBoxText, fontSize = 14.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.12f))
                            .clickable { dlg.dismiss() }
                            .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(cancelText, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                    }
                    Box(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (enabled) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.primary.copy(alpha = 0.35f)
                            )
                            .clickable(enabled = enabled) {
                                onConfirm.onConfirm(text.text, checked)
                                dlg.dismiss()
                            }
                            .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            confirmText,
                            fontSize = 16.sp,
                            color = MiuixTheme.colorScheme.onPrimary.copy(alpha = if (enabled) 1f else 0.7f)
                        )
                    }
                }
            }
        }
        return dialog != null
    }
}
