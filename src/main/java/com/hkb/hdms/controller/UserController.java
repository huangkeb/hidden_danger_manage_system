package com.hkb.hdms.controller;

import com.hkb.hdms.model.pojo.User;
import com.hkb.hdms.service.SysUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 处理用户信息相关接口
 *
 * @author huangkebing
 * 2021/03/15
 */
@Controller
@RequestMapping("/user")
public class UserController {

    private final SysUserService sysUserService;

    @Autowired
    public UserController(SysUserService sysUserService) {
        this.sysUserService = sysUserService;
    }

    /**
     * 修改密码页面跳转
     */
    @RequestMapping("/password.html")
    public Object userPasswordPage() {
        return "user/password";
    }

    @RequestMapping("/user.html")
    public Object userPage(){
        return "user/user";
    }

    /**
     * 修改密码接口
     * @param old 旧密码
     * @param now 新密码
     * @param check 确认密码
     * */
    @PostMapping("/resetPassword")
    @ResponseBody
    public Object resetPassword(String old, String now,String check){
        return sysUserService.resetPassword(old, now, check);
    }

    @GetMapping("/getUser")
    @ResponseBody
    public Object getUser(User user,int limit,int page){
        return sysUserService.getUser(user, limit, page);
    }

    @PostMapping("/addUser")
    @ResponseBody
    public Object addUser(String email, Integer role){
        User user = new User();
        user.setEmail(email);
        user.setRole(role);
        return sysUserService.addUser(user);
    }

    @PostMapping("/userState")
    @ResponseBody
    public Object userState(Long id, int live){
        User user = new User();
        user.setId(id);
        user.setLive(live);
        return sysUserService.updateUser(user);
    }

    @PostMapping("/updateUser")
    @ResponseBody
    public Object updateUser(Long id, int role){
        User user = new User();
        user.setId(id);
        user.setRole(role);
        return sysUserService.updateUser(user);
    }

    @PostMapping("/deleteUser")
    @ResponseBody
    public Object deleteUser(Long id){
        return sysUserService.deleteUser(id);
    }
}
