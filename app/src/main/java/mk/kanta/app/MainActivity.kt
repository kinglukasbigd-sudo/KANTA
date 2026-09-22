package mk.kanta.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import mk.kanta.app.core.designsystem.KantaTheme

/**
 * The single activity (spec §2 "Architecture"). Everything else is Compose.
 * Edge-to-edge because the map is the hero and draws behind the system bars.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KantaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KantaNavHost()
                }
            }
        }
    }
}
