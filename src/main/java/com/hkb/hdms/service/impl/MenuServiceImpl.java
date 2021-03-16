package com.hkb.hdms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hkb.hdms.base.Constants;
import com.hkb.hdms.mapper.MenuMapper;
import com.hkb.hdms.model.pojo.Menu;
import com.hkb.hdms.model.pojo.User;
import com.hkb.hdms.model.vo.MenuVo;
import com.hkb.hdms.service.MenuService;
import com.hkb.hdms.utils.MenuTreeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author huangkebing
 * 2021/03/16
 */
@Service
public class MenuServiceImpl extends ServiceImpl<MenuMapper, Menu> implements MenuService {

    private final MenuMapper menuMapper;

    private final HttpSession session;

    private final MenuTreeUtil treeUtil;

    @Autowired
    public MenuServiceImpl(MenuMapper menuMapper, HttpSession session, MenuTreeUtil treeUtil) {
        this.menuMapper = menuMapper;
        this.session = session;
        this.treeUtil = treeUtil;
    }

    /**
     * 根据登录的用户角色动态生成资源
     */
    @Override
    public Map<String, Object> initMenu() {
        Map<String, Object> map = new HashMap<>();
        Map<String, Object> home = new HashMap<>();
        Map<String, Object> logo = new HashMap<>();

        //获取当前登录的用户
        User loginUser = (User) session.getAttribute(Constants.LOGIN_USER_KEY);

        List<Menu> menus = menuMapper.selectByRole(loginUser.getRole());
        List<MenuVo> menuVos = new ArrayList<>();

        for (Menu menu : menus) {
            MenuVo menuVo = new MenuVo();
            menuVo.setId(menu.getId());
            menuVo.setPid(menu.getPid());
            menuVo.setHref(menu.getHref());
            menuVo.setTitle(menu.getTitle());
            menuVo.setIcon(menu.getIcon());
            menuVo.setTarget(menu.getTarget());
            menuVos.add(menuVo);
        }

        map.put("menuInfo", treeUtil.toTree(menuVos, 0L));

        home.put("title", "首页");
        //判断系统管理员
        if (loginUser.getRole() == Constants.ADMIN_ID) {
            home.put("href", Constants.ADMIN_INDEX);
        } else {
            home.put("href", Constants.OTHER_INDEX);
        }

        logo.put("title", "HDMS");
        logo.put("image", Constants.LOGO_PATH);
        logo.put("href", "");

        map.put("homeInfo", home);
        map.put("logoInfo", logo);
        return map;
    }
}
