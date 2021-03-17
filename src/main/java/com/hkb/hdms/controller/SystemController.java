package com.hkb.hdms.controller;

import com.hkb.hdms.service.MenuService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 系统资源相关接口
 *
 * @author huangkebing
 * 2021/03/13
 */
@Controller
@RequestMapping("/system")
public class SystemController {

    private final MenuService menuService;

    @Autowired
    public SystemController(MenuService menuService) {
        this.menuService = menuService;
    }

    @RequestMapping("/test.html")
    public Object test() {
        return "test";
    }

    /**
     * 跳转到资源管理页面
     */
    @RequestMapping("/menu.html")
    public Object menuPage() {
        return "system/menu";
    }

    /**
     * 首页渲染数据
     */
    @GetMapping("/menuInit")
    @ResponseBody
    public Object menuInit() {
        return menuService.initMenu();
    }

    /**
     * 菜单管理页面，渲染页面接口
     */
    @GetMapping("/getMenu")
    @ResponseBody
    public Object getMenu() {
        return menuService.getMenu();
    }
}
