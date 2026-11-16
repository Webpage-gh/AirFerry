package com.airferry.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airferry.app.scan.ShutterSpeed

private val BgDark = Color(0xFF0F172A)
private val CardBg = Color(0xFF1E293B)
private val Accent = Color(0xFF3B82F6)
private val TextPrimary = Color(0xFFF1F5F9)
private val TextSecondary = Color(0xFF94A3B8)

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SettingsScreen() }
    }

    @Composable
    private fun SettingsScreen() {
        val prefs = remember { getSharedPreferences("airferry", MODE_PRIVATE) }
        var redundancy by remember { mutableFloatStateOf(prefs.getInt("default_redundancy", 5).toFloat()) }
        var shutter by remember {
            mutableStateOf(
                ShutterSpeed.fromPref(prefs.getString(ShutterSpeed.PREF_KEY, null))
            )
        }

        Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
            Text(
                "设置",
                color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(20.dp)
            )
            HorizontalDivider(color = Color(0xFF334155))

            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("默认冗余率", color = TextSecondary, fontSize = 14.sp)
                    Text("${redundancy.toInt()}%", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = redundancy,
                        onValueChange = { redundancy = it },
                        onValueChangeFinished = {
                            prefs.edit().putInt("default_redundancy", redundancy.toInt()).apply()
                        },
                        valueRange = 5f..50f,
                        steps = 8,
                        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                    )
                }
            }

            ShutterSpeedCard(
                current = shutter,
                onSelect = { picked ->
                    shutter = picked
                    prefs.edit().putString(ShutterSpeed.PREF_KEY, picked.name).apply()
                }
            )

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("易传 AirFerry", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("版本 ${appVersionName()}", color = TextSecondary, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            OutlinedButton(
                onClick = { finish() },
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) { Text("返回", color = TextPrimary, fontSize = 16.sp) }
        }
    }

    /**
     * Shutter-speed presets.
     *
     * Selecting a fixed speed turns auto-exposure off and locks
     * `SENSOR_EXPOSURE_TIME`, which suppresses hand-shake / rolling-shutter smear
     * on the scanned screen. Brightness is then maintained by an auto-ISO loop, so
     * a locked shutter never goes black — but a *fast* shutter needs more ISO than
     * a dim scene can provide, hence the hint text.
     *
     * Presets are filtered against the sensor's exposure range, which the scanner
     * caches into prefs when it binds the camera (see
     * [ShutterSpeed.PREF_EXPOSURE_MIN] / [ShutterSpeed.PREF_EXPOSURE_MAX]).
     * Offering a preset the sensor cannot honor would be worse than useless: it
     * looks applied while the HAL silently clamps it. When the range is still
     * unknown, every preset is shown — the scanner re-validates on apply anyway.
     */
    @Composable
    private fun ShutterSpeedCard(current: ShutterSpeed, onSelect: (ShutterSpeed) -> Unit) {
        val prefs = remember { getSharedPreferences("airferry", MODE_PRIVATE) }
        val options = remember {
            ShutterSpeed.availableFor(
                prefs.getLong(ShutterSpeed.PREF_EXPOSURE_MIN, 0L),
                prefs.getLong(ShutterSpeed.PREF_EXPOSURE_MAX, 0L),
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("快门速度", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "固定快门可减轻手持抖动和画面拖影。选「自动」则交由相机决定。",
                    color = TextSecondary, fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                options.forEach { speed ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(speed) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = speed == current,
                            onClick = { onSelect(speed) },
                            colors = RadioButtonDefaults.colors(selectedColor = Accent)
                        )
                        Text(
                            text = speed.label,
                            color = if (speed == current) TextPrimary else TextSecondary,
                            fontSize = 15.sp,
                            fontWeight = if (speed == current) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
                // If the persisted choice is no longer offered by this device, say so
                // rather than leaving a preselected radio that isn't in the list.
                if (options.none { it == current }) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "（本机不支持 ${current.label}，已回退为自动）",
                        color = TextSecondary, fontSize = 11.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "提示：更快的快门需要更高 ISO；环境偏暗时画面可能变暗。",
                    color = TextSecondary, fontSize = 11.sp
                )
            }
        }
    }

    /**
     * Read the installed version name from the package manifest (the single
     * source of truth — build.gradle.kts `versionName`). Avoids hardcoding a
     * version string that drifts from the actual build (the previous literal
     * "0.1.0" was wrong ever since the v1.0.0 bump).
     */
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
}
