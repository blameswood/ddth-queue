package com.github.ddth.queue.impl;

import com.github.ddth.queue.QueueSpec;

/**
 * Factory to create {@link InmemQueue} instances.
 * 
 * @author Thanh Ba Nguyen <bnguyen2k@gmail.com>
 * @since 0.4.1
 */
public abstract class InmemQueueFactory<T extends InmemQueue> extends AbstractQueueFactory<T> {

    /**
     * {@inheritDoc}
     */
    @Override
    protected void initQueue(InmemQueue queue, QueueSpec spec) {
        Boolean ephemeralDisabled = spec.getField(QueueSpec.FIELD_EPHEMERAL_DISABLED,
                Boolean.class);
        if (ephemeralDisabled != null) {
            queue.setEphemeralDisabled(ephemeralDisabled.booleanValue());
        }

        Integer maxSize = spec.getField(QueueSpec.FIELD_MAX_SIZE, Integer.class);
        if (maxSize != null) {
            queue.setBoundary(maxSize.intValue());
        }

        Integer maxEphemeralSize = spec.getField(QueueSpec.FIELD_EPHEMERAL_MAX_SIZE, Integer.class);
        if (maxEphemeralSize != null) {
            queue.setEphemeralMaxSize(maxEphemeralSize.intValue());
        }

        queue.init();
    }

}
