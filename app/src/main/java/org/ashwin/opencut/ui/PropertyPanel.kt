package org.ashwin.opencut.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ashwin.opencut.engine.timeline.Clip
import org.ashwin.opencut.engine.timeline.ColorAdjustments
import org.ashwin.opencut.engine.timeline.VideoTransform

@Composable
fun PropertyPanel(
    selectedClip: Clip?,
    onTransformChanged: (VideoTransform) -> Unit,
    onAdjustmentsChanged: (ColorAdjustments) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(Color(0xFF141416))
            .padding(16.dp)
    ) {
        Text(
            text = "Properties",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (selectedClip == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Select a clip in the timeline to edit properties",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // SECTION 1: CLIP DETAILS CARD
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E20)),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Clip: ${selectedClip.filePath.substringAfterLast("/")}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Duration: ${selectedClip.durationOnTimelineMs} ms",
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                    }
                }

                // SECTION 2: TRANSFORMATIONS (Scale, Rotation, Position)
                PropertySectionHeader(title = "Transform")

                // Scale Factor
                PropertySlider(
                    label = "Scale",
                    value = selectedClip.transform.scaleX,
                    valueRange = 0.1f..3.0f,
                    onValueChange = { scale ->
                        onTransformChanged(selectedClip.transform.copy(scaleX = scale, scaleY = scale))
                    },
                    displayValue = "%.2fx".format(selectedClip.transform.scaleX)
                )

                // Rotation Angle
                PropertySlider(
                    label = "Rotation",
                    value = selectedClip.transform.rotation,
                    valueRange = -180f..180f,
                    onValueChange = { rot ->
                        onTransformChanged(selectedClip.transform.copy(rotation = rot))
                    },
                    displayValue = "%.0f°".format(selectedClip.transform.rotation)
                )

                // X Position Offset
                PropertySlider(
                    label = "Position X",
                    value = selectedClip.transform.translationX,
                    valueRange = -500f..500f,
                    onValueChange = { x ->
                        onTransformChanged(selectedClip.transform.copy(translationX = x))
                    },
                    displayValue = "%.0f px".format(selectedClip.transform.translationX)
                )

                // Y Position Offset
                PropertySlider(
                    label = "Position Y",
                    value = selectedClip.transform.translationY,
                    valueRange = -500f..500f,
                    onValueChange = { y ->
                        onTransformChanged(selectedClip.transform.copy(translationY = y))
                    },
                    displayValue = "%.0f px".format(selectedClip.transform.translationY)
                )

                // SECTION 3: COLOR ADJUSTMENTS (Brightness, Contrast, Saturation)
                PropertySectionHeader(title = "Color Adjustment")

                // Brightness
                PropertySlider(
                    label = "Brightness",
                    value = selectedClip.adjustments.brightness,
                    valueRange = -1.0f..1.0f,
                    onValueChange = { brightness ->
                        onAdjustmentsChanged(selectedClip.adjustments.copy(brightness = brightness))
                    },
                    displayValue = "%+.2f".format(selectedClip.adjustments.brightness)
                )

                // Contrast
                PropertySlider(
                    label = "Contrast",
                    value = selectedClip.adjustments.contrast,
                    valueRange = 0.0f..2.0f,
                    onValueChange = { contrast ->
                        onAdjustmentsChanged(selectedClip.adjustments.copy(contrast = contrast))
                    },
                    displayValue = "%.2fx".format(selectedClip.adjustments.contrast)
                )

                // Saturation
                PropertySlider(
                    label = "Saturation",
                    value = selectedClip.adjustments.saturation,
                    valueRange = 0.0f..2.0f,
                    onValueChange = { saturation ->
                        onAdjustmentsChanged(selectedClip.adjustments.copy(saturation = saturation))
                    },
                    displayValue = "%.2fx".format(selectedClip.adjustments.saturation)
                )
            }
        }
    }
}

@Composable
private fun PropertySectionHeader(title: String) {
    Column {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF007AFF)
        )
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = Color(0xFF2C2C2E))
    }
}

@Composable
private fun PropertySlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    displayValue: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, fontSize = 11.sp, color = Color.LightGray)
            Text(text = displayValue, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF007AFF),
                activeTrackColor = Color(0xFF007AFF),
                inactiveTrackColor = Color(0xFF2C2C2E)
            ),
            modifier = Modifier.height(24.dp)
        )
    }
}
