package com.retronexus.xserver;

public interface XLock extends AutoCloseable {
    @Override
    void close();
}
