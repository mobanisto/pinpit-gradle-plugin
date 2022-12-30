import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import simple.ComposeUI

fun main() {
    application {
        Window(onCloseRequest = ::exitApplication, title = "Simple") {
            ComposeUI()
        }
    }
}
