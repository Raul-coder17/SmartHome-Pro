package com.smarthomepro.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SocketBridgePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
