package com.github.ddth.queue.impl;

import java.io.Closeable;

import com.github.ddth.queue.IQueue;

/**
 * Abstract queue implementation.
 * 
 * @author Thanh Nguyen <btnguyen2k@gmail.com>
 * @since 0.5.0
 */
public abstract class AbstractQueue implements IQueue, Closeable, AutoCloseable {

    /**
     * Initializing method.
     * 
     * @return
     * @throws Exception
     */
    public abstract AbstractQueue init() throws Exception;

    /**
     * Destroy method.
     */
    public abstract void destroy();

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        destroy();
    }

}
