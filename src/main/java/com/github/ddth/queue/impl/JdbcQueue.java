package com.github.ddth.queue.impl;

import java.sql.Connection;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.github.ddth.dao.jdbc.BaseJdbcDao;
import com.github.ddth.queue.IQueue;
import com.github.ddth.queue.IQueueMessage;
import com.github.ddth.queue.utils.QueueException;

/**
 * Abstract JDBC implementation of {@link IQueue}.
 * 
 * <p>
 * Implementation:
 * <ul>
 * <li>Queue storage & Ephemeral storage are 2 database tables, same structure!</li>
 * </ul>
 * </p>
 * 
 * @author Thanh Ba Nguyen <bnguyen2k@gmail.com>
 * @since 0.1.0
 */
public abstract class JdbcQueue extends BaseJdbcDao implements IQueue {

    private Logger LOGGER = LoggerFactory.getLogger(JdbcQueue.class);

    private String tableName, tableNameEphemeral;
    private String SQL_COUNT = "SELECT COUNT(*) AS num_entries FROM {0}";
    private String SQL_COUNT_EPHEMERAL = "SELECT COUNT(*) AS num_entries FROM {0}";

    private static int MAX_RETRIES = 3;

    /*----------------------------------------------------------------------*/
    public JdbcQueue setTableName(String tableName) {
        this.tableName = tableName;
        return this;
    }

    protected String getTableName() {
        return tableName;
    }

    public JdbcQueue setTableNameEphemeral(String tableNameEphemeral) {
        this.tableNameEphemeral = tableNameEphemeral;
        return this;
    }

    protected String getTableNameEphemeral() {
        return tableNameEphemeral;
    }

    /*----------------------------------------------------------------------*/

    /**
     * {@inheritDoc}
     */
    @Override
    public JdbcQueue init() {
        SQL_COUNT = MessageFormat.format(SQL_COUNT, tableName);
        SQL_COUNT_EPHEMERAL = MessageFormat.format(SQL_COUNT_EPHEMERAL, tableNameEphemeral);
        return (JdbcQueue) super.init();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
        super.destroy();
    }

    /*----------------------------------------------------------------------*/

    /**
     * Reads a message from head of queue storage.
     * 
     * @param jdbcTemplate
     * @return
     */
    protected abstract IQueueMessage readFromQueueStorage(JdbcTemplate jdbcTemplate);

    /**
     * Gets all orphan messages (messages that were left in ephemeral storage
     * for a long time).
     * 
     * @param jdbcTemplate
     * @param thresholdTimestampMs
     *            get all orphan messages that were queued
     *            <strong>before</strong> this timestamp
     * @return
     * @since 0.2.0
     */
    protected abstract Collection<IQueueMessage> getOrphanFromEphemeralStorage(
            JdbcTemplate jdbcTemplate, long thresholdTimestampMs);

    /**
     * Puts a message to tail of the queue storage.
     * 
     * @param jdbcTemplate
     * @param msg
     * @return
     */
    protected abstract boolean putToQueueStorage(JdbcTemplate jdbcTemplate, IQueueMessage msg);

    /**
     * Puts a message to the ephemeral storage.
     * 
     * @param jdbcTemplate
     * @param msg
     * @return
     */
    protected abstract boolean putToEphemeralStorage(JdbcTemplate jdbcTemplate, IQueueMessage msg);

    /**
     * Removes a message from the queue storage.
     * 
     * @param jdbcTemplate
     * @param msg
     * @return
     */
    protected abstract boolean removeFromQueueStorage(JdbcTemplate jdbcTemplate, IQueueMessage msg);

    /**
     * Removes a message from the ephemeral storage.
     * 
     * @param jdbcTemplate
     * @param msg
     * @return
     */
    protected abstract boolean removeFromEphemeralStorage(JdbcTemplate jdbcTemplate,
            IQueueMessage msg);

    /**
     * Queues a message, retry if deadlock.
     * 
     * <p>
     * Note: http://dev.mysql.com/doc/refman/5.0/en/innodb-deadlocks.html
     * </p>
     * <p>
     * InnoDB uses automatic row-level locking. You can get deadlocks even in
     * the case of transactions that just insert or delete a single row. That is
     * because these operations are not really "atomic"; they automatically set
     * locks on the (possibly several) index records of the row inserted or
     * deleted.
     * </p>
     * 
     * @param jdbcTemplate
     * @param msg
     * @param numRetries
     * @param maxRetries
     * @return
     */
    protected boolean _queueWithRetries(JdbcTemplate jdbcTemplate, IQueueMessage msg,
            int numRetries, int maxRetries) {
        try {
            Date now = new Date();
            msg.qNumRequeues(0).qOriginalTimestamp(now).qTimestamp(now);
            return putToQueueStorage(jdbcTemplate, msg);
        } catch (DuplicateKeyException dke) {
            LOGGER.warn(dke.getMessage(), dke);
            return true;
        } catch (DeadlockLoserDataAccessException dle) {
            if (numRetries > maxRetries) {
                throw new QueueException(dle);
            } else {
                return _queueWithRetries(jdbcTemplate, msg, numRetries + 1, maxRetries);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean queue(IQueueMessage msg) {
        if (msg == null) {
            return false;
        }
        try {
            /*
             * obtain a new connection & start transaction
             */
            Connection conn = connection(true);
            try {
                conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                JdbcTemplate jdbcTemplate = jdbcTemplate(conn);

                /*
                 * commit if underlying storage method successes, rollback
                 * otherwise
                 */
                boolean result = _queueWithRetries(jdbcTemplate, msg, 0, MAX_RETRIES);
                if (result) {
                    commitTransaction(conn);
                } else {
                    rollbackTransaction(conn);
                }
                return result;
            } catch (Exception e) {
                rollbackTransaction(conn);
                throw e;
            } finally {
                returnConnection(conn);
            }
        } catch (Exception e) {
            final String logMsg = "(queue) Exception [" + e.getClass().getName() + "]: "
                    + e.getMessage();
            LOGGER.error(logMsg, e);
            if (e instanceof QueueException) {
                throw (QueueException) e;
            } else {
                throw new QueueException(e);
            }
        }
    }

    /**
     * Re-queues a message, retry if deadlock.
     * 
     * <p>
     * Note: http://dev.mysql.com/doc/refman/5.0/en/innodb-deadlocks.html
     * </p>
     * <p>
     * InnoDB uses automatic row-level locking. You can get deadlocks even in
     * the case of transactions that just insert or delete a single row. That is
     * because these operations are not really "atomic"; they automatically set
     * locks on the (possibly several) index records of the row inserted or
     * deleted.
     * </p>
     * 
     * @param jdbcTemplate
     * @param msg
     * @param numRetries
     * @param maxRetries
     * @return
     */
    protected boolean _requeueWithRetries(JdbcTemplate jdbcTemplate, IQueueMessage msg,
            int numRetries, int maxRetries) {
        try {
            removeFromEphemeralStorage(jdbcTemplate, msg);
            Date now = new Date();
            msg.qIncNumRequeues().qTimestamp(now);
            boolean result = putToQueueStorage(jdbcTemplate, msg);
            return result;
        } catch (DuplicateKeyException dke) {
            LOGGER.warn(dke.getMessage(), dke);
            return true;
        } catch (DeadlockLoserDataAccessException dle) {
            if (numRetries > maxRetries) {
                throw new QueueException(dle);
            } else {
                /*
                 * call _requeueSilentWithRetries(...) here is correct because
                 * we do not want message's num-requeues is increased with every
                 * retry
                 */
                return _requeueSilentWithRetries(jdbcTemplate, msg, numRetries + 1, maxRetries);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean requeue(IQueueMessage msg) {
        if (msg == null) {
            return false;
        }
        try {
            /*
             * obtain a new connection & start transaction
             */
            Connection conn = connection(true);
            try {
                conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                JdbcTemplate jdbcTemplate = jdbcTemplate(conn);

                /*
                 * commit if underlying storage method successes, rollback
                 * otherwise
                 */
                boolean result = _requeueWithRetries(jdbcTemplate, msg, 0, MAX_RETRIES);
                if (result) {
                    commitTransaction(conn);
                } else {
                    rollbackTransaction(conn);
                }
                return result;
            } catch (Exception e) {
                rollbackTransaction(conn);
                throw e;
            } finally {
                returnConnection(conn);
            }
        } catch (Exception e) {
            final String logMsg = "(requeue) Exception [" + e.getClass().getName() + "]: "
                    + e.getMessage();
            LOGGER.error(logMsg, e);
            if (e instanceof QueueException) {
                throw (QueueException) e;
            } else {
                throw new QueueException(e);
            }
        }
    }

    /**
     * Re-queues a message silently, retry if deadlock.
     * 
     * <p>
     * Note: http://dev.mysql.com/doc/refman/5.0/en/innodb-deadlocks.html
     * </p>
     * <p>
     * InnoDB uses automatic row-level locking. You can get deadlocks even in
     * the case of transactions that just insert or delete a single row. That is
     * because these operations are not really "atomic"; they automatically set
     * locks on the (possibly several) index records of the row inserted or
     * deleted.
     * </p>
     * 
     * @param jdbcTemplate
     * @param msg
     * @param numRetries
     * @param maxRetries
     * @return
     */
    protected boolean _requeueSilentWithRetries(JdbcTemplate jdbcTemplate, IQueueMessage msg,
            int numRetries, int maxRetries) {
        try {
            removeFromEphemeralStorage(jdbcTemplate, msg);
            boolean result = putToQueueStorage(jdbcTemplate, msg);
            return result;
        } catch (DuplicateKeyException dke) {
            LOGGER.warn(dke.getMessage(), dke);
            return true;
        } catch (DeadlockLoserDataAccessException dle) {
            if (numRetries > maxRetries) {
                throw new QueueException(dle);
            } else {
                return _requeueSilentWithRetries(jdbcTemplate, msg, numRetries + 1, maxRetries);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean requeueSilent(IQueueMessage msg) {
        if (msg == null) {
            return false;
        }
        try {
            /*
             * obtain a new connection & start transaction
             */
            Connection conn = connection(true);
            try {
                conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                JdbcTemplate jdbcTemplate = jdbcTemplate(conn);

                /*
                 * commit if underlying storage method successes, rollback
                 * otherwise
                 */
                boolean result = _requeueSilentWithRetries(jdbcTemplate, msg, 0, MAX_RETRIES);
                if (result) {
                    commitTransaction(conn);
                } else {
                    rollbackTransaction(conn);
                }
                return result;
            } catch (Exception e) {
                rollbackTransaction(conn);
                throw e;
            } finally {
                returnConnection(conn);
            }
        } catch (Exception e) {
            final String logMsg = "(requeueSilent) Exception [" + e.getClass().getName() + "]: "
                    + e.getMessage();
            LOGGER.error(logMsg, e);
            if (e instanceof QueueException) {
                throw (QueueException) e;
            } else {
                throw new QueueException(e);
            }
        }
    }

    /**
     * Performs "finish" action, retry if deadlock.
     * 
     * <p>
     * Note: http://dev.mysql.com/doc/refman/5.0/en/innodb-deadlocks.html
     * </p>
     * <p>
     * InnoDB uses automatic row-level locking. You can get deadlocks even in
     * the case of transactions that just insert or delete a single row. That is
     * because these operations are not really "atomic"; they automatically set
     * locks on the (possibly several) index records of the row inserted or
     * deleted.
     * </p>
     * 
     * @param jdbcTemplate
     * @param msg
     * @param numRetries
     * @param maxRetries
     */
    protected void _finishWithRetries(JdbcTemplate jdbcTemplate, IQueueMessage msg, int numRetries,
            int maxRetries) {
        try {
            removeFromEphemeralStorage(jdbcTemplate, msg);
        } catch (DeadlockLoserDataAccessException dle) {
            if (numRetries > maxRetries) {
                throw new QueueException(dle);
            } else {
                _finishWithRetries(jdbcTemplate, msg, numRetries + 1, maxRetries);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void finish(IQueueMessage msg) {
        if (msg == null) {
            return;
        }
        try {
            /*
             * obtain a new connection & start transaction
             */
            Connection conn = connection(true);
            try {
                conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                JdbcTemplate jdbcTemplate = jdbcTemplate(conn);

                _finishWithRetries(jdbcTemplate, msg, 0, MAX_RETRIES);
                commitTransaction(conn);
            } catch (Exception e) {
                rollbackTransaction(conn);
                throw e;
            } finally {
                returnConnection(conn);
            }
        } catch (Exception e) {
            final String logMsg = "(finish) Exception [" + e.getClass().getName() + "]: "
                    + e.getMessage();
            LOGGER.error(logMsg, e);
            if (e instanceof QueueException) {
                throw (QueueException) e;
            } else {
                throw new QueueException(e);
            }
        }
    }

    /**
     * Takes a message from queue, retry if deadlock.
     * 
     * <p>
     * Note: http://dev.mysql.com/doc/refman/5.0/en/innodb-deadlocks.html
     * </p>
     * <p>
     * InnoDB uses automatic row-level locking. You can get deadlocks even in
     * the case of transactions that just insert or delete a single row. That is
     * because these operations are not really "atomic"; they automatically set
     * locks on the (possibly several) index records of the row inserted or
     * deleted.
     * </p>
     * 
     * @param jdbcTemplate
     * @param numRetries
     * @param maxRetries
     * @return
     */
    protected IQueueMessage _takeWithRetries(final JdbcTemplate jdbcTemplate, final int numRetries,
            final int maxRetries) {
        try {
            IQueueMessage msg = readFromQueueStorage(jdbcTemplate);
            if (msg != null) {
                removeFromQueueStorage(jdbcTemplate, msg);
                try {
                    putToEphemeralStorage(jdbcTemplate, msg);
                } catch (DuplicateKeyException dke) {
                    LOGGER.warn(dke.getMessage(), dke);
                }
            }
            return msg;
        } catch (DeadlockLoserDataAccessException dle) {
            if (numRetries > maxRetries) {
                throw new QueueException(dle);
            } else {
                return _takeWithRetries(jdbcTemplate, numRetries + 1, maxRetries);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IQueueMessage take() {
        try {
            /*
             * obtain a new connection & start transaction
             */
            Connection conn = connection(true);
            try {
                conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                JdbcTemplate jdbcTemplate = jdbcTemplate(conn);
                IQueueMessage result = _takeWithRetries(jdbcTemplate, 0, MAX_RETRIES);
                commitTransaction(conn);
                return result;
            } catch (Exception e) {
                rollbackTransaction(conn);
                throw e;
            } finally {
                returnConnection(conn);
            }
        } catch (Exception e) {
            final String logMsg = "(take) Exception [" + e.getClass().getName() + "]: "
                    + e.getMessage();
            LOGGER.error(logMsg, e);
            if (e instanceof QueueException) {
                throw (QueueException) e;
            } else {
                throw new QueueException(e);
            }
        }
    }

    /**
     * Gets all orphan messages (messages that were left in ephemeral storage
     * for a long time), retry if deadlock.
     * 
     * <p>
     * Note: http://dev.mysql.com/doc/refman/5.0/en/innodb-deadlocks.html
     * </p>
     * <p>
     * InnoDB uses automatic row-level locking. You can get deadlocks even in
     * the case of transactions that just insert or delete a single row. That is
     * because these operations are not really "atomic"; they automatically set
     * locks on the (possibly several) index records of the row inserted or
     * deleted.
     * </p>
     * 
     * @param thresholdTimestampMs
     * @param jdbcTemplate
     * @param numRetries
     * @param maxRetries
     * @return
     * @since 0.2.0
     */
    protected Collection<IQueueMessage> _getOrphanMessagesWithRetries(
            final long thresholdTimestampMs, final JdbcTemplate jdbcTemplate, final int numRetries,
            final int maxRetries) {
        try {
            Collection<IQueueMessage> msgs = getOrphanFromEphemeralStorage(jdbcTemplate,
                    thresholdTimestampMs);
            return msgs;
        } catch (DeadlockLoserDataAccessException dle) {
            if (numRetries > maxRetries) {
                throw new QueueException(dle);
            } else {
                return _getOrphanMessagesWithRetries(thresholdTimestampMs, jdbcTemplate,
                        numRetries + 1, maxRetries);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<IQueueMessage> getOrphanMessages(long thresholdTimestampMs) {
        try {
            /*
             * obtain a new connection & start transaction
             */
            Connection conn = connection(true);
            try {
                conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                JdbcTemplate jdbcTemplate = jdbcTemplate(conn);
                Collection<IQueueMessage> result = _getOrphanMessagesWithRetries(
                        thresholdTimestampMs, jdbcTemplate, 0, MAX_RETRIES);
                commitTransaction(conn);
                return result;
            } catch (Exception e) {
                rollbackTransaction(conn);
                throw e;
            } finally {
                returnConnection(conn);
            }
        } catch (Exception e) {
            final String logMsg = "(getOrphanMessages) Exception [" + e.getClass().getName()
                    + "]: " + e.getMessage();
            LOGGER.error(logMsg, e);
            if (e instanceof QueueException) {
                throw (QueueException) e;
            } else {
                throw new QueueException(e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int queueSize() {
        try {
            Connection conn = connection();
            try {
                JdbcTemplate jdbcTemplate = jdbcTemplate(conn);
                Integer result = jdbcTemplate.queryForObject(SQL_COUNT, null, Integer.class);
                return result != null ? result.intValue() : 0;
            } finally {
                returnConnection(conn);
            }
        } catch (Exception e) {
            final String logMsg = "(queueSize) Exception [" + e.getClass().getName() + "]: "
                    + e.getMessage();
            LOGGER.error(logMsg, e);
            return -1;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int ephemeralSize() {
        try {
            Connection conn = connection();
            try {
                JdbcTemplate jdbcTemplate = jdbcTemplate(conn);
                Integer result = jdbcTemplate.queryForObject(SQL_COUNT_EPHEMERAL, null,
                        Integer.class);
                return result != null ? result.intValue() : 0;
            } finally {
                returnConnection(conn);
            }
        } catch (Exception e) {
            final String logMsg = "(ephemeralSize) Exception [" + e.getClass().getName() + "]: "
                    + e.getMessage();
            LOGGER.error(logMsg, e);
            return -1;
        }
    }
}
