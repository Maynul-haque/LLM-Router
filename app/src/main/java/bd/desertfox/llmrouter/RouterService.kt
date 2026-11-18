package bd.desertfox.llmrouter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class RouterService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val c = NotificationChannel("router", "LLM Router server", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(c)
            val b = Notification.Builder(this, c.id)
                .setContentTitle("LLM Router running")
                .setContentText("Serving /v1 on 127.0.0.1:" + Store.cfg.optInt("port", 8790))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
            startForeground(1, b.build())
        } else {
            val b = Notification.Builder(this)
                .setContentTitle("LLM Router running")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
            startForeground(1, b.build())
        }
        HttpServerHolder.start(Store.cfg.optInt("port", 8790))
        return START_STICKY
    }

    override fun onDestroy() {
        HttpServerHolder.stop()
        super.onDestroy()
    }
}
