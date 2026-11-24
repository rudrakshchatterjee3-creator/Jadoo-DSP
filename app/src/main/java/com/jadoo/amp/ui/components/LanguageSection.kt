package com.jadoo.amp.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jadoo.amp.AppLocale
import com.jadoo.amp.R

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
private fun languageName(tag: String?): String = when (tag) {
    "en" -> stringResource(R.string.lang_name_en)
    "de" -> stringResource(R.string.lang_name_de)
    "fr" -> stringResource(R.string.lang_name_fr)
    "pl" -> stringResource(R.string.lang_name_pl)
    "zh-CN" -> stringResource(R.string.lang_name_zh)
    // "Follow system" plus the language the phone resolves to, so the
    // default choice says what it is actually showing.
    else -> stringResource(R.string.lang_system_default) + " · " +
        languageName(AppLocale.systemTag(LocalContext.current))
}

/**
 * Settings > Language. One row showing the current choice, a dialog to change
 * it. The change itself is [AppLocale.select]; the activity recreates and
 * every string (including notifications) follows.
 */
@Composable
fun LanguageSection() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    // Read at composition: the activity is recreated after a change, so this
    // is always current.
    val selected = remember { AppLocale.selectedTag(context) }

    Column {
        GroupHeader(stringResource(R.string.lang_group))
        FeatureGroup {
            NavRow(
                icon = Icons.Default.Language,
                title = stringResource(R.string.lang_title),
                value = languageName(selected),
                onClick = { open = true }
            )
        }
    }

    if (open) {
        val options: List<String?> = listOf<String?>(null) + AppLocale.SUPPORTED
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.lang_dialog_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.lang_dialog_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Column(Modifier.selectableGroup()) {
                        options.forEach { tag ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = tag == selected,
                                        role = Role.RadioButton,
                                        onClick = {
                                            open = false
                                            if (tag != selected) {
                                                context.findActivity()?.let { AppLocale.select(it, tag) }
                                            }
                                        }
                                    )
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = tag == selected, onClick = null)
                                Spacer(Modifier.width(12.dp))
                                Text(languageName(tag), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.dash_cancel)) }
            }
        )
    }
}
