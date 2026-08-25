package com.wwjob.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * @author 王威
 * @version 1.0
 */
@TableName("job_group")
public class JobGroup {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String appName;
    private String title;
    private Integer addressType;
    private String addressList;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public JobGroup() {
    }

    public JobGroup(Long id, String appName, String title, Integer addressType, String addressList, LocalDateTime createTime, LocalDateTime updateTime) {
        this.id = id;
        this.appName = appName;
        this.title = title;
        this.addressType = addressType;
        this.addressList = addressList;
        this.createTime = createTime;
        this.updateTime = updateTime;
    }

    /**
     * 获取
     * @return id
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置
     * @param id
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * 获取
     * @return appName
     */
    public String getAppName() {
        return appName;
    }

    /**
     * 设置
     * @param appName
     */
    public void setAppName(String appName) {
        this.appName = appName;
    }

    /**
     * 获取
     * @return title
     */
    public String getTitle() {
        return title;
    }

    /**
     * 设置
     * @param title
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * 获取
     * @return addressType
     */
    public Integer getAddressType() {
        return addressType;
    }

    /**
     * 设置
     * @param addressType
     */
    public void setAddressType(Integer addressType) {
        this.addressType = addressType;
    }

    /**
     * 获取
     * @return addressList
     */
    public String getAddressList() {
        return addressList;
    }

    /**
     * 设置
     * @param addressList
     */
    public void setAddressList(String addressList) {
        this.addressList = addressList;
    }

    /**
     * 获取
     * @return createTime
     */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /**
     * 设置
     * @param createTime
     */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    /**
     * 获取
     * @return updateTime
     */
    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    /**
     * 设置
     * @param updateTime
     */
    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public String toString() {
        return "JobGroup{id = " + id + ", appName = " + appName + ", title = " + title + ", addressType = " + addressType + ", addressList = " + addressList + ", createTime = " + createTime + ", updateTime = " + updateTime + "}";
    }
}
