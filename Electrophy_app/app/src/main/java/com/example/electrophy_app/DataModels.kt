package com.example.electrophy_app

enum class ConnectionState { Disconnected, Scanning, Connecting, Connected }

enum class GraphMode(val label: String, val command: String) {
    LOW_G("Low-G Accel", "low_acc"),
    HIGH_G("High-G Accel", "high_acc"),
    BOTH_ACC("Both Accel", "both_acc"),
    GYRO("Gyro", "only_gyro"),
    ALL("All Sensors", "all"),
}

data class AxisPoint(val x: Float, val y: Float, val z: Float, val time: Float = 0f)

data class ChartData(
    val lowG: List<AxisPoint> = emptyList(),
    val highG: List<AxisPoint> = emptyList(),
    val gyro: List<AxisPoint> = emptyList(),
)

data class AirplaneAttitude(
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val yawDeg: Float = 0f,
    val gForce: Float = 1f,
    val turnRateDps: Float = 0f
)

data class CalibrationOffsets(
    val accelX: Float = 0f, // in mg
    val accelY: Float = 0f, // in mg
    val accelZ: Float = 0f, // in mg (deviation from ideal 1000mg)
    val gyroX: Float = 0f,  // in mdps
    val gyroY: Float = 0f,  // in mdps
    val gyroZ: Float = 0f,  // in mdps
    val isCalibrated: Boolean = false
)

