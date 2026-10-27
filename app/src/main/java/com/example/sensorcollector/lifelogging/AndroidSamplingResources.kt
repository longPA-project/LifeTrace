package com.example.sensorcollector.lifelogging

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

class AndroidSamplingResources(context: Context) {
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    fun read(): SamplingResources {
        val thermalState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when {
                powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.HOT
                powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.WARM
                else -> ThermalState.NORMAL
            }
        } else {
            ThermalState.NORMAL
        }
        return SamplingResources(
            batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .takeIf { it in 0..100 },
            isCharging = batteryManager.isCharging,
            powerSaveMode = powerManager.isPowerSaveMode,
            thermalState = thermalState
        )
    }
}
