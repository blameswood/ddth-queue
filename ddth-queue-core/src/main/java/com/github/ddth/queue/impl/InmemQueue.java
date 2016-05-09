package com.github.ddth.queue.impl;

import java.io.Closeable;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;

import com.github.ddth.queue.IQueue;
import com.github.ddth.queue.IQueueMessage;

/**
 * In-Memory implementation of {@link IQueue}.
 * 
 * <p>
 * Implementation:
 * <ul>
 * <li>A {@link Queue} as queue storage.</li>
 * <li>A {@link ConcurrentMap} as ephemeral storage.</li>
 * </ul>
 * </p>
 * 
 * @author Thanh Ba Nguyen <bnguyen2k@gmail.com>
 * @since 0.4.0
 */
public class InmemQueue implements IQueue, Closeable, AutoCloseable {

    private Queue<IQueueMessage> queue;
    private ConcurrentMap<Object, IQueueMessage> ephemeralStorage;
    private boolean ephemeralDisabled = false;

    /**
     * A value less than {@code 1} mean "no boundary".
     */
    private int boundary = -1;

    public InmemQueue() {
    }

    public InmemQueue(int boundary) {
        setBoundary(boundary);
    }

    /**
     * Is ephemeral storage disabled?
     * 
     * @return
     */
    public boolean getEphemeralDisabled() {
        return ephemeralDisabled;
    }

    /**
     * Is ephemeral storage disabled?
     * 
     * @return
     */
    public boolean isEphemeralDisabled() {
        return ephemeralDisabled;
    }

    /**
     * Disables/Enables ephemeral storage.
     * 
     * @param ephemeralDisabled
     *            {@code true} to disable ephemeral storage, {@code false}
     *            otherwise.
     * @return
     */
    public InmemQueue setEphemeralDisabled(boolean ephemeralDisabled) {
        this.ephemeralDisabled = ephemeralDisabled;
        return this;
    }

    /**
     * Gets queue's boundary (max number of elements).
     * 
     * @return
     */
    public int getBoundary() {
        return boundary;
    }

    /**
     * Sets queue's boundary (max number of elements).
     * 
     * @param boundary
     *            queue's max number of elements, a value less than {@code 1}
     *            mean "no boundary".
     * @return
     */
    public InmemQueue setBoundary(int boundary) {
        this.boundary = boundary;
        return this;
    }

    /**
     * This method will create a {@link Queue} instance with the following
     * rules:
     * 
     * <ul>
     * <li>If {@link #boundary} is set and larger than {@code 1024}, a
     * {@link LinkedBlockingQueue} is created; if {@link #boundary} is less than
     * or equals to {@code 1024}, an {@link ArrayBlockingQueue} is created
     * instead.</li>
     * <li>Otherwise, a {@link ConcurrentLinkedQueue} is created.</li>
     * </ul>
     * 
     * @param boundary
     * @return
     */
    protected Queue<IQueueMessage> createQueue(int boundary) {
        if (boundary > 0) {
            if (boundary > 1024) {
                return new LinkedBlockingQueue<>(boundary);
            } else {
                return new ArrayBlockingQueue<>(boundary);
            }
        } else {
            return new ConcurrentLinkedQueue<>();
        }
    }

    /**
     * Init method.
     * 
     * @return
     */
    public InmemQueue init() {
        queue = createQueue(boundary);
        if (!ephemeralDisabled) {
            ephemeralStorage = new ConcurrentHashMap<>();
        }
        return this;
    }

    /**
     * Destroy method.
     */
    public void destroy() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        destroy();
    }

    /**
     * Puts a message to the internal queue.
     * 
     * @param msg
     * @return
     */
    protected boolean putToQueue(IQueueMessage msg) {
        return queue.offer(msg);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean queue(IQueueMessage _msg) {
        IQueueMessage msg = _msg.clone();
        Date now = new Date();
        msg.qNumRequeues(0).qOriginalTimestamp(now).qTimestamp(now);
        return putToQueue(msg);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean requeue(IQueueMessage _msg) {
        IQueueMessage msg = _msg.clone();
        Date now = new Date();
        msg.qIncNumRequeues().qTimestamp(now);
        if (putToQueue(msg)) {
            if (!ephemeralDisabled) {
                ephemeralStorage.remove(msg.qId());
            }
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean requeueSilent(IQueueMessage msg) {
        if (putToQueue(msg)) {
            if (!ephemeralDisabled) {
                ephemeralStorage.remove(msg.qId());
            }
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void finish(IQueueMessage msg) {
        if (!ephemeralDisabled) {
            ephemeralStorage.remove(msg.qId());
        }
    }

    /**
     * Takes a message from the internal queue.
     * 
     * @return
     */
    protected IQueueMessage takeFromQueue() {
        return queue.poll();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IQueueMessage take() {
        IQueueMessage msg = takeFromQueue();
        if (msg != null && !ephemeralDisabled) {
            ephemeralStorage.putIfAbsent(msg.qId(), msg);
        }
        return msg;
    }

    /**
     * {@inheritDoc}
     * 
     * @param thresholdTimestampMs
     * @return
     */
    @Override
    public Collection<IQueueMessage> getOrphanMessages(long thresholdTimestampMs) {
        if (ephemeralDisabled) {
            return null;
        }
        Collection<IQueueMessage> orphanMessages = new HashSet<>();
        long now = System.currentTimeMillis();
        for (Entry<?, IQueueMessage> entry : ephemeralStorage.entrySet()) {
            IQueueMessage msg = entry.getValue();
            if (msg.qOriginalTimestamp().getTime() + thresholdTimestampMs < now) {
                orphanMessages.add(msg);
            }
        }
        return orphanMessages;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean moveFromEphemeralToQueueStorage(IQueueMessage _msg) {
        if (!ephemeralDisabled) {
            IQueueMessage msg = ephemeralStorage.remove(_msg.qId());
            if (msg != null) {
                if (putToQueue(msg)) {
                    return true;
                } else {
                    ephemeralStorage.putIfAbsent(msg.qId(), msg);
                }
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int queueSize() {
        return queue.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int ephemeralSize() {
        return ephemeralDisabled ? -1 : ephemeralStorage.size();
    }
}