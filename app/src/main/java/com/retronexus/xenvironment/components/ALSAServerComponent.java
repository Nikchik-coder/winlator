package com.retronexus.xenvironment.components;

import com.retronexus.alsaserver.ALSAClient;
import com.retronexus.alsaserver.ALSAClientConnectionHandler;
import com.retronexus.alsaserver.ALSARequestHandler;
import com.retronexus.xconnector.UnixSocketConfig;
import com.retronexus.xconnector.XConnectorEpoll;
import com.retronexus.xenvironment.EnvironmentComponent;

public class ALSAServerComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    private final UnixSocketConfig socketConfig;
    private final ALSAClient.Options options;

    public ALSAServerComponent(UnixSocketConfig socketConfig, ALSAClient.Options options) {
        this.socketConfig = socketConfig;
        this.options = options;
    }

    @Override
    public void start() {
        if (connector != null) return;
        ALSAClient.assignFramesPerBuffer(environment.getContext());
        connector = new XConnectorEpoll(socketConfig, new ALSAClientConnectionHandler(options), new ALSARequestHandler());
        connector.setMultithreadedClients(true);
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.destroy();
            connector = null;
        }
    }
}
