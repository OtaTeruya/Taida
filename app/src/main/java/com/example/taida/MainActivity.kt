package com.example.taida

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), SensorEventListener {

    // === センサ関連 ===
    private lateinit var sensorManager: SensorManager
    private lateinit var accel: Sensor
    private val sampleRateUs = SensorManager.SENSOR_DELAY_GAME     // ≈ 20 ms

    // === バイブ関連 ===
    private lateinit var vibrator: Vibrator
    private val strongPulse = VibrationEffect.createOneShot(100, 255)
    private var isVibrating by mutableStateOf(false)

    // === 揺れ解析 ===
    private val analyzer = MotionAnalyzer(windowSize = 30)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: throw IllegalStateException("Accelerometer not available")

        sensorManager.registerListener(this, accel, sampleRateUs)
        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isVibrating) Color.Red else Color.White)
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        scope.cancel()
    }

    // === センサコールバック ===
    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]          // X方向の加速度 (端末を縦吊り想定)
        val now = event.timestamp        // ナノ秒

        // 振り子周期と位相を推定
        val phaseInfo = analyzer.update(timeNs = now, accelX = x.toDouble())

        if (phaseInfo.shouldPulse) {
            // 位相 90°手前でパルス予約: delay =  phaseLeadMs
            scope.launch {
                delay(phaseInfo.leadMs)
                vibrator.vibrate(strongPulse)
                isVibrating = true
                delay(100)
                isVibrating = false
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
class MotionAnalyzer(private val windowSize: Int) {

    private val buf = ArrayDeque<Pair<Long, Double>>()   // (timeNs, zAcc)
    private var lastSign = 0
    private val periods = ArrayDeque<Long>()

    data class PhaseResult(val shouldPulse: Boolean, val leadMs: Long = 0)

    fun update(timeNs: Long, accelX: Double): PhaseResult {
        // 低域通過: 重力成分を除去 → 振動成分だけ残す
        val filtered = accelX

        buf.addLast(timeNs to filtered)
        if (buf.size > windowSize) buf.removeFirst()

        val sign = when {
            filtered > 0.1 -> 1     // 適当閾値
            filtered < -0.1 -> -1
            else -> 0
        }

        // 零交差で周期測定
        var shouldPulse = false
        var leadMs = 0L

        if (lastSign < 0 && sign > 0) {          // 下→上への零交差（中心通過）
            if (periods.size >= 3) periods.removeFirst()
            periods.addLast(timeNs)

            if (periods.size >= 2) {
                val periodNs = periods.last() - periods[periods.size - 2]
                val periodMs = periodNs / 1_000_000

                // 最大振幅タイミング ≈ 中心通過の ¼ 周期後
                leadMs = (periodMs * 0.25).toLong()

                shouldPulse = true           // 次の ¼ 周期後にパルス
            }
        }
        lastSign = sign
        return PhaseResult(shouldPulse, leadMs)
    }
}