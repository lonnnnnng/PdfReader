package com.loooong.reader

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.loooong.reader.ui.EbookApp
import com.loooong.reader.ui.theme.InspectorTheme
import com.loooong.reader.ui.theme.rememberThemeState

internal enum class EbookPageTurn {
    BACKWARD,
    FORWARD,
}

internal fun ebookPageTurnForKeyCode(keyCode: Int): EbookPageTurn? = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_UP -> EbookPageTurn.BACKWARD
    KeyEvent.KEYCODE_VOLUME_DOWN -> EbookPageTurn.FORWARD
    else -> null
}

class EbookActivity : FragmentActivity() {
    private val viewModel: EbookViewModel by viewModels()
    private var volumePageTurnHandler: ((EbookPageTurn) -> Unit)? = null

    internal fun registerVolumePageTurnHandler(handler: (EbookPageTurn) -> Unit): () -> Unit {
        volumePageTurnHandler = handler
        return {
            if (volumePageTurnHandler === handler) volumePageTurnHandler = null
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val pageTurn = ebookPageTurnForKeyCode(keyCode)
        val handler = volumePageTurnHandler
        if (pageTurn != null && handler != null) {
            // long: 长按音量键会持续产生 ACTION_DOWN；每次实体按压只翻一屏，避免一本书瞬间跨过多页。
            if (event.repeatCount == 0) handler(pageTurn)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (ebookPageTurnForKeyCode(keyCode) != null && volumePageTurnHandler != null) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // long: Readium Navigator 依赖运行时 Publication，系统不能用默认构造器恢复它；旋转后由 ViewModel 重新挂载并恢复 Locator。
        super.onCreate(null)
        enableEdgeToEdge()
        val initialUri = intent?.data
        // long: 仅在系统重建 Activity 时恢复最近阅读，正常进入电子书入口仍先展示书架。
        val restoreHistory = savedInstanceState != null
        setContent {
            val themeState = rememberThemeState()
            InspectorTheme(
                mode = themeState.mode,
                dynamicColor = themeState.dynamic,
                accent = themeState.accent,
            ) {
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri -> uri?.let { viewModel.openUri(this, it) } }
                EbookApp(
                    activity = this,
                    viewModel = viewModel,
                    initialUri = initialUri,
                    restoreHistory = restoreHistory,
                    onChooseFile = {
                        launcher.launch(
                            arrayOf("application/epub+zip", "text/plain", "application/octet-stream"),
                        )
                    },
                )
            }
        }
    }
}
