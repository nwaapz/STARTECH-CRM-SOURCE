package com.example.smsgateway.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.smsgateway.R
import com.example.smsgateway.SettingsStore
import com.example.smsgateway.SmsMessageCatalog
import com.example.smsgateway.SmsMessageDefinition
import com.example.smsgateway.SmsMessageTemplates

@Composable
fun SmsMessagesScreen(
    settingsStore: SettingsStore,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val templates = remember { SmsMessageTemplates.getInstance(settingsStore) }
    var drafts by remember {
        mutableStateOf(
            SmsMessageCatalog.definitions.associate { def ->
                def.id to templates.templateFor(def.id)
            },
        )
    }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun saveAll() {
        templates.validateAll(drafts)?.let { message ->
            errorText = message
            return
        }
        templates.setOverrides(
            drafts.filter { (id, text) ->
                text.trim() != SmsMessageCatalog.definitionFor(id).defaultTemplate
            },
        )
        errorText = null
        Toast.makeText(context, context.getString(R.string.messages_saved), Toast.LENGTH_SHORT).show()
    }

    fun resetAll() {
        templates.resetToDefaults()
        drafts = SmsMessageCatalog.definitions.associate { def ->
            def.id to def.defaultTemplate
        }
        errorText = null
        Toast.makeText(context, context.getString(R.string.messages_reset), Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.messages_screen_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        errorText?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SmsMessageCatalog.categoriesInOrder().forEach { category ->
            Text(
                text = category,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )

            SmsMessageCatalog.definitions
                .filter { it.categoryFa == category }
                .forEach { definition ->
                    MessageTemplateCard(
                        definition = definition,
                        template = drafts[definition.id] ?: definition.defaultTemplate,
                        onTemplateChange = { updated ->
                            drafts = drafts + (definition.id to updated)
                            errorText = null
                        },
                        onReset = {
                            drafts = drafts + (definition.id to definition.defaultTemplate)
                            templates.resetMessage(definition.id)
                            errorText = null
                        },
                    )
                }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = ::saveAll) {
                Text(stringResource(R.string.messages_save))
            }
            TextButton(onClick = ::resetAll) {
                Text(stringResource(R.string.messages_reset_all))
            }
        }
    }
}

@Composable
private fun MessageTemplateCard(
    definition: SmsMessageDefinition,
    template: String,
    onTemplateChange: (String) -> Unit,
    onReset: () -> Unit,
) {
    val preview = SmsMessageTemplates.getInstance(
        SettingsStore.getInstance(LocalContext.current),
    ).resolvePreview(template, definition)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = definition.titleFa, style = MaterialTheme.typography.titleSmall)
            Text(
                text = definition.descriptionFa,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = template,
                onValueChange = onTemplateChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Start),
            )

            if (definition.placeholders.isNotEmpty()) {
                definition.placeholders.forEach { hint ->
                    Text(
                        text = "${hint.token} — ${hint.labelFa}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = stringResource(R.string.messages_preview, preview),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Start,
                )
            }

            TextButton(onClick = onReset) {
                Text(stringResource(R.string.messages_reset_one))
            }
        }
    }
}
