package com.github.ddth.queue.test.universal;

import java.sql.SQLException;

import org.apache.commons.dbcp2.BasicDataSource;

import com.github.ddth.dao.jdbc.IJdbcHelper;
import com.github.ddth.queue.IQueue;
import com.github.ddth.queue.impl.JdbcQueue;
import com.github.ddth.queue.impl.universal.UniversalJdbcQueue;

import junit.framework.Test;
import junit.framework.TestSuite;

public class TestMySQLQueueLongBoundEphemeralSize extends BaseQueueLongTest {
    public TestMySQLQueueLongBoundEphemeralSize(String testName) {
        super(testName);
    }

    public static Test suite() {
        return new TestSuite(TestMySQLQueueLongBoundEphemeralSize.class);
    }

    private static class MyJdbcQueue extends UniversalJdbcQueue {
        public void flush() throws SQLException {
            IJdbcHelper jdbcHelper = getJdbcHelper();
            jdbcHelper.execute("DELETE FROM " + getTableName());
            jdbcHelper.execute("DELETE FROM " + getTableNameEphemeral());
        }
    }

    protected IQueue initQueueInstance() {
        if (System.getProperty("enableTestsMySql") == null
                && System.getProperty("enableTestsMySQL") == null) {
            return null;
        }
        String mysqlHost = System.getProperty("db.host", "localhost");
        String mysqlPort = System.getProperty("db.port", "3306");
        String mysqlDb = System.getProperty("db.db", "test");
        String mysqlUser = System.getProperty("db.user", "test");
        String mysqlPassword = System.getProperty("db.password", "test");
        String tableQueue = System.getProperty("table.queue", "queue");
        String tableEphemeral = System.getProperty("table.ephemeral", "queue_ephemeral");

        BasicDataSource dataSource = new BasicDataSource();
        dataSource.setDriverClassName("com.mysql.jdbc.Driver");
        dataSource.setUrl("jdbc:mysql://" + mysqlHost + ":" + mysqlPort + "/" + mysqlDb
                + "?autoReconnect=true&useUnicode=true&characterEncoding=UTF-8");
        dataSource.setUsername(mysqlUser);
        dataSource.setPassword(mysqlPassword);

        MyJdbcQueue queue = new MyJdbcQueue();
        try {
            queue.setDataSource(dataSource).setTableName(tableQueue)
                    .setTableNameEphemeral(tableEphemeral).setEphemeralDisabled(false)
                    .setEphemeralMaxSize(16).init();
            queue.flush();
            return queue;
        } catch (Exception e) {
            queue.destroy();
            throw new RuntimeException(e);
        }
    }

    protected void destroyQueueInstance(IQueue queue) {
        if (queue instanceof JdbcQueue) {
            ((JdbcQueue) queue).destroy();
        } else {
            throw new RuntimeException("[queue] is not closed!");
        }
    }

    protected int numTestMessages() {
        // to make a very long queue
        return 16 * 1024;
    }

}
