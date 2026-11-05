package com.offlinechat.app.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinechat.app.core.Protocol
import com.offlinechat.app.engine.ChatEngine
import com.offlinechat.app.ui.components.Avatar
import com.offlinechat.app.ui.theme.GroupColors
import com.offlinechat.app.ui.theme.colorFromArgb
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class CreateGroupViewModel @Inject constructor(private val engine: ChatEngine) : ViewModel() {
    fun create(name: String, color: Int, max: Int, onCreated: (Long) -> Unit) {
        viewModelScope.launch { onCreated(engine.createGroup(name, color, max).id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    onBack: () -> Unit,
    onCreated: (Long) -> Unit,
    viewModel: CreateGroupViewModel = hiltViewModel(),
) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(GroupColors.first()) }
    var max by remember { mutableFloatStateOf(Protocol.DEFAULT_MAX_MEMBERS.toFloat()) }
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("New group") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Avatar(name = name.ifBlank { "Group" }, color = colorFromArgb(color), size = 96.dp, isGroup = true)
            }
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 40) name = it },
                label = { Text("Group name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Colour", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GroupColors.forEachIndexed { index, argb ->
                        val selected = argb == color
                        Box(
                            Modifier
                                .size(34.dp)
                                .background(colorFromArgb(argb), CircleShape)
                                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = argb }
                                .semantics { contentDescription = "Colour ${index + 1}${if (selected) ", selected" else ""}" },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Column {
                Text("Maximum people: ${max.toInt()}", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = max,
                    onValueChange = { max = it },
                    valueRange = 2f..Protocol.HARD_MAX_MEMBERS.toFloat(),
                    steps = Protocol.HARD_MAX_MEMBERS - 3,
                )
                Text(
                    "You are the host: every message goes through your phone, so keep it switched on and nearby. Bluetooth supports up to ${Protocol.HARD_MAX_MEMBERS} people in one group. Fewer people means faster file sharing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            onClick = {
                creating = true
                viewModel.create(name, color, max.toInt(), onCreated)
            },
            enabled = name.isNotBlank() && !creating,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .navigationBarsPadding()
                .height(52.dp),
        ) { Text("Create group") }
    }
}
