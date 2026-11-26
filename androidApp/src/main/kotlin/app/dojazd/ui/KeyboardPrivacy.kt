package app.dojazd.ui

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Asks the keyboard not to learn from what is typed into the text fields of
 * [content], unless [learning] is allowed: the addresses and places searched
 * for say where the user lives and goes. Compose has no setting of its own for
 * this, so the request each field makes to the keyboard is marked on its way.
 * A keyboard is free to ignore the wish; the common ones honour it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun KeyboardPrivacy(
    learning: Boolean,
    content: @Composable () -> Unit,
) {
    val allowed by rememberUpdatedState(learning)
    val interceptor =
        remember {
            PlatformTextInputInterceptor { request, nextHandler ->
                val marked =
                    PlatformTextInputMethodRequest { attributes ->
                        request.createInputConnection(attributes).also {
                            if (!allowed) attributes.imeOptions = attributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        }
                    }
                nextHandler.startInputMethod(marked)
            }
        }
    InterceptPlatformTextInput(interceptor, content)
}
