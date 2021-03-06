package com.github.ddth.queue.qnd.universal;

import java.util.Date;

import org.apache.commons.dbcp2.BasicDataSource;

import com.github.ddth.queue.impl.universal.UniversalJdbcQueue;
import com.github.ddth.queue.impl.universal.UniversalQueueMessage;

public class QndQueueMySQL {

    public static void main(String[] args) throws Exception {
        BasicDataSource dataSource = new BasicDataSource();
        dataSource.setDriverClassName("com.mysql.jdbc.Driver");
        dataSource.setUrl(
                "jdbc:mysql://localhost:3306/temp?autoReconnect=true&useUnicode=true&characterEncoding=UTF-8");
        dataSource.setUsername("test");
        dataSource.setPassword("test");

        try (final UniversalJdbcQueue queue = new UniversalJdbcQueue()) {
            queue.setTableName("queue").setTableNameEphemeral("queue_ephemeral")
                    .setDataSource(dataSource).init();

            UniversalQueueMessage msg = UniversalQueueMessage.newInstance();
            msg.content("Content: [" + System.currentTimeMillis() + "] " + new Date());
            System.out.println("Queue: " + queue.queue(msg));

            msg = queue.take();
            while (msg.qNumRequeues() < 2) {
                System.out.println("Message: " + msg);
                System.out.println("Content: " + new String(msg.content()));
                System.out.println("Requeue: " + queue.requeue(msg));
                msg = queue.take();
            }

            queue.finish(msg);
        }
    }
}
