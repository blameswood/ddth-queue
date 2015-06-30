package com.github.ddth.queue.impl;

import java.nio.charset.Charset;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;

import com.github.ddth.commons.utils.IdGenerator;
import com.github.ddth.queue.IQueue;
import com.github.ddth.queue.IQueueMessage;

/**
 * Redis implementation of {@link IQueue}.
 * 
 * <p>
 * Implementation:
 * <ul>
 * <li>A hash to store message, format {queue_id => message}. See
 * {@link #setRedisHashName(String)}.
 * <li>A list to act as a queue of message's queue_id. See
 * {@link #setRedisListName(String)}.
 * <li>A sorted set to act as ephemeral storage of message's queue_id, score is
 * message's timestamp. See {@link #setRedisSortedSetName(String)}.</li>
 * </ul>
 * </p>
 * 
 * @author Thanh Ba Nguyen <bnguyen2k@gmail.com>
 * @since 0.3.1
 */
public abstract class RedisQueue implements IQueue {

    protected final static Charset UTF8 = Charset.forName("UTF-8");

    private IdGenerator IDGEN = IdGenerator.getInstance(IdGenerator.getMacAddr());

    private JedisPool jedisPool;
    private boolean myOwnJedisPool = true;
    private String redisHostAndPort = "localhost:6379";

    private String _redisHashName = "queue_h";
    private byte[] redisHashName = _redisHashName.getBytes(UTF8);

    private String _redisListName = "queue_l";
    private byte[] redisListName = _redisListName.getBytes(UTF8);

    private String _redisSortedSetName = "queue_s";
    private byte[] redisSortedSetName = _redisSortedSetName.getBytes(UTF8);

    /**
     * Redis' host and port scheme (format {@code host:port}).
     * 
     * @return
     */
    public String getRedisHostAndPort() {
        return redisHostAndPort;
    }

    /**
     * Sets Redis' host and port scheme (format {@code host:port}).
     * 
     * @param redisHostAndPort
     * @return
     */
    public RedisQueue setRedisHostAndPort(String redisHostAndPort) {
        this.redisHostAndPort = redisHostAndPort;
        return this;
    }

    public String getRedisHashName() {
        return _redisHashName;
    }

    public RedisQueue setRedisHashName(String redisHashName) {
        _redisHashName = redisHashName;
        this.redisHashName = _redisHashName.getBytes(UTF8);
        return this;
    }

    public String getRedisListName() {
        return _redisListName;
    }

    public RedisQueue setRedisListName(String redisListName) {
        _redisListName = redisListName;
        this.redisListName = _redisListName.getBytes(UTF8);
        return this;
    }

    public String getRedisSortedSetName() {
        return _redisSortedSetName;
    }

    public RedisQueue setRedisSortedSetName(String redisSortedSetName) {
        _redisSortedSetName = redisSortedSetName;
        this.redisSortedSetName = _redisSortedSetName.getBytes(UTF8);
        return this;
    }

    protected JedisPool getJedisPool() {
        return jedisPool;
    }

    public RedisQueue setJedisPool(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
        myOwnJedisPool = false;
        return this;
    }

    /*----------------------------------------------------------------------*/

    private String SCRIPT_TAKE, SCRIPT_MOVE;

    /**
     * Init method.
     * 
     * @return
     */
    public RedisQueue init() {
        if (jedisPool == null) {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(32);
            poolConfig.setMinIdle(1);
            poolConfig.setMaxIdle(16);
            poolConfig.setMaxWaitMillis(10000);
            // poolConfig.setTestOnBorrow(true);
            poolConfig.setTestWhileIdle(true);

            String[] tokens = redisHostAndPort.split(":");
            String redisHost = tokens.length > 0 ? tokens[0] : "localhost";
            int redisPort = tokens.length > 1 ? Integer.parseInt(tokens[1]) : 6379;
            jedisPool = new JedisPool(poolConfig, redisHost, redisPort);
            myOwnJedisPool = true;
        }

        SCRIPT_TAKE = "local qid=redis.call(\"lpop\",\"{0}\"); if qid then "
                + "redis.call(\"zadd\", \"{1}\",  ARGV[1], qid); return redis.call(\"hget\", \"{2}\", qid) "
                + "else return nil end";
        SCRIPT_TAKE = MessageFormat.format(SCRIPT_TAKE, _redisListName, _redisSortedSetName,
                _redisHashName);

        SCRIPT_MOVE = "local result=redis.call(\"zrem\",\"{0}\",ARGV[1]); if result then "
                + "redis.call(\"rpush\", \"{1}\",  ARGV[1]); return 1; else return 0; end";
        SCRIPT_MOVE = MessageFormat.format(SCRIPT_MOVE, _redisSortedSetName, _redisListName);

        return this;
    }

    /**
     * Destroy method.
     */
    public void destroy() {
        if (jedisPool != null && myOwnJedisPool) {
            jedisPool.destroy();
            jedisPool = null;
        }
    }

    /**
     * Serializes a queue message to store in Redis.
     * 
     * @param msg
     * @return
     */
    protected abstract byte[] serialize(IQueueMessage msg);

    /**
     * Deserilizes a queue message.
     * 
     * @param msgData
     * @return
     */
    protected abstract IQueueMessage deserialize(byte[] msgData);

    /**
     * Removes a message completely.
     * 
     * @param msg
     * @return
     */
    protected boolean remove(IQueueMessage msg) {
        try (Jedis jedis = jedisPool.getResource()) {
            Transaction jt = jedis.multi();

            byte[] field = msg.qId().toString().getBytes(UTF8);
            Response<Long> response = jt.hdel(redisHashName, field);
            jt.zrem(redisSortedSetName, field);

            jt.exec();
            Long value = response.get();
            return value != null && value.longValue() > 1;
        }
    }

    /**
     * Stores a new message.
     * 
     * @param msg
     * @return
     */
    protected boolean storeNew(IQueueMessage msg) {
        try (Jedis jedis = jedisPool.getResource()) {
            Transaction jt = jedis.multi();

            byte[] field = msg.qId().toString().getBytes(UTF8);
            byte[] data = serialize(msg);
            jt.hset(redisHashName, field, data);
            jt.rpush(redisListName, field);

            jt.exec();
            return true;
        }
    }

    /**
     * Re-stores an old message (called by {@link #requeue(IQueueMessage)} or
     * {@link #requeueSilent(IQueueMessage)}.
     * 
     * @param msg
     * @return
     */
    protected boolean storeOld(IQueueMessage msg) {
        try (Jedis jedis = jedisPool.getResource()) {
            Transaction jt = jedis.multi();

            byte[] field = msg.qId().toString().getBytes(UTF8);
            byte[] data = serialize(msg);
            jt.hset(redisHashName, field, data);
            jt.rpush(redisListName, field);
            jt.zrem(redisSortedSetName, field);

            jt.exec();
            return true;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean queue(IQueueMessage msg) {
        Date now = new Date();
        Object qId = msg.qId();
        if (qId == null || (qId instanceof Number && ((Number) qId).longValue() == 0)) {
            msg.qId(IDGEN.generateId64());
        }
        msg.qNumRequeues(0).qOriginalTimestamp(now).qTimestamp(now);
        return storeNew(msg);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean requeue(IQueueMessage msg) {
        Date now = new Date();
        msg.qIncNumRequeues().qTimestamp(now);
        return storeOld(msg);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean requeueSilent(IQueueMessage msg) {
        return storeOld(msg);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void finish(IQueueMessage msg) {
        remove(msg);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IQueueMessage take() {
        try (Jedis jedis = jedisPool.getResource()) {
            long timestamp = System.currentTimeMillis();
            Object response = jedis.eval(SCRIPT_TAKE, 0, String.valueOf(timestamp));
            if (response == null) {
                return null;
            }
            return deserialize(response instanceof byte[] ? (byte[]) response : response.toString()
                    .getBytes(UTF8));
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @param thresholdTimestampMs
     * @return
     */
    @Override
    public Collection<IQueueMessage> getOrphanMessages(long thresholdTimestampMs) {
        try (Jedis jedis = jedisPool.getResource()) {
            Collection<IQueueMessage> result = new HashSet<IQueueMessage>();

            byte[] min = "0".getBytes();
            byte[] max = String.valueOf(thresholdTimestampMs).getBytes();
            Set<byte[]> fields = jedis.zrangeByScore(redisSortedSetName, min, max, 0, 100);
            for (byte[] field : fields) {
                byte[] data = jedis.hget(redisHashName, field);
                IQueueMessage msg = deserialize(data);
                if (msg != null) {
                    result.add(msg);
                }
            }

            return result;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean moveFromEphemeralToQueueStorage(IQueueMessage msg) {
        try (Jedis jedis = jedisPool.getResource()) {
            Object response = jedis.eval(SCRIPT_MOVE, 0, msg.qId().toString());
            return response != null && "1".equals(response.toString());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int queueSize() {
        try (Jedis jedis = jedisPool.getResource()) {
            Long result = jedis.hlen(redisHashName);
            return result != null ? result.intValue() : 0;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int ephemeralSize() {
        try (Jedis jedis = jedisPool.getResource()) {
            Long result = jedis.zcard(redisSortedSetName);
            return result != null ? result.intValue() : 0;
        }
    }
}
