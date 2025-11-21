package com.example.demoplayvideo


import kotlinx.coroutines.*
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

class SendDataMonitor {
    private val sendCount = AtomicLong(0)
    private var monitorJob: Job? = null
     private val TAG = "LiveStreamActivity"

    // Wrapper cho hàm sendData()
    suspend fun sendData() {
        // Tăng counter
        sendCount.incrementAndGet()

        // Gọi hàm sendData() gốc của bạn ở đây
        // Ví dụ: actualSendData()
    }

    // Bắt đầu monitor
    fun startMonitoring(scope: CoroutineScope, text: String) {
        monitorJob = scope.launch {
            var lastCount = 0L

            while (isActive) {
                delay(10000) // Đợi 5 giây

                val currentCount = sendCount.get()
                val sentInLast5Sec = currentCount - lastCount

                Log.e(TAG,
                    "Total ${text}: $currentCount | Last 10s: $sentInLast5Sec | Rate: ${sentInLast5Sec / 10.0}/s")

                lastCount = currentCount
            }
        }
    }

    // Dừng monitor
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    // Reset counter
    fun resetCounter() {
        sendCount.set(0)
        Log.d(TAG, "Counter reset")
    }

    // Lấy tổng số lần gửi
    fun getTotalSent(): Long = sendCount.get()
}