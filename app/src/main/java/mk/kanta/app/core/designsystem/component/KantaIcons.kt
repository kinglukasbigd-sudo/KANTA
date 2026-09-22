package mk.kanta.app.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Spec §3.3: "rounded outline icon set (Material Symbols Rounded), 24dp".
 *
 * Named by what they mean in Kanta rather than by glyph, so swapping a glyph later is a
 * one-line change here instead of a search across every screen.
 */
object KantaIcons {
    // --- The three bottom-sheet actions (§4.1) ---
    val Full: ImageVector = Icons.Rounded.Delete
    val Report: ImageVector = Icons.Rounded.ReportProblem
    val Suggest: ImageVector = Icons.Rounded.AddLocationAlt

    // --- Report kinds (§4.3) ---
    val Burning: ImageVector = Icons.Rounded.LocalFireDepartment
    val Camera: ImageVector = Icons.Rounded.CameraAlt

    // --- Map & navigation ---
    val MyLocation: ImageVector = Icons.Rounded.MyLocation
    val Place: ImageVector = Icons.Rounded.Place
    val Navigate: ImageVector = Icons.Rounded.Navigation

    // --- Sheet sections (§4.1) ---
    val Profile: ImageVector = Icons.Rounded.Person
    val Stats: ImageVector = Icons.Rounded.Insights
    val Settings: ImageVector = Icons.Rounded.Settings

    // --- States & feedback (§4.5 screen 14) ---
    val Empty: ImageVector = Icons.Rounded.Inbox
    val Error: ImageVector = Icons.Rounded.ErrorOutline
    val Offline: ImageVector = Icons.Rounded.CloudOff
    val Retry: ImageVector = Icons.Rounded.Refresh
    val Success: ImageVector = Icons.Rounded.Check
    val Send: ImageVector = Icons.Rounded.Send
    val Add: ImageVector = Icons.Rounded.Add
    val ChevronRight: ImageVector = Icons.Rounded.ChevronRight
}
