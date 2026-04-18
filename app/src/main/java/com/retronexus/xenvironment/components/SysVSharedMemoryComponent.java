package com.retronexus.xenvironment.components;

import com.retronexus.sysvshm.SysVSHMConnectionHandler;
import com.retronexus.sysvshm.SysVSHMRequestHandler;
import com.retronexus.sysvshm.SysVSharedMemory;
import com.retronexus.xconnector.UnixSocketConfig;
import com.retronexus.xconnector.XConnectorEpoll;
import com.retronexus.xenvironment.EnvironmentComponent;
import com.retronexus.xserver.SHMSegmentManager;
import com.retronexus.xserver.XServer;

public class SysVSharedMemoryComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    public final UnixSocketConfig socketConfig;
    private SysVSharedMemory sysVSharedMemory;
    private final XServer xServer;

    public SysVSharedMemoryComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        if (connector != null) return;
        sysVSharedMemory = new SysVSharedMemory();
        connector = new XConnectorEpoll(socketConfig, new SysVSHMConnectionHandler(sysVSharedMemory), new SysVSHMRequestHandler());
        connector.start();

        xServer.setSHMSegmentManager(new SHMSegmentManager(sysVSharedMemory));
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.destroy();
            connector = null;
        }

        sysVSharedMemory.deleteAll();
    }
}
