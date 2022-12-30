package simple

import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ComposeUI(
) {
    Text("Hello Compose Multiplatform", modifier = Modifier.padding(16.dp))
}

