package com.heima.schedule.service;

import com.heima.model.schedule.dtos.Task;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.PostConstruct;

public interface TaskService {
    /**
     * 添加任务
     *
     * @param task 任务对象
     * @return 任务id
     */
    public long addTask(Task task);

    /**
     * 取消任务
     *
     * @param taskId 任务id
     * @return 取消结果
     */
    public boolean cancelTask(long taskId);

    /**
     * 按照类型和优先级来拉取任务
     *
     * @param type
     * @param priority
     * @return
     */
    public Task poll(int type, int priority);

    /**
     * 定时刷新任务
     */
    public void refresh();

    /**
     * 数据库任务定时同步到redis
     */
    @Scheduled(cron = "0 */5 * * * ?")
    @PostConstruct
    public void reloadData();
}
