package com.alibaba.ttl.threadpool.agent;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.alibaba.ttl.Utils;
import com.alibaba.ttl.testmodel.Task;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.alibaba.ttl.Utils.CHILD;
import static com.alibaba.ttl.Utils.PARENT_AFTER_CREATE_TTL_TASK;
import static com.alibaba.ttl.Utils.PARENT_MODIFIED_IN_CHILD;
import static com.alibaba.ttl.Utils.PARENT_UNMODIFIED_IN_CHILD;
import static com.alibaba.ttl.Utils.assertTtlInstances;
import static com.alibaba.ttl.Utils.copied;
import static com.alibaba.ttl.Utils.createTestTtlValue;
import static com.alibaba.ttl.Utils.expandThreadPool;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * @author Jerry Lee (oldratlee at gmail dot com)
 */
public final class AgentCheck {

    private AgentCheck() {
        throw new InstantiationError("Must not instantiate this class");
    }

    public static void main(String[] args) throws Exception {
        try {
            ThreadPoolExecutor executorService = new ThreadPoolExecutor(3, 3,
                    10L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<Runnable>());
            ScheduledThreadPoolExecutor scheduledExecutorService = new ScheduledThreadPoolExecutor(3);

            expandThreadPool(executorService);
            expandThreadPool(scheduledExecutorService);

            ConcurrentMap<String, TransmittableThreadLocal<String>> ttlInstances = createTestTtlValue();

            checkExecutorService(executorService, ttlInstances);
            checkThreadPoolExecutorForRemoveMethod(executorService);
            checkScheduledExecutorService(scheduledExecutorService, ttlInstances);

            System.out.println();
            System.out.println("====================================");
            System.out.println("Check OK!");
            System.out.println("====================================");

            executorService.shutdown();
            scheduledExecutorService.shutdown();

            if (!executorService.awaitTermination(3, TimeUnit.SECONDS)) {
                System.out.println("Fail to close ThreadPoolExecutor");
                System.exit(1);
            }
            if (!scheduledExecutorService.awaitTermination(3, TimeUnit.SECONDS)) {
                System.out.println("Fail to close scheduledExecutorService");
                System.exit(1);
            }
        } catch (Throwable e) {
            System.out.println("Exception when run AgentCheck: ");
            e.printStackTrace(System.out);
            System.exit(2);
        }
    }

    private static void checkExecutorService(ExecutorService executorService, ConcurrentMap<String, TransmittableThreadLocal<String>> ttlInstances) throws Exception {
        Task task = new Task("1", ttlInstances);
        executorService.submit(task);

        // create after new Task, won't see parent value in in task!
        TransmittableThreadLocal<String> after = new TransmittableThreadLocal<String>();
        after.set(PARENT_AFTER_CREATE_TTL_TASK);
        ttlInstances.put(PARENT_AFTER_CREATE_TTL_TASK, after);

        Thread.sleep(1000);

        System.out.println(task.copied);

        // child Inheritable
        Utils.assertTtlInstances(task.copied,
                PARENT_UNMODIFIED_IN_CHILD, PARENT_UNMODIFIED_IN_CHILD,
                PARENT_MODIFIED_IN_CHILD + "1", PARENT_MODIFIED_IN_CHILD,
                CHILD + "1", CHILD + "1"
        );

        // child do not effect parent
        assertTtlInstances(copied(ttlInstances),
                PARENT_UNMODIFIED_IN_CHILD, PARENT_UNMODIFIED_IN_CHILD,
                PARENT_MODIFIED_IN_CHILD, PARENT_MODIFIED_IN_CHILD,
                PARENT_AFTER_CREATE_TTL_TASK, PARENT_AFTER_CREATE_TTL_TASK
        );
    }

    private static void checkThreadPoolExecutorForRemoveMethod(ThreadPoolExecutor executor) throws Exception {
        final AtomicBoolean touched = new AtomicBoolean(false);

        final int COUNT = 4;
        final CountDownLatch latch = new CountDownLatch(COUNT);
        for (int i = 0; i < COUNT; i++) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(1000);
                        System.out.println("Run sleep task!");
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }

        final Runnable taskToRemove = new Runnable() {
            @Override
            public void run() {
                System.out.println("Run taskToRemove!");
                touched.set(true);
            }
        };
        executor.execute(taskToRemove);
        executor.remove(taskToRemove);

        // wait sleep task finished.
        if (!latch.await(3, TimeUnit.SECONDS)) {
            throw new RuntimeException("Fail to finished in time!");
        }

        /////////////////////////////////////////////////////////////
        // Is ThreadPoolExecutor#remove method take effect?
        /////////////////////////////////////////////////////////////

        // task is directly removed from work queue, so not cancelled!
        assertEquals(0, executor.getActiveCount());
        assertFalse(touched.get());
    }

    private static void checkScheduledExecutorService(ScheduledExecutorService scheduledExecutorService, ConcurrentMap<String, TransmittableThreadLocal<String>> ttlInstances) throws Exception {
        Task task = new Task("2", ttlInstances);
        ScheduledFuture<?> future = scheduledExecutorService.schedule(task, 200, TimeUnit.MILLISECONDS);

        // create after new Task, won't see parent value in in task!
        TransmittableThreadLocal<String> after = new TransmittableThreadLocal<String>();
        after.set(PARENT_AFTER_CREATE_TTL_TASK);
        ttlInstances.put(PARENT_AFTER_CREATE_TTL_TASK, after);

        future.get();

        // child Inheritable
        assertTtlInstances(task.copied,
                PARENT_UNMODIFIED_IN_CHILD, PARENT_UNMODIFIED_IN_CHILD,
                PARENT_MODIFIED_IN_CHILD + "2", PARENT_MODIFIED_IN_CHILD,
                CHILD + "2", CHILD + "2"
        );

        // child do not effect parent
        assertTtlInstances(copied(ttlInstances),
                PARENT_UNMODIFIED_IN_CHILD, PARENT_UNMODIFIED_IN_CHILD,
                PARENT_MODIFIED_IN_CHILD, PARENT_MODIFIED_IN_CHILD,
                PARENT_AFTER_CREATE_TTL_TASK, PARENT_AFTER_CREATE_TTL_TASK
        );
    }
}
