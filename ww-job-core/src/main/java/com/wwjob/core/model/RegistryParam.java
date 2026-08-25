package com.wwjob.core.model;

/**
 * @author 王威
 * @version 1.0
 */
public class RegistryParam {
    private String registryKey;
    private String registryValue;

    public RegistryParam() {
    }

    public RegistryParam(String registryKey, String registryValue) {
        this.registryKey = registryKey;
        this.registryValue = registryValue;
    }

    /**
     * 获取
     * @return registryKey
     */
    public String getRegistryKey() {
        return registryKey;
    }

    /**
     * 设置
     * @param registryKey
     */
    public void setRegistryKey(String registryKey) {
        this.registryKey = registryKey;
    }

    /**
     * 获取
     * @return registryValue
     */
    public String getRegistryValue() {
        return registryValue;
    }

    /**
     * 设置
     * @param registryValue
     */
    public void setRegistryValue(String registryValue) {
        this.registryValue = registryValue;
    }

    public String toString() {
        return "RegistryParam{registryKey = " + registryKey + ", registryValue = " + registryValue + "}";
    }
}
