package dev.pocketopencode

import android.app.*
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*

class EngineService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var foreground=false
    override fun onCreate() {
        super.onCreate()
        scope.launch { NativeLanguage.locale.collect {
            if(foreground) getSystemService(NotificationManager::class.java).notify(1,notification())
        } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { Engine.get(this).stop(); stopSelf(); return START_NOT_STICKY }
        startForeground(1,notification())
        foreground=true
        Engine.get(this).start()
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("engine",tr(UiText.LocalOpenCode),NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this,1,Intent(this,EngineService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"engine").setContentTitle("Opencode")
            .setContentText(tr(UiText.EnvironmentRunning)).setSmallIcon(R.drawable.ic_launcher).setContentIntent(open)
            .addAction(Notification.Action.Builder(null,tr(UiText.Stop),stop).build()).setOngoing(true).build()
    }
    override fun onDestroy() { foreground=false; scope.cancel(); Engine.get(this).stop(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
