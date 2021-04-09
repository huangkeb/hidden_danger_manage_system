package com.hkb.hdms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hkb.hdms.base.R;
import com.hkb.hdms.model.pojo.Problem;

import java.util.Map;

/**
 * @author huangkebing
 * 2021/04/05
 */
public interface TaskService extends IService<Problem> {
    R createTask(Problem problem);

    Map<String,Object> getMyTask(int page, int limit);

    R getDetailTask(Long problemId);

    R completeTask(String taskId);

    void test();
}
