package ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import settings.AppSettings

private fun deeplinkType(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd > 0) return url.substring(0, schemeEnd)
    val colonIdx = url.indexOf(':')
    if (colonIdx > 0) return url.substring(0, colonIdx)
    return "other"
}

@Composable
fun DeeplinkScreen(openUrl: suspend (String) -> Result<String>) {
    var feedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Deeplink / URL", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        AnimatedFade(feedback) { (success, msg) ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (success) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                ),
                border = appCardBorder(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = if (success) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        msg,
                        color = if (success) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        DeeplinkSection(openUrl = openUrl, onFeedback = { feedback = it })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeeplinkSection(
    openUrl: suspend (String) -> Result<String>,
    onFeedback: (Pair<Boolean, String>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(AppSettings.deeplinkHistory()) }

    val filtered = remember(url, history) {
        if (url.isBlank()) history else history.filter { it.contains(url, ignoreCase = true) }
    }
    val grouped = remember(filtered) {
        filtered.groupBy { deeplinkType(it) }.toSortedMap()
    }

    fun open(target: String) {
        scope.launch {
            isBusy = true
            val result = openUrl(target)
            isBusy = false
            if (result.isSuccess) {
                AppSettings.addDeeplink(target)
                history = AppSettings.deeplinkHistory()
            }
            onFeedback(
                if (result.isSuccess) true to "Opened: $target"
                else false to "Failed: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    SectionCard("Open deeplink", Icons.Filled.Link) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            ExposedDropdownMenuBox(
                expanded = expanded && filtered.isNotEmpty(),
                onExpandedChange = { expanded = it },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; expanded = true },
                    label = { Text("URL or deeplink") },
                    placeholder = { Text("https://example.com or myapp://path") },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    singleLine = true,
                )
                ExposedDropdownMenu(
                    expanded = expanded && filtered.isNotEmpty(),
                    onDismissRequest = { expanded = false }
                ) {
                    grouped.forEach { (type, entries) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    type,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            onClick = {},
                            enabled = false,
                        )
                        entries.forEach { entry ->
                            DropdownMenuItem(
                                text = { Text(entry, style = MaterialTheme.typography.bodySmall) },
                                onClick = { url = entry; expanded = false }
                            )
                        }
                    }
                }
            }
            Button(
                enabled = !isBusy && url.isNotBlank(),
                onClick = { expanded = false; open(url.trim()) }
            ) {
                Icon(Icons.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open")
            }
            if (isBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }

    if (filtered.isNotEmpty()) {
        SectionCard("History", Icons.Filled.History) {
            grouped.forEach { (type, entries) ->
                Text(
                    type,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                entries.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            entry,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Row {
                            IconButton(onClick = { url = entry }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Edit, "Fill", modifier = Modifier.size(14.dp))
                            }
                            IconButton(enabled = !isBusy, onClick = { open(entry) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.OpenInNew, "Open", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
