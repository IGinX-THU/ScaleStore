package com.storage.engine.service;

import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.DataItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台元数据抽取队列：
 * - 存储流程只负责入队，不等待 LLM/图谱写入完成；
 * - 后台线程消费任务并回写知识抽取状态。
 */
@Service
public class MetadataExtractionQueueService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataExtractionQueueService.class);

    private static final int QUEUE_CAPACITY = 2000;

    @Value("${metadata.extraction.max-concurrency:4}")
    private int maxConcurrency;

    private ThreadPoolExecutor executor;

    @Autowired
    private MetadataKnowledgeService metadataKnowledgeService;

    @Autowired
    private IGinxDao iginxDao;

    public void enqueue(byte[] fileBytes, DataItem item) {
        if (item == null || item.getId() == null) {
            return;
        }

        ThreadPoolExecutor pool = this.executor;
        if (pool == null) {
            logger.error("元数据抽取线程池未初始化, id={}, path={}", item.getId(), item.getLogicalPath());
            safeUpdateStatus(item.getId(), "FAILED");
            return;
        }

        try {
            pool.execute(new ExtractionTask(fileBytes, item));
        } catch (RejectedExecutionException e) {
            logger.error("元数据抽取队列已满, id={}, path={}", item.getId(), item.getLogicalPath());
            safeUpdateStatus(item.getId(), "FAILED");
        }
    }

    @PostConstruct
    public void startWorker() {
        int concurrency = Math.max(1, maxConcurrency);
        this.executor = new ThreadPoolExecutor(
                concurrency,
                concurrency,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(QUEUE_CAPACITY),
                new ExtractionThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        logger.info("元数据抽取线程池已启动, maxConcurrency={}, queueCapacity={}", concurrency, QUEUE_CAPACITY);
    }

    @PreDestroy
    public void stopWorker() {
        ThreadPoolExecutor pool = this.executor;
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
    }

    private void processTask(ExtractionTask task) {
        try {
            boolean ok = metadataKnowledgeService.indexStoredData(task.fileBytes, task.item);
            if (ok) {
                safeUpdateStatus(task.item.getId(), "SUCCESS");
            } else {
                safeUpdateStatus(task.item.getId(), "FAILED");
            }
        } catch (Exception e) {
            logger.error("后台元数据抽取任务异常: id={}, error={}", task.item.getId(), e.getMessage(), e);
            safeUpdateStatus(task.item.getId(), "FAILED");
        }
    }

    private void safeUpdateStatus(Integer id, String status) {
        if (id == null) {
            return;
        }
        try {
            iginxDao.updateMetaKnowledgeStatus(id.longValue(), status);
        } catch (Exception e) {
            logger.error("更新知识抽取状态失败, id={}, status={}, error={}", id, status, e.getMessage(), e);
        }
    }

    private class ExtractionTask implements Runnable {
        private final byte[] fileBytes;
        private final DataItem item;

        private ExtractionTask(byte[] fileBytes, DataItem item) {
            this.fileBytes = fileBytes;
            this.item = item;
        }

        @Override
        public void run() {
            processTask(this);
        }
    }

    private static class ExtractionThreadFactory implements ThreadFactory {
        private final AtomicInteger idx = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "metadata-extraction-worker-" + idx.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
