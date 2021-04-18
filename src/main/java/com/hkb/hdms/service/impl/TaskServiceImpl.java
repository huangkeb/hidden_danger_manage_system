package com.hkb.hdms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hkb.hdms.base.Constants;
import com.hkb.hdms.base.R;
import com.hkb.hdms.base.ReturnConstants;
import com.hkb.hdms.mapper.*;
import com.hkb.hdms.model.dto.InstanceDto;
import com.hkb.hdms.model.dto.ProblemDto;
import com.hkb.hdms.model.pojo.*;
import com.hkb.hdms.service.TaskService;
import com.hkb.hdms.service.TypeService;
import com.hkb.hdms.utils.NoticeUtil;
import com.hkb.hdms.utils.TaskHandlerUtil;
import com.mysql.cj.util.StringUtils;
import org.activiti.api.runtime.shared.identity.UserGroupManager;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.bpmn.model.FlowNode;
import org.activiti.bpmn.model.SequenceFlow;
import org.activiti.engine.HistoryService;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.history.HistoricActivityInstance;
import org.activiti.engine.history.HistoricProcessInstance;
import org.activiti.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Task;
import org.activiti.image.ProcessDiagramGenerator;
import org.activiti.image.impl.DefaultProcessDiagramGenerator;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpSession;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author huangkebing
 * 2021/04/05
 */
@Service
public class TaskServiceImpl extends ServiceImpl<ProblemMapper, Problem> implements TaskService {

    private final TypeService typeService;

    private final RuntimeService runtimeService;

    private final HistoryService historyService;

    private final RepositoryService repositoryService;

    private final org.activiti.engine.TaskService taskService;

    private final TaskHandlerUtil taskHandlerUtil;

    private final ProcessVariableMapper processVariableMapper;

    private final UserGroupManager userGroupManager;

    private final ProblemObserverMapper problemObserverMapper;

    private final ProblemMapper problemMapper;

    private final ProblemInfoMapper problemInfoMapper;

    private final UserMapper userMapper;

    private final TaskMapper taskMapper;

    private final HttpSession session;

    private final NoticeUtil noticeUtil;

    @Autowired
    public TaskServiceImpl(TypeService typeService,
                           RuntimeService runtimeService,
                           TaskHandlerUtil taskHandlerUtil,
                           org.activiti.engine.TaskService taskService,
                           HttpSession session,
                           ProcessVariableMapper processVariableMapper,
                           UserGroupManager userGroupManager,
                           TaskMapper taskMapper,
                           ProblemMapper problemMapper,
                           ProblemInfoMapper problemInfoMapper, HistoryService historyService, RepositoryService repositoryService, ProblemObserverMapper problemObserverMapper, NoticeUtil noticeUtil, UserMapper userMapper) {
        this.typeService = typeService;
        this.runtimeService = runtimeService;
        this.taskHandlerUtil = taskHandlerUtil;
        this.taskService = taskService;
        this.session = session;
        this.processVariableMapper = processVariableMapper;
        this.userGroupManager = userGroupManager;
        this.taskMapper = taskMapper;
        this.problemMapper = problemMapper;
        this.problemInfoMapper = problemInfoMapper;
        this.historyService = historyService;
        this.repositoryService = repositoryService;
        this.problemObserverMapper = problemObserverMapper;
        this.noticeUtil = noticeUtil;
        this.userMapper = userMapper;
    }

    @Override
    @Transactional
    public R createTask(Problem problem, Map<String, Object> processVariables) {
        Type taskType = typeService.getById(problem.getTypeId());
        User user = (User) session.getAttribute(Constants.LOGIN_USER_KEY);
        List<ProcessVariable> variables = processVariableMapper.selectList(new QueryWrapper<ProcessVariable>()
                .eq("begin_variable", 1)
                .eq("process_id", problem.getTypeId()));

        //给定流程变量
        Map<String, Object> variablesMap = new HashMap<>();
        for (ProcessVariable variable : variables) {
            variablesMap.put(variable.getName(), processVariables.get("variables[" + variable.getName() + "]"));
        }

        ProcessInstance instance;
        try {
            //根据流程定义id发起流程实例
            instance = runtimeService.startProcessInstanceById(taskType.getProcessId(), variablesMap);
            //绑定任务的操作人
            taskHandlerUtil.setTaskHandler(instance, problem.getTypeId());
            problem.setInstanceId(instance.getId());
        } catch (Exception e) {
            e.printStackTrace();
            return ReturnConstants.PROCESS_ERROR;
        }
        problem.setUserId(user.getId());
        this.save(problem);

        Problem nowProblem = problemMapper.selectOne(new QueryWrapper<Problem>().eq("instance_id", problem.getInstanceId()));

        //添加备注
        ProblemInfo problemInfo = new ProblemInfo();
        problemInfo.setType(1);
        problemInfo.setProblemId(nowProblem.getId());
        problemInfo.setEmail(user.getEmail());
        problemInfo.setUsername(user.getName());
        problemInfo.setUserId(user.getId());
        problemInfo.setContext(user.getName() + " 创建了问题：" + nowProblem.getName());
        noticeUtil.insertRemark(problemInfo);

        //设置关注
        ProblemObserver problemObserver = new ProblemObserver();
        problemObserver.setProblemId(nowProblem.getId());
        problemObserver.setEmail(user.getEmail());
        problemObserver.setUserId(user.getId());
        noticeUtil.insertObserve(problemObserver);

        //保存到redis中
        Map<String, Object> map = new HashMap<>();
        map.put("type", 1);
        map.put("userId", user.getId());
        map.put("username", user.getName());
        map.put("email", user.getEmail());
        map.put("problemId",nowProblem.getId());
        map.put("problemName",nowProblem.getName());
        map.put("priority",nowProblem.getPriority());
        noticeUtil.insertRedis(map);

        String message = user.getName() + "(" + user.getEmail() + ") 创建了隐患：" + nowProblem.getName();
        noticeUtil.noticeMail(nowProblem.getId(), message);
        return ReturnConstants.SUCCESS;
    }

    @Override
    public Map<String, Object> getMyTask(int page, int limit) {
        HashMap<String, Object> map = new HashMap<>();
        map.put("code", 0);
        map.put("msg", "");

        if (page < 1) {
            page = 1;
        }

        int offset = (page - 1) * limit;

        User loginUser = (User) session.getAttribute(Constants.LOGIN_USER_KEY);
        List<String> groups = userGroupManager.getUserGroups(loginUser.getEmail());

        List<Problem> problems = taskMapper.getTodoInstances(loginUser.getEmail(), groups, limit, offset);

        map.put("data", problems);
        map.put("count", taskMapper.getTodoCount(loginUser.getEmail(), groups));

        return map;
    }

    @Override
    public R getDetailTask(Long problemId) {
        Map<String, Object> data = new HashMap<>();
        ProblemDto problem = problemMapper.selectDetailById(problemId);
        User loginUser = (User) session.getAttribute(Constants.LOGIN_USER_KEY);

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(problem.getInstanceId()).taskCandidateUser(loginUser.getEmail()).list();

        List<Map<String, Object>> list = new ArrayList<>();
        for (Task task : tasks) {
            Map<String, Object> map = new HashMap<>();
            map.put("taskId", task.getId());
            map.put("taskName", task.getName());
            List<ProcessVariable> variables = processVariableMapper.selectList(new QueryWrapper<ProcessVariable>()
                    .eq("process_id", task.getProcessDefinitionId())
                    .eq("node_id", task.getTaskDefinitionKey()));
            map.put("variables",variables);
            list.add(map);
        }

        data.put("task", list);
        data.put("problem", problem);
        return new R(0, "SUCCESS", data);
    }

    @Override
    @Transactional
    public R completeTask(String taskId, Map<String,Object> processVariables, String context) {
        //根据taskId查询出task
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();

        Problem problem = this.getOne(new QueryWrapper<Problem>().eq("instance_id", task.getProcessInstanceId()));
        User user = (User) session.getAttribute(Constants.LOGIN_USER_KEY);
        String taskName = task.getName();

        List<ProcessVariable> variables = processVariableMapper.selectList(new QueryWrapper<ProcessVariable>()
                .eq("node_id", task.getTaskDefinitionKey())
                .eq("process_id", task.getProcessDefinitionId()));

        //给定流程变量
        Map<String, Object> variablesMap = new HashMap<>();
        for (ProcessVariable variable : variables) {
            variablesMap.put(variable.getName(), processVariables.get("variables[" + variable.getName() + "]"));
        }
        taskHandlerUtil.deleteCandidateUser(taskId, task.getProcessInstanceId());
        //完成task
        taskService.complete(taskId, variablesMap);
        //尝试去设置后续出现的任务节点的处理人
        ProcessInstance instance = runtimeService.createProcessInstanceQuery().processInstanceId(task.getProcessInstanceId()).singleResult();
        //处理的是最后一个节点，complete之后instance会为null
        if(ObjectUtils.isNotEmpty(instance)){
            taskHandlerUtil.setTaskHandler(instance,problem.getTypeId());
        }

        //添加备注
        ProblemInfo problemInfo = new ProblemInfo();
        problemInfo.setType(1);
        problemInfo.setProblemId(problem.getId());
        problemInfo.setEmail(user.getEmail());
        problemInfo.setUsername(user.getName());
        problemInfo.setUserId(user.getId());
        problemInfo.setContext(user.getName() + "(" + user.getEmail()  + ") 处理了隐患：" + problem.getName() + " 中的" + taskName + " 节点");
        noticeUtil.insertRemark(problemInfo);

        //设置关注
        ProblemObserver problemObserver = new ProblemObserver();
        problemObserver.setProblemId(problem.getId());
        problemObserver.setEmail(user.getEmail());
        problemObserver.setUserId(user.getId());
        noticeUtil.insertObserve(problemObserver);

        //保存到redis中
        Map<String, Object> map = new HashMap<>();
        map.put("type", 5);
        map.put("userId", user.getId());
        map.put("username", user.getName());
        map.put("email", user.getEmail());
        map.put("problemId",problem.getId());
        map.put("problemName",problem.getName());
        map.put("priority",problem.getPriority());
        map.put("taskName",taskName);
        noticeUtil.insertRedis(map);

        String message = user.getName() + "(" + user.getEmail()  + ") 处理了隐患：" + problem.getName() + " 中的" + taskName + " 节点";
        noticeUtil.noticeMail(problem.getId(), message);

        noticeUtil.updateProblemModify(problem.getId());
        return ReturnConstants.SUCCESS;
    }

    @Override
    public List<ProcessVariable> getBeginVariable(Long typeId) {
        Type taskType = typeService.getById(typeId);

        return processVariableMapper.selectList(new QueryWrapper<ProcessVariable>()
                .eq("process_id", taskType.getProcessId())
                .eq("begin_variable", 1));
    }

    @Override
    public Map<String, Object> getHistoryTask(String name, String priority, String begin, String end, int page, int limit) {
        HashMap<String, Object> map = new HashMap<>();
        map.put("code", 0);
        map.put("msg", "");

        if (page < 1) {
            page = 1;
        }

        int offset = (page - 1) * limit;

        User loginUser = (User) session.getAttribute(Constants.LOGIN_USER_KEY);
        List<String> groups = userGroupManager.getUserGroups(loginUser.getEmail());

        if(!StringUtils.isNullOrEmpty(name)){
            name = "%" + name + "%";
        }
        List<Problem> problems = taskMapper.getHistoryInstances(loginUser.getEmail(), groups, limit, offset,name, priority, begin, end);

        map.put("data", problems);
        map.put("count", taskMapper.getHistoryCount(loginUser.getEmail(), groups,name, priority, begin, end));
        return map;
    }

    @Override
    public Map<String, Object> getSolveingTask(int page, int limit) {
        HashMap<String, Object> map = new HashMap<>();
        map.put("code", 0);
        map.put("msg", "");

        if (page < 1) {
            page = 1;
        }

        int offset = (page - 1) * limit;

        User loginUser = (User) session.getAttribute(Constants.LOGIN_USER_KEY);
        List<String> groups = userGroupManager.getUserGroups(loginUser.getEmail());

        List<Problem> problems = taskMapper.getSolveingInstances(loginUser.getEmail(), groups, limit, offset);

        map.put("data", problems);
        map.put("count", taskMapper.getSolveingCount(loginUser.getEmail(), groups));
        return map;
    }

    @Override
    public R updateProblem(Problem problem) {
        if (this.update(problem, new UpdateWrapper<Problem>().eq("id",problem.getId()))) {
            Problem nowProblem = problemMapper.selectById(problem.getId());
            User user = (User) session.getAttribute(Constants.LOGIN_USER_KEY);
            //添加备注
            ProblemInfo problemInfo = new ProblemInfo();
            problemInfo.setType(1);
            problemInfo.setProblemId(nowProblem.getId());
            problemInfo.setEmail(user.getEmail());
            problemInfo.setUsername(user.getName());
            problemInfo.setUserId(user.getId());
            problemInfo.setContext(user.getName() + " 编辑了隐患：" + nowProblem.getName());
            noticeUtil.insertRemark(problemInfo);

            noticeUtil.updateProblemModify(nowProblem.getId());
            return ReturnConstants.SUCCESS;
        }
        else {
            return ReturnConstants.FAILURE;
        }
    }

    @Override
    public Map<String, Object> getRemarks(Long problemId, int page, int limit) {
        Map<String, Object> map = new HashMap<>();
        Page<ProblemInfo> pageParam = new Page<>(page, limit);
        problemInfoMapper.selectPage(pageParam, new QueryWrapper<ProblemInfo>()
                .eq("type", 1)
                .eq("problem_id",problemId)
                .orderByDesc("modify"));
        map.put("data",pageParam.getRecords());
        map.put("count",pageParam.getTotal());
        return map;
    }

    @Override
    public Map<String, Object> getFiles(Long problemId, int page, int limit) {
        Map<String, Object> map = new HashMap<>();
        Page<ProblemInfo> pageParam = new Page<>(page, limit);
        problemInfoMapper.selectPage(pageParam, new QueryWrapper<ProblemInfo>()
                .eq("type", 2)
                .eq("problem_id",problemId)
                .orderByDesc("modify"));
        map.put("data",pageParam.getRecords());
        map.put("count",pageParam.getTotal());
        return map;
    }

    @Override
    public R addRemarks(Long problemId, String remark) {
        User user = (User) session.getAttribute(Constants.LOGIN_USER_KEY);
        ProblemInfo problemInfo = new ProblemInfo();
        problemInfo.setProblemId(problemId);
        problemInfo.setContext(remark);
        problemInfo.setType(1);
        problemInfo.setEmail(user.getEmail());
        problemInfo.setUserId(user.getId());
        problemInfo.setUsername(user.getName());
        problemInfoMapper.insert(problemInfo);
        //更新修改时间
        noticeUtil.updateProblemModify(problemId);
        return ReturnConstants.SUCCESS;
    }

    @Override
    public R updateRemarks(ProblemInfo info) {
        User user = (User) session.getAttribute(Constants.LOGIN_USER_KEY);
        info.setEmail(user.getEmail());
        info.setUserId(user.getId());
        info.setUsername(user.getName());

        problemInfoMapper.update(info, new UpdateWrapper<ProblemInfo>().eq("id",info.getId()));

        //更新修改时间
        ProblemInfo problemInfo = problemInfoMapper.selectById(info.getId());
        noticeUtil.updateProblemModify(problemInfo.getProblemId());
        return ReturnConstants.SUCCESS;
    }

    @Override
    public R deleteRemarks(Long infoId) {
        //先获取
        ProblemInfo problemInfo = problemInfoMapper.selectById(infoId);
        problemInfoMapper.deleteById(infoId);

        //更新修改时间
        noticeUtil.updateProblemModify(problemInfo.getProblemId());
        return ReturnConstants.SUCCESS;
    }

    @Override
    public R transferTask(Long problemId, String email) {
        ProblemDto problem = problemMapper.selectDetailById(problemId);
        User loginUser = (User) session.getAttribute(Constants.LOGIN_USER_KEY);
        User toUser = userMapper.selectOne(new QueryWrapper<User>().eq("email", email));
        List<Task> tasks = taskService.createTaskQuery().processInstanceId(problem.getInstanceId()).taskCandidateUser(loginUser.getEmail()).list();
        for (Task task : tasks) {
            taskService.deleteCandidateUser(task.getId(), loginUser.getEmail());
            taskService.addCandidateUser(task.getId(), email);
        }
        ProblemInfo problemInfo = new ProblemInfo();
        problemInfo.setProblemId(problemId);
        problemInfo.setContext(loginUser.getName() + "(" + loginUser.getEmail() + ") 把隐患" + problem.getName() + "移交给" + toUser.getName() + "(" + toUser.getEmail() + ")");
        problemInfo.setType(1);
        problemInfo.setEmail(loginUser.getEmail());
        problemInfo.setUserId(loginUser.getId());
        problemInfo.setUsername(loginUser.getName());
        noticeUtil.insertRemark(problemInfo);

        HashMap<String, Object> map = new HashMap<>();
        map.put("type", 4);
        map.put("userId", loginUser.getId());
        map.put("username", loginUser.getName());
        map.put("email", loginUser.getEmail());
        map.put("problemId",problem.getId());
        map.put("problemName",problem.getName());
        map.put("priority",problem.getPriority());
        map.put("toUserId", toUser.getId());
        map.put("toUsername", toUser.getName());
        map.put("toEmail", toUser.getEmail());
        noticeUtil.insertRedis(map);
        return ReturnConstants.SUCCESS;
    }

    @Override
    public InputStream speedOfProgress(Long problemId) {
        Problem problem = problemMapper.selectById(problemId);
        String processInstanceId = problem.getInstanceId();

        ProcessDiagramGenerator ge = new DefaultProcessDiagramGenerator();
        // 获取历史流程实例
        HistoricProcessInstance historicProcessInstance = historyService.createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        // 获取流程中已经执行的节点，按照执行先后顺序排序
        List<HistoricActivityInstance> historicActivityInstances = historyService.createHistoricActivityInstanceQuery().processInstanceId(processInstanceId)
                .orderByHistoricActivityInstanceId().asc().list();
        // 高亮已经执行流程节点ID集合
        List<String> highLightedActivitiIds = new ArrayList<>();
        for (HistoricActivityInstance historicActivityInstance : historicActivityInstances) {
            highLightedActivitiIds.add(historicActivityInstance.getActivityId());
        }

        BpmnModel bpmnModel = repositoryService.getBpmnModel(historicProcessInstance.getProcessDefinitionId());
        // 高亮流程已发生流转的线id集合
        List<String> highLightedFlowIds = getHighLightedFlows(bpmnModel, historicActivityInstances);

        // 使用默认配置获得流程图表生成器，并生成追踪图片字符流
        return ge.generateDiagram(bpmnModel, highLightedActivitiIds, highLightedFlowIds, "宋体", "微软雅黑", "黑体");
    }

    @Override
    public R closeTask(Long problemId) {
        Problem problem = problemMapper.selectById(problemId);
        User user = (User) session.getAttribute(Constants.LOGIN_USER_KEY);
        runtimeService.deleteProcessInstance(problem.getInstanceId(), "隐患关闭");

        //添加备注
        ProblemInfo problemInfo = new ProblemInfo();
        problemInfo.setType(1);
        problemInfo.setProblemId(problem.getId());
        problemInfo.setEmail(user.getEmail());
        problemInfo.setUsername(user.getName());
        problemInfo.setUserId(user.getId());
        problemInfo.setContext(user.getName() + "(" + user.getEmail()  + ") 关闭了问题：" + problem.getName());
        noticeUtil.insertRemark(problemInfo);

        //设置关注
        ProblemObserver problemObserver = new ProblemObserver();
        problemObserver.setProblemId(problem.getId());
        problemObserver.setEmail(user.getEmail());
        problemObserver.setUserId(user.getId());
        noticeUtil.insertObserve(problemObserver);

        //保存到redis中
        Map<String, Object> map = new HashMap<>();
        map.put("type", 3);
        map.put("userId", user.getId());
        map.put("username", user.getName());
        map.put("email", user.getEmail());
        map.put("problemId",problem.getId());
        map.put("problemName",problem.getName());
        map.put("priority",problem.getPriority());
        noticeUtil.insertRedis(map);

        String message = user.getName() + "(" + user.getEmail() + ") 关闭了隐患：" + problem.getName();
        noticeUtil.noticeMail(problem.getId(), message);

        noticeUtil.updateProblemModify(problemId);
        return ReturnConstants.SUCCESS;
    }

    @Override
    @Transactional
    public R deleteTask(Long problemId) {
        Problem problem = problemMapper.selectById(problemId);
        User user = (User) session.getAttribute(Constants.LOGIN_USER_KEY);
        String message = user.getName() + "(" + user.getEmail() + ") 删除了隐患：" + problem.getName();
        noticeUtil.noticeMail(problem.getId(), message);

        Map<String, Object> map = new HashMap<>();
        map.put("type", 2);
        map.put("userId", user.getId());
        map.put("username", user.getName());
        map.put("email", user.getEmail());
        map.put("problemId",problem.getId());
        map.put("problemName",problem.getName());
        map.put("priority",problem.getPriority());
        noticeUtil.insertRedis(map);

        runtimeService.deleteProcessInstance(problem.getInstanceId(), "隐患删除");
        historyService.deleteHistoricProcessInstance(problem.getInstanceId());
        problemObserverMapper.delete(new QueryWrapper<ProblemObserver>().eq("problem_id",problemId));
        problemInfoMapper.delete(new QueryWrapper<ProblemInfo>().eq("problem_id",problemId));
        problemMapper.deleteById(problemId);
        return ReturnConstants.SUCCESS;
    }

    private static List<String> getHighLightedFlows(BpmnModel bpmnModel, List<HistoricActivityInstance> historicActivityInstances) {
        // 高亮流程已发生流转的线id集合
        List<String> highLightedFlowIds = new ArrayList<>();
        // 全部活动节点
        List<FlowNode> historicActivityNodes = new ArrayList<>();
        // 已完成的历史活动节点
        List<HistoricActivityInstance> finishedActivityInstances = new ArrayList<>();

        for (HistoricActivityInstance historicActivityInstance : historicActivityInstances) {
            FlowNode flowNode = (FlowNode) bpmnModel.getMainProcess().getFlowElement(historicActivityInstance.getActivityId(), true);
            historicActivityNodes.add(flowNode);
            if (historicActivityInstance.getEndTime() != null) {
                finishedActivityInstances.add(historicActivityInstance);
            }
        }

        FlowNode currentFlowNode;
        FlowNode targetFlowNode;
        // 遍历已完成的活动实例，从每个实例的outgoingFlows中找到已执行的
        for (HistoricActivityInstance currentActivityInstance : finishedActivityInstances) {
            // 获得当前活动对应的节点信息及outgoingFlows信息
            currentFlowNode = (FlowNode) bpmnModel.getMainProcess().getFlowElement(currentActivityInstance.getActivityId(), true);
            List<SequenceFlow> sequenceFlows = currentFlowNode.getOutgoingFlows();

            if ("parallelGateway".equals(currentActivityInstance.getActivityType()) || "inclusiveGateway".equals(currentActivityInstance.getActivityType())) {
                // 遍历历史活动节点，找到匹配流程目标节点的
                for (SequenceFlow sequenceFlow : sequenceFlows) {
                    targetFlowNode = (FlowNode) bpmnModel.getMainProcess().getFlowElement(sequenceFlow.getTargetRef(), true);
                    if (historicActivityNodes.contains(targetFlowNode)) {
                        highLightedFlowIds.add(targetFlowNode.getId());
                    }
                }
            } else {
                List<Map<String, Object>> tempMapList = new ArrayList<>();
                for (SequenceFlow sequenceFlow : sequenceFlows) {
                    for (HistoricActivityInstance historicActivityInstance : historicActivityInstances) {
                        if (historicActivityInstance.getActivityId().equals(sequenceFlow.getTargetRef())) {
                            Map<String, Object> map = new HashMap<>();
                            map.put("highLightedFlowId", sequenceFlow.getId());
                            map.put("highLightedFlowStartTime", historicActivityInstance.getStartTime().getTime());
                            tempMapList.add(map);
                        }
                    }
                }

                if (!CollectionUtils.isEmpty(tempMapList)) {
                    // 遍历匹配的集合，取得开始时间最早的一个
                    long earliestStamp = 0L;
                    String highLightedFlowId = null;
                    for (Map<String, Object> map : tempMapList) {
                        long highLightedFlowStartTime = Long.parseLong(map.get("highLightedFlowStartTime").toString());
                        if (earliestStamp == 0 || earliestStamp >= highLightedFlowStartTime) {
                            highLightedFlowId = map.get("highLightedFlowId").toString();
                            earliestStamp = highLightedFlowStartTime;
                        }
                    }

                    highLightedFlowIds.add(highLightedFlowId);
                }

            }

        }
        return highLightedFlowIds;
    }
}
