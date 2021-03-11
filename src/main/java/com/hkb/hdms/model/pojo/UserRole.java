package com.hkb.hdms.model.pojo;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.util.Date;

/**
 * role表实体类
 *
 * @author huangkebing
 * 2021/03/11
 */
@Data
@TableName("hdms_role")
public class UserRole {
    @TableId(value = "id", type = IdType.AUTO)
    private long id;

    private String name;

    private String description;

    @TableField(value = "`create`", fill = FieldFill.INSERT)
    private Date create;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date modify;
}
