package com.github.ddth.queue.impl;

import java.util.Collection;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.ddth.kafka.KafkaClient;
import com.github.ddth.kafka.KafkaClient.ProducerType;
import com.github.ddth.kafka.KafkaMessage;
import com.github.ddth.queue.IPartitionSupport;
import com.github.ddth.queue.IQueue;
import com.github.ddth.queue.IQueueMessage;
import com.github.ddth.queue.utils.QueueException;

/**
 * (Experimental) Kafka implementation of {@link IQueue}.
 * 
 * @author Thanh Ba Nguyen <bnguyen2k@gmail.com>
 * @since 0.3.2
 */
public abstract class KafkaQueue extends AbstractQueue {

    private final Logger LOGGER = LoggerFactory.getLogger(KafkaQueue.class);

    private KafkaClient kafkaClient;
    private boolean myOwnKafkaClient = true;

    private String bootstrapServers = "localhost:9092";
    private String topicName = "ddth-queue";
    private String consumerGroupId = "kafkaqueue-" + System.currentTimeMillis();
    private ProducerType producerType = ProducerType.LEADER_ACK;
    private Properties producerProps, consumerProps;
    private boolean sendAsync = true;

    /**
     * Sends message to Kafka asynchronously or not (default {@code true}).
     * 
     * @return
     * @since 0.5.0
     */
    public boolean isSendAsync() {
        return sendAsync;
    }

    /**
     * Sends message to Kafka asynchronously or not.
     * 
     * @param value
     * @return
     */
    public KafkaQueue setSendAsync(boolean value) {
        this.sendAsync = value;
        return this;
    }

    public ProducerType getProducerType() {
        return producerType;
    }

    public KafkaQueue setProducerType(ProducerType producerType) {
        this.producerType = producerType;
        return this;
    }

    /**
     * Kafka bootstrap server list (format
     * {@code host1:9092,host2:port2,host3:port3}).
     * 
     * @return
     * @since 0.4.0
     */
    public String getKafkaBootstrapServers() {
        return bootstrapServers;
    }

    /**
     * Sets Kafka bootstrap server list (format
     * {@code host1:9092,host2:port2,host3:port3}).
     * 
     * @param kafkaBootstrapServers
     * @return
     * @since 0.4.0
     */
    public KafkaQueue setKafkaBootstrapServers(String kafkaBootstrapServers) {
        this.bootstrapServers = kafkaBootstrapServers;
        return this;
    }

    /**
     * Gets Kafka producer's custom configuration properties.
     * 
     * @return
     * @since 0.4.0
     */
    public Properties getKafkaProducerProperties() {
        return producerProps;
    }

    /**
     * Sets Kafka producer's custom configuration properties.
     * 
     * @param kafkaProducerConfigs
     * @return
     * @since 0.4.0
     */
    public KafkaQueue setKafkaProducerProperties(Properties kafkaProducerConfigs) {
        this.producerProps = kafkaProducerConfigs;
        return this;
    }

    /**
     * Gets Kafka consumer's custom configuration properties.
     * 
     * @return
     * @since 0.4.0
     */
    public Properties getKafkaConsumerProperties() {
        return consumerProps;
    }

    /**
     * Sets Kafka consumer's custom configuration properties.
     * 
     * @param kafkaConsumerConfigs
     * @return
     * @since 0.4.0
     */
    public KafkaQueue setKafkaConsumerProperties(Properties kafkaConsumerConfigs) {
        this.consumerProps = kafkaConsumerConfigs;
        return this;
    }

    /**
     * Name of Kafka topic to store queue messages.
     * 
     * @return
     */
    public String getTopicName() {
        return topicName;
    }

    public KafkaQueue setTopicName(String topicName) {
        this.topicName = topicName;
        return this;
    }

    /**
     * Kafka's group-id to consume messages.
     * 
     * @return
     */
    public String getConsumerGroupId() {
        return consumerGroupId;
    }

    public KafkaQueue setConsumerGroupId(String consumerGroupId) {
        this.consumerGroupId = consumerGroupId;
        return this;
    }

    protected KafkaClient getKafkaClient() {
        return kafkaClient;
    }

    /**
     * An external {@link KafkaClient} can be used. If not set,
     * {@link KafkaQueue} will automatically create a {@link KafkaClient} for
     * its own use.
     * 
     * @param kafkaClient
     * @return
     */
    public KafkaQueue setKafkaClient(KafkaClient kafkaClient) {
        this.kafkaClient = kafkaClient;
        myOwnKafkaClient = false;
        return this;
    }

    /*----------------------------------------------------------------------*/

    /**
     * Init method.
     * 
     * @return
     * @throws Exception
     */
    public KafkaQueue init() throws Exception {
        if (kafkaClient == null) {
            kafkaClient = new KafkaClient(bootstrapServers);
            kafkaClient.setProducerProperties(consumerProps).setConsumerProperties(consumerProps);
            kafkaClient.init();
            myOwnKafkaClient = true;
        }
        return this;
    }

    /**
     * Destroy method.
     */
    public void destroy() {
        if (kafkaClient != null && myOwnKafkaClient) {
            try {
                kafkaClient.destroy();
            } catch (Exception e) {
                LOGGER.warn(e.getMessage(), e);
            } finally {
                kafkaClient = null;
            }
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @since 0.4.0
     */
    @Override
    public void close() {
        destroy();
    }

    /**
     * Serializes a queue message to store in Kafka.
     * 
     * @param msg
     * @return
     */
    protected abstract byte[] serialize(IQueueMessage msg) throws QueueException;

    /**
     * Deserilizes a queue message.
     * 
     * @param msgData
     * @return
     */
    protected abstract IQueueMessage deserialize(byte[] msgData) throws QueueException;

    /**
     * Takes a message from Kafka queue.
     * 
     * @return
     * @since 0.3.3
     */
    protected IQueueMessage takeFromQueue() {
        KafkaMessage kMsg = kafkaClient.consumeMessage(consumerGroupId, true, topicName, 1000,
                TimeUnit.MILLISECONDS);
        return kMsg != null ? deserialize(kMsg.content()) : null;
    }

    /**
     * Puts a message to Kafka queue, partitioning message by
     * {@link IQueueMessage#qId()}
     * 
     * @param msg
     * @return
     */
    protected boolean putToQueue(IQueueMessage msg) {
        byte[] msgData = serialize(msg);
        Object pKey = msg instanceof IPartitionSupport ? ((IPartitionSupport) msg).qPartitionKey()
                : msg.qId();
        if (pKey == null) {
            pKey = msg.qId();
        }
        KafkaMessage kMsg = pKey != null ? new KafkaMessage(topicName, pKey.toString(), msgData)
                : new KafkaMessage(topicName, msgData);
        if (sendAsync) {
            return kafkaClient.sendMessageRaw(producerType, kMsg) != null;
        } else {
            return kafkaClient.sendMessage(producerType, kMsg) != null;
        }
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
    public boolean requeue(final IQueueMessage _msg) {
        IQueueMessage msg = _msg.clone();
        Date now = new Date();
        msg.qIncNumRequeues().qTimestamp(now);
        return putToQueue(msg);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean requeueSilent(IQueueMessage _msg) {
        IQueueMessage msg = _msg.clone();
        return putToQueue(msg);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void finish(IQueueMessage msg) {
        // EMPTY
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IQueueMessage take() {
        return takeFromQueue();
    }

    /**
     * {@inheritDoc}
     * 
     * This method throws {@link QueueException.OperationNotSupported}
     */
    @Override
    public Collection<IQueueMessage> getOrphanMessages(long thresholdTimestampMs) {
        throw new QueueException.OperationNotSupported();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean moveFromEphemeralToQueueStorage(IQueueMessage msg) {
        throw new UnsupportedOperationException(
                "Method [moveFromEphemeralToQueueStorage] is not supported!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int queueSize() {
        return -1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int ephemeralSize() {
        return -1;
    }

}
